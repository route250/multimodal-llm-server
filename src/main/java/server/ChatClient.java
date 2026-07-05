package server;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;
import audio.AudioDiagnostics;
import llm.ChatMessage;
import llm.LanguageModel;
import llm.LanguageModelException;
import llm.OpenAiResponsesLanguageModel;
import model.download.SmartTurnV3ModelDownloader;
import onnx.OnnxModelException;
import stt.Lfm2AudioSpeechToText;
import stt.SpeechToTextException;
import stt.Transcription;
import tts.Lfm2AudioTextToSpeech;
import tts.StreamingTextChunker;
import tts.TextToSpeech;
import tts.TextToSpeechException;
import vad.VadAudioProcessor;
import vad.VadAudioProcessor.TranscriptionKind;
import vad.VadAudioProcessor.TranscriptionResult;
import vad.VadAudioProcessor.TranscriptionStarted;
import vad.smartturn.LazySmartTurnV3;

public class ChatClient {
    private static final int MAX_HISTORY_MESSAGES = 20;

    private final String id;
    private final ChatGroup chatGroup;
    private final LinkedBlockingQueue<ServerEvent> events = new LinkedBlockingQueue<>();
    private final VadAudioProcessor audioProcessor;
    private final AudioDiagnostics.Context diagnosticsContext;
    private final LanguageModel languageModel;
    private final TextToSpeech textToSpeech;
    private final Object audioTaskLock = new Object();
    private final Object lifecycleLock = new Object();
    private final Object conversationLock = new Object();
    private final Object playbackControlLock = new Object();
    private final List<ChatMessage> conversationHistory = new ArrayList<>();
    private final Set<Long> canceledAssistantTurnIds = new HashSet<>();
    private CompletableFuture<Void> audioTaskTail = CompletableFuture.completedFuture(null);
    private Future<?> activeAssistantTask;
    private long currentAssistantTurnId;
    private long currentInterruptionId;
    private long activePlaybackAssistantTurnId;
    private long activePlaybackStartSampleIndex = Long.MIN_VALUE;
    private long sttWaitAssistantTurnId;
    private long sttWaitSpeechSequenceId = Long.MIN_VALUE;
    private boolean assistantTurnActive;
    private boolean sttWaitActive;
    private boolean closed;

    public ChatClient(String id, ChatGroup chatGroup) {
        this(id, chatGroup, new VadAudioProcessor(
                new LazySmartTurnV3(smartTurnModelPath()),
                new Lfm2AudioSpeechToText(),
                chatGroup::execute),
                new OpenAiResponsesLanguageModel(),
                defaultTextToSpeech());
    }

    ChatClient(String id, ChatGroup chatGroup, VadAudioProcessor audioProcessor) {
        this(id, chatGroup, audioProcessor, new OpenAiResponsesLanguageModel(), TextToSpeech.disabled());
    }

    ChatClient(String id, ChatGroup chatGroup, VadAudioProcessor audioProcessor, LanguageModel languageModel) {
        this(id, chatGroup, audioProcessor, languageModel, TextToSpeech.disabled());
    }

    ChatClient(
            String id,
            ChatGroup chatGroup,
            VadAudioProcessor audioProcessor,
            LanguageModel languageModel,
            TextToSpeech textToSpeech) {
        this.id = id;
        this.chatGroup = chatGroup;
        this.audioProcessor = audioProcessor;
        this.diagnosticsContext = AudioDiagnostics.context(chatGroup.id(), id);
        this.languageModel = languageModel;
        this.textToSpeech = textToSpeech;
        this.audioProcessor.setDiagnosticsContext(chatGroup.id(), id);
        this.audioProcessor.setTranscriptionStartedListener(this::handleTranscriptionStarted);
    }

    public String id() {
        return id;
    }

    public int nextId() {
        return this.chatGroup.nextId();
    }
    public void execute(Runnable r) {
        this.chatGroup.execute(r);
    }
    public Future<?> submit(Runnable task) {
        return this.chatGroup.submit(task);
    }
    public <T> Future<T> submit(Runnable task, T result ) {
        return this.chatGroup.submit(task,result);
    }
    public <T> Future<T> submit(Callable<T> task) {
        return this.chatGroup.submit(task);
    }

    public LinkedBlockingQueue<ServerEvent> events() {
        return events;
    }

    public void handle(ChatRequest request) {
        if ("audio".equals(request.type())) {
            handleAudio(request);
            return;
        }
        if ("text".equals(request.type())) {
            handleText(request);
            return;
        }
        sendToGroup(request.toEvent());
    }

    private void handleText(ChatRequest request) {
        String text = request.textBody().trim();
        if (!text.isBlank()) {
            startAssistantReply(text);
        }
    }

    private void handleAudio(ChatRequest request) {
        handleAudio(request, Long.MIN_VALUE, Long.MIN_VALUE);
    }

    public void handleAudio(ChatRequest request, long clientStartSampleIndex, long clientEndSampleIndexExclusive) {
        if (!request.isPcmVadAudio()) {
            throw new HttpRequestException(400,
                    "unsupported audio content type. Use: audio/pcm-vad; rate=16000; channels=1; format=s16le; vad-frame-samples=256");
        }
        byte[] body = request.body().clone();
        byte[] vadBytes = request.vadBytes().clone();
        synchronized (audioTaskLock) {
            audioTaskTail = audioTaskTail.handle((ignored, error) -> null)
                    .thenRunAsync(
                            () -> processAudio(body, vadBytes, clientStartSampleIndex, clientEndSampleIndexExclusive),
                            this::execute);
        }
    }

    private void processAudio(
            byte[] body,
            byte[] vadBytes,
            long clientStartSampleIndex,
            long clientEndSampleIndexExclusive) {
        if (isClosed()) {
            return;
        }
        try {
            AudioDiagnostics.log("audio-chunk-process", diagnosticsContext, AudioDiagnostics.fields(
                    "startSampleIndex", clientStartSampleIndex == Long.MIN_VALUE ? null : clientStartSampleIndex,
                    "endSampleIndexExclusive", clientEndSampleIndexExclusive == Long.MIN_VALUE ? null : clientEndSampleIndexExclusive,
                    "pcmBytes", body.length,
                    "vadBytes", vadBytes.length));
            Optional<TranscriptionResult> result = audioProcessor.acceptPcm16LeWithVadDetailed(body, vadBytes);
            result.ifPresent(this::handleTranscriptionResult);
        } catch (IllegalArgumentException e) {
            sendAudioProcessingFailure(e);
        } catch (SpeechToTextException e) {
            sendAudioProcessingFailure(e);
        } catch (OnnxModelException e) {
            sendAudioProcessingFailure(e);
        } catch (RuntimeException e) {
            sendAudioProcessingFailure(e);
        }
    }

    public void handlePlayback(PlaybackEvent playbackEvent) {
        AudioDiagnostics.log("playback-report", diagnosticsContext, AudioDiagnostics.fields(
                "assistantTurnId", playbackEvent.assistantTurnId(),
                "state", playbackEvent.state(),
                "clientMicSampleIndex", playbackEvent.clientMicSampleIndex()));
        synchronized (playbackControlLock) {
            String state = playbackEvent.state();
            if ("start".equals(state) || "resume".equals(state)) {
                activePlaybackAssistantTurnId = playbackEvent.assistantTurnId();
                activePlaybackStartSampleIndex = Math.max(0, playbackEvent.clientMicSampleIndex());
                return;
            }
            if ("pause".equals(state) || "stop".equals(state) || "end".equals(state) || "cancel".equals(state)) {
                finishPlaybackTracking(playbackEvent.assistantTurnId(), playbackEvent.clientMicSampleIndex());
            }
        }
    }

    private void finishPlaybackTracking(long assistantTurnId, long clientMicSampleIndex) {
        if (activePlaybackStartSampleIndex == Long.MIN_VALUE
                || activePlaybackAssistantTurnId != assistantTurnId
                || clientMicSampleIndex < activePlaybackStartSampleIndex) {
            return;
        }
        activePlaybackStartSampleIndex = Long.MIN_VALUE;
    }

    private void handleTranscriptionStarted(TranscriptionStarted started) {
        ServerEvent event = null;
        synchronized (playbackControlLock) {
            if (sttWaitActive && sttWaitSpeechSequenceId == started.speechSequenceId()) {
                return;
            }
            if (currentAssistantTurnId <= 0) {
                return;
            }
            currentInterruptionId++;
            sttWaitActive = true;
            sttWaitAssistantTurnId = currentAssistantTurnId;
            sttWaitSpeechSequenceId = started.speechSequenceId();
            event = ServerEvent.audioControl(
                    "pause",
                    sttWaitAssistantTurnId,
                    currentInterruptionId,
                    started.speechSequenceId(),
                    "stt-wait");
        }
        AudioDiagnostics.log("audio-control-send", diagnosticsContext, AudioDiagnostics.fields(
                "action", "pause",
                "assistantTurnId", event == null ? null : sttWaitAssistantTurnId,
                "interruptionId", currentInterruptionId,
                "speechSequenceId", started.speechSequenceId(),
                "reason", "stt-wait"));
        sendToGroupIfOpen(event);
    }

    private void resumePlaybackForEmptyTranscript(long speechSequenceId) {
        ServerEvent event = null;
        synchronized (playbackControlLock) {
            if (!sttWaitActive || sttWaitSpeechSequenceId != speechSequenceId) {
                return;
            }
            if (canceledAssistantTurnIds.contains(sttWaitAssistantTurnId)) {
                sttWaitActive = false;
                return;
            }
            event = ServerEvent.audioControl(
                    "resume",
                    sttWaitAssistantTurnId,
                    currentInterruptionId,
                    speechSequenceId,
                    "empty-stt");
            sttWaitActive = false;
        }
        AudioDiagnostics.log("audio-control-send", diagnosticsContext, AudioDiagnostics.fields(
                "action", "resume",
                "assistantTurnId", event == null ? null : sttWaitAssistantTurnId,
                "interruptionId", currentInterruptionId,
                "speechSequenceId", speechSequenceId,
                "reason", "empty-stt"));
        sendToGroupIfOpen(event);
    }

    private void handleTranscriptionResult(TranscriptionResult result) {
        String text = result.transcription().text();
        String transcript = text == null ? "" : text.trim();
        if (transcript.isBlank()) {
            if (result.kind() == TranscriptionKind.FINAL) {
                resumePlaybackForEmptyTranscript(result.speechSequenceId());
            }
            return;
        }
        cancelCurrentAssistantTurnForTranscript(result.speechSequenceId());
        startAssistantReply(transcript);
    }

    private void cancelCurrentAssistantTurnForTranscript(long speechSequenceId) {
        ServerEvent event = null;
        Future<?> taskToCancel = null;
        long canceledTurnId;
        synchronized (playbackControlLock) {
            canceledTurnId = sttWaitActive && sttWaitSpeechSequenceId == speechSequenceId
                    ? sttWaitAssistantTurnId
                    : currentAssistantTurnId;
            if (canceledTurnId > 0) {
                canceledAssistantTurnIds.add(canceledTurnId);
                event = ServerEvent.audioControl(
                        "cancel",
                        canceledTurnId,
                        currentInterruptionId,
                        speechSequenceId,
                        "user-transcript");
            }
            if (activeAssistantTask != null) {
                taskToCancel = activeAssistantTask;
            }
            sttWaitActive = false;
            if (currentAssistantTurnId == canceledTurnId) {
                assistantTurnActive = false;
            }
        }
        if (event != null) {
            AudioDiagnostics.log("audio-control-send", diagnosticsContext, AudioDiagnostics.fields(
                    "action", "cancel",
                    "assistantTurnId", canceledTurnId,
                    "interruptionId", currentInterruptionId,
                    "speechSequenceId", speechSequenceId,
                    "reason", "user-transcript"));
        }
        if (taskToCancel != null) {
            taskToCancel.cancel(true);
        }
        if (event != null) {
            sendToGroupIfOpen(event);
        }
    }

    private void startAssistantReply(String transcript) {
        long assistantTurnId = beginAssistantTurn();
        AudioDiagnostics.log("assistant-turn-start", diagnosticsContext, AudioDiagnostics.fields(
                "assistantTurnId", assistantTurnId,
                "transcriptChars", transcript.length(),
                "transcript", transcript));
        FutureTask<Void> task = new FutureTask<>(() -> {
            replyToTranscript(transcript, assistantTurnId);
            return null;
        });
        synchronized (playbackControlLock) {
            if (currentAssistantTurnId == assistantTurnId && !canceledAssistantTurnIds.contains(assistantTurnId)) {
                activeAssistantTask = task;
            } else {
                task.cancel(true);
            }
        }
        execute(task);
    }

    private void replyToTranscript(String transcript, long assistantTurnId) {
        if (isClosed()) {
            return;
        }
        synchronized (conversationLock) {
            StreamingTextChunker chunker = new StreamingTextChunker();
            StringBuilder assistantText = new StringBuilder();
            ChatMessage userMessage = new ChatMessage("user", transcript);
            List<ChatMessage> requestMessages = requestMessages(userMessage);
            try {
                sendToGroupIfOpen(ServerEvent.userMessage(transcript));
                languageModel.respondStreaming(requestMessages, delta -> {
                    if (!isAssistantTurnActive(assistantTurnId)) {
                        return;
                    }
                    assistantText.append(delta);
                    AudioDiagnostics.log("llm-message-delta", diagnosticsContext, AudioDiagnostics.fields(
                            "assistantTurnId", assistantTurnId,
                            "deltaChars", delta.length()));
                    sendToGroupIfOpen(ServerEvent.messageDelta(delta));
                    speak(assistantTurnId, chunker.append(delta));
                });
                speak(assistantTurnId, chunker.finish());
                if (!isAssistantTurnActive(assistantTurnId)) {
                    return;
                }
                remember(userMessage, assistantText.toString());
                AudioDiagnostics.log("assistant-turn-message-done", diagnosticsContext, AudioDiagnostics.fields(
                        "assistantTurnId", assistantTurnId,
                        "assistantTextChars", assistantText.length()));
                sendToGroupIfOpen(ServerEvent.messageDone());
                finishAssistantTurn(assistantTurnId);
            } catch (LanguageModelException e) {
                if (isAssistantTurnActive(assistantTurnId)) {
                    sendToGroupIfOpen(ServerEvent.system("llm request failed: " + e.getMessage()));
                }
                finishAssistantTurn(assistantTurnId);
            } catch (TextToSpeechException e) {
                if (isAssistantTurnActive(assistantTurnId)) {
                    sendToGroupIfOpen(ServerEvent.system("tts request failed: " + e.getMessage()));
                }
                finishAssistantTurn(assistantTurnId);
            }
        }
    }

    private long beginAssistantTurn() {
        synchronized (playbackControlLock) {
            currentAssistantTurnId++;
            assistantTurnActive = true;
            activeAssistantTask = null;
            return currentAssistantTurnId;
        }
    }

    private void finishAssistantTurn(long assistantTurnId) {
        synchronized (playbackControlLock) {
            if (currentAssistantTurnId == assistantTurnId && !canceledAssistantTurnIds.contains(assistantTurnId)) {
                assistantTurnActive = false;
                activeAssistantTask = null;
            }
        }
    }

    private boolean isAssistantTurnActive(long assistantTurnId) {
        synchronized (playbackControlLock) {
            return currentAssistantTurnId == assistantTurnId
                    && !canceledAssistantTurnIds.contains(assistantTurnId)
                    && !closed;
        }
    }

    private List<ChatMessage> requestMessages(ChatMessage userMessage) {
        List<ChatMessage> messages = new ArrayList<>(conversationHistory.size() + 1);
        messages.addAll(conversationHistory);
        messages.add(userMessage);
        return List.copyOf(messages);
    }

    private void remember(ChatMessage userMessage, String assistantText) {
        String responseText = assistantText.trim();
        if (responseText.isBlank()) {
            return;
        }
        conversationHistory.add(userMessage);
        conversationHistory.add(new ChatMessage("assistant", responseText));
        while (conversationHistory.size() > MAX_HISTORY_MESSAGES) {
            conversationHistory.removeFirst();
        }
    }

    private void speak(long assistantTurnId, Iterable<String> chunks) {
        for (String chunk : chunks) {
            if (!isAssistantTurnActive(assistantTurnId)) {
                return;
            }
            AudioDiagnostics.log("tts-chunk-start", diagnosticsContext, AudioDiagnostics.fields(
                    "assistantTurnId", assistantTurnId,
                    "textChars", chunk.length(),
                    "text", chunk));
            final int[] audioDeltaCount = {0};
            final long[] audioBase64Chars = {0};
            textToSpeech.synthesizeStreaming(chunk, audio -> {
                if (isAssistantTurnActive(assistantTurnId)) {
                    audioDeltaCount[0]++;
                    audioBase64Chars[0] += audio.data().length();
                    AudioDiagnostics.log("tts-audio-delta", diagnosticsContext, AudioDiagnostics.fields(
                            "assistantTurnId", assistantTurnId,
                            "deltaIndex", audioDeltaCount[0],
                            "base64Chars", audio.data().length(),
                            "format", audio.format(),
                            "sampleRate", audio.sampleRate()));
                    sendToGroupIfOpen(ServerEvent.audioDelta(
                            audio.data(),
                            audio.format(),
                            audio.sampleRate(),
                            assistantTurnId));
                }
            });
            AudioDiagnostics.log("tts-chunk-done", diagnosticsContext, AudioDiagnostics.fields(
                    "assistantTurnId", assistantTurnId,
                    "audioDeltaCount", audioDeltaCount[0],
                    "audioBase64Chars", audioBase64Chars[0]));
        }
    }

    private void sendAudioProcessingFailure(RuntimeException e) {
        AudioDiagnostics.log("audio-processing-error", diagnosticsContext, AudioDiagnostics.fields(
                "errorClass", e.getClass().getName(),
                "errorMessage", e.getMessage()));
        sendToGroupIfOpen(ServerEvent.system("audio processing failed: " + e.getMessage()));
    }

    private void sendToGroupIfOpen(ServerEvent event) {
        synchronized (lifecycleLock) {
            if (!closed) {
                sendToGroup(event);
            }
        }
    }

    private void sendToGroup(ServerEvent event) {
        chatGroup.publish(event);
    }

    public void receive(ServerEvent event) {
        events.offer(event);
    }

    public void close() {
        Future<?> taskToCancel;
        synchronized (lifecycleLock) {
            closed = true;
        }
        synchronized (playbackControlLock) {
            taskToCancel = activeAssistantTask;
            activeAssistantTask = null;
            assistantTurnActive = false;
            sttWaitActive = false;
        }
        if (taskToCancel != null) {
            taskToCancel.cancel(true);
        }
    }

    private boolean isClosed() {
        synchronized (lifecycleLock) {
            return closed;
        }
    }

    private static Path smartTurnModelPath() {
        return SmartTurnV3ModelDownloader.MODEL_PATH.toAbsolutePath().normalize();
    }

    private static TextToSpeech defaultTextToSpeech() {
        return new Lfm2AudioTextToSpeech();
    }

    public record PlaybackEvent(long assistantTurnId, String state, long clientMicSampleIndex) {
        public PlaybackEvent {
            if (state == null || state.isBlank()) {
                throw new IllegalArgumentException("playback state is required");
            }
            state = state.trim().toLowerCase();
        }
    }
}
