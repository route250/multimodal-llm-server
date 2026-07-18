package server;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;

import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseFunctionToolCall;

import audio.AudioDiagnostics;
import audio.AudioProcessor;
import audio.AudioProcessor.SpeechStateChange;
import audio.AudioProcessor.TranscriptionKind;
import audio.AudioProcessor.TranscriptionResult;
import audio.AudioProcessor.TranscriptionStarted;
import audio.stt.Lfm2AudioSpeechToText;
import audio.stt.SpeechToTextException;
import audio.tts.AudioDelta;
import audio.tts.Lfm2AudioTextToSpeech;
import audio.tts.StreamingTextChunker;
import audio.tts.TextToSpeech;
import audio.tts.TextToSpeechException;
import audio.vad.smartturn.LazySmartTurnV3;
import facedb.FaceDB;
import facedb.FacePossibility;
import json.Json;
import json.JsonFields;
import llm.ChatMessage;
import llm.LLM;
import llm.LlmOpenAI;
import llm.LanguageModelException;
import model.download.SmartTurnV3ModelDownloader;
import onnx.OnnxModelException;

public class ChatClient {
    /** 設定がない ChatClient が使用するシステムプロンプトです。 */
    public static final String DEFAULT_SYSTEM_PROMPT = """
                あなたは会話AIの「りり」です。目の前の相手と親しみのある会話をします。AIの発言だけを出力して下さい。
                あなたのセリフのみ出力して下さい。
                """;
    private static final int MAX_HISTORY_MESSAGES = 20;

    private final String id;
    private final ChatGroup chatGroup;
    private final LinkedBlockingQueue<ServerEvent> events = new LinkedBlockingQueue<>();
    private final AudioProcessor audioProcessor;
    private final AudioDiagnostics.Context diagnosticsContext;
    private volatile LanguageModelContext languageModel;
    private final TextToSpeech textToSpeech;
    private final Object audioTaskLock = new Object();
    private final Object lifecycleLock = new Object();
    private final Object conversationLock = new Object();
    private final Object playbackControlLock = new Object();
    private final Object partialTranscriptLock = new Object();
    private final Object faceTrackLock = new Object();
    private final List<ChatMessage> conversationHistory = new ArrayList<>();
    private final Set<Long> canceledAssistantTurnIds = new HashSet<>();
    private final Set<Long> rememberedUserAssistantTurnIds = new HashSet<>();
    private final Map<Long, PendingUserMessage> pendingUserMessages = new LinkedHashMap<>();
    private final Map<AssistantChunkKey, PendingAssistantChunk> pendingAssistantChunks = new LinkedHashMap<>();
    private final Map<Long, Integer> rememberedAssistantMessageIndexes = new HashMap<>();
    private final Map<Long, String> partialTranscripts = new LinkedHashMap<>();
    // 同一セッション内で、入室済みの顔トラックを区別する。
    private final Map<String, String> trackmap = new LinkedHashMap<>();
    private final Map<String, FaceEventResult> activeFaceTracks = new LinkedHashMap<>();
    private CompletableFuture<Void> audioTaskTail = CompletableFuture.completedFuture(null);
    private Future<?> activeAssistantTask;
    private long currentAssistantTurnId;
    private long nextAssistantChunkId;
    private long currentInterruptionId;
    private long activePlaybackAssistantTurnId;
    private long activePlaybackStartSampleIndex = Long.MIN_VALUE;
    private long sttWaitAssistantTurnId;
    private long sttWaitSpeechSequenceId = Long.MIN_VALUE;
    private boolean assistantTurnActive;
    private boolean sttWaitActive;
    private boolean closed;

    public ChatClient(String id, ChatGroup chatGroup) {
        this(id, chatGroup, new AudioProcessor(
                new LazySmartTurnV3(smartTurnModelPath()),
                new Lfm2AudioSpeechToText(),
                chatGroup::execute),
                chatGroup.server().languageModelSettings(chatGroup.id()),
                defaultTextToSpeech());
    }

    private ChatClient(
            String id,
            ChatGroup chatGroup,
            AudioProcessor audioProcessor,
            GroupLlmSettings settings,
            TextToSpeech textToSpeech) {
        this(id, chatGroup, audioProcessor, new LlmOpenAI(settings.toConfig()), settings.systemPrompt(), textToSpeech);
    }

    ChatClient(String id, ChatGroup chatGroup, AudioProcessor audioProcessor) {
        this(id, chatGroup, audioProcessor, new LlmOpenAI(), DEFAULT_SYSTEM_PROMPT, TextToSpeech.disabled());
    }

    ChatClient(String id, ChatGroup chatGroup, AudioProcessor audioProcessor, LLM llm) {
        this(id, chatGroup, audioProcessor, llm, DEFAULT_SYSTEM_PROMPT, TextToSpeech.disabled());
    }

    ChatClient(
            String id,
            ChatGroup chatGroup,
            AudioProcessor audioProcessor,
            LLM llm,
            TextToSpeech textToSpeech) {
        this(id, chatGroup, audioProcessor, llm, DEFAULT_SYSTEM_PROMPT, textToSpeech);
    }

    ChatClient(
            String id,
            ChatGroup chatGroup,
            AudioProcessor audioProcessor,
            LLM llm,
            String systemPrompt,
            TextToSpeech textToSpeech) {
        this.id = id;
        this.chatGroup = chatGroup;
        this.audioProcessor = audioProcessor;
        this.diagnosticsContext = AudioDiagnostics.context(chatGroup.id(), id);
        this.languageModel = new LanguageModelContext(llm, systemPrompt);
        this.textToSpeech = textToSpeech;
        this.audioProcessor.setDiagnosticsContext(chatGroup.id(), id);
        this.audioProcessor.setSpeechStateListener(this::handleSpeechStateChange);
        this.audioProcessor.setTranscriptionStartedListener(this::handleTranscriptionStarted);
    }

    public String id() {
        return id;
    }

    /** Group 設定の保存後に、次の LLM 呼び出しから使用するモデルを切り替えます。 */
    void setLanguageModel(LLM llm, String systemPrompt) {
        this.languageModel = new LanguageModelContext(llm, systemPrompt);
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
        byte[] rmsBytes = request.rmsBytes().clone();
        synchronized (audioTaskLock) {
            audioTaskTail = audioTaskTail.handle((ignored, error) -> null)
                    .thenRunAsync(
                            () -> processAudio(body, vadBytes, rmsBytes, clientStartSampleIndex, clientEndSampleIndexExclusive),
                            this::execute);
        }
    }

    private void processAudio(
            byte[] body,
            byte[] vadBytes,
            byte[] rmsBytes,
            long clientStartSampleIndex,
            long clientEndSampleIndexExclusive) {
        if (isClosed()) {
            return;
        }
        try {
            AudioDiagnostics.log("audio-chunk-process", diagnosticsContext, Json.fields(
                    "startSampleIndex", clientStartSampleIndex == Long.MIN_VALUE ? null : clientStartSampleIndex,
                    "endSampleIndexExclusive", clientEndSampleIndexExclusive == Long.MIN_VALUE ? null : clientEndSampleIndexExclusive,
                    "pcmBytes", body.length,
                    "vadBytes", vadBytes.length,
                    "rmsBytes", rmsBytes.length));
            Optional<TranscriptionResult> result = audioProcessor.acceptPcm16LeWithVadDetailed(body, vadBytes, rmsBytes);
            if (isClosed()) {
                return;
            }
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
        AudioDiagnostics.log("playback-report", diagnosticsContext, Json.fields(
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
        if (playbackEvent.recognized()) {
            rememberRecognizedChunk(playbackEvent.assistantTurnId(), playbackEvent.chunkId());
        }
    }

    public FaceEventResult handleFacePresence(FaceDB faceDB,FaceEventRequest request ) throws IOException {
        if ("person-left".equals(request.eventType())) {
            String trackId;
            synchronized (faceTrackLock) {
                trackId = this.trackmap.remove(request.trackId());
            }
            faceDB.finish(trackId);
            FaceEventResult result = FaceEventResult.left().withTrackId(request.trackId());
            handleFacePresence(result);
            return result;
        }
        String trackId;
        synchronized (faceTrackLock) {
            trackId = this.trackmap.get(request.trackId());
            if( trackId==null) {
                trackId = faceDB.createTrackId();
                this.trackmap.put(request.trackId(),trackId);
            }
        }
        FacePossibility registered = faceDB.register(trackId,request.descriptor(), request.imageDataUrl());
        FacePossibility.PersonPossibility nearest = registered.nearest();
        FaceEventResult result;
        if (nearest == null) {
            result = new FaceEventResult(
                    "accepted",
                    "",
                    "",
                    null,
                    false,
                    registered.sampleId,
                    request.presenceState(),
                    request.trackId());
        } else {
            result = new FaceEventResult(
                "accepted",
                nearest.personId,
                nearest.name,
                (double) nearest.distance,
                true,
                registered.sampleId,
                request.presenceState(),
                request.trackId());
        }
        handleFacePresence(result);
        return result;
    }

    public void handleFacePresence(FaceEventResult result) {
        boolean finalPersonLeft;
        synchronized (conversationLock) {
            if ("person-left".equals(result.presenceState())) {
                activeFaceTracks.remove(result.trackId());
            } else if ("person-entered".equals(result.presenceState())) {
                activeFaceTracks.put(result.trackId(), result);
            }
            finalPersonLeft = "person-left".equals(result.presenceState()) && activeFaceTracks.isEmpty();
        }
        ChatMessage faceEventMessage = null;
        if ("person-entered".equals(result.presenceState()) || finalPersonLeft) {
            String eventText = facePresenceHistoryText(result);
            faceEventMessage = new ChatMessage("system", eventText);
            synchronized (conversationLock) {
                conversationHistory.add(faceEventMessage);
                trimConversationHistory();
            }
        }
        sendToGroupIfOpen(ServerEvent.facePresence(result));
        if ("person-entered".equals(result.presenceState()) && faceEventMessage != null) {
            startAssistantReplyFromHistory(faceEventMessage);
        }
    }
    List<ChatMessage> conversationHistoryForTest() {
        synchronized (conversationLock) {
            return List.copyOf(conversationHistory);
        }
    }

    public static String facePresenceHistoryText(FaceEventResult result) {
        String faceEventMessage;
        if ("person-left".equals(result.presenceState())) {
            faceEventMessage = "人物認識通知\n認識結果: 不在\n相手の名前: 不在\ntrackId: 不在";
        } else {
            String trackId = "";
            trackId = result.trackId() == null || result.trackId().isBlank() ? "" : result.trackId();
            if( !result.known() || result.personName() == null || result.personName().isBlank() ) {
                faceEventMessage = "人物認識通知\n認識結果: 名前不明\n相手の名前: 不明\ntrackId: " + trackId;
                faceEventMessage = "だれか他の人が居ます(trackId:"+trackId+")。挨拶をしてお名前を聞いてみましょう。名前がわかったらツールをコール";
            } else {
                faceEventMessage = "人物認識通知\n認識結果: 登録済み\n相手の名前: "+result.personName()+"\ntrackId: "+result.trackId();
                faceEventMessage = "\"ユーザ名 "+result.personName()+"(trackId:"+result.trackId()+")と出会いました。友人として挨拶から\"";
            }
        }
        return faceEventMessage;
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
        AudioDiagnostics.log("audio-control-send", diagnosticsContext, Json.fields(
                "action", "pause",
                "assistantTurnId", event == null ? null : sttWaitAssistantTurnId,
                "interruptionId", currentInterruptionId,
                "speechSequenceId", started.speechSequenceId(),
                "reason", "stt-wait"));
        sendToGroupIfOpen(event);
    }

    private void handleSpeechStateChange(SpeechStateChange change) {
        sendToGroupIfOpen(ServerEvent.speechState(
                change.previousState().name(),
                change.currentState().name(),
                change.speechSequenceId(),
                change.sampleIndex()));
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
        AudioDiagnostics.log("audio-control-send", diagnosticsContext, Json.fields(
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
        if (result.kind() == TranscriptionKind.PARTIAL) {
            if (!transcript.isBlank()) {
                String partialTranscript = rememberPartialTranscript(result.speechSequenceId(), transcript);
                sendToGroupIfOpen(ServerEvent.transcriptPartial(result.speechSequenceId(), partialTranscript));
                cancelCurrentAssistantTurnForTranscript(result.speechSequenceId());
            }
            return;
        }

        String finalTranscript = takeFinalTranscript(result.speechSequenceId(), transcript);
        if (finalTranscript.isBlank()) {
            resumePlaybackForEmptyTranscript(result.speechSequenceId());
            return;
        }

        cancelCurrentAssistantTurnForTranscript(result.speechSequenceId());
        startAssistantReply(finalTranscript);
    }

    private String rememberPartialTranscript(long speechSequenceId, String transcript) {
        synchronized (partialTranscriptLock) {
            partialTranscripts.keySet().removeIf(id -> id < speechSequenceId);
            String current = partialTranscripts.getOrDefault(speechSequenceId, "");
            String merged = mergeTranscriptText(current, transcript);
            partialTranscripts.put(speechSequenceId, merged);
            return merged;
        }
    }

    private String takeFinalTranscript(long speechSequenceId, String transcript) {
        synchronized (partialTranscriptLock) {
            String partial = partialTranscripts.remove(speechSequenceId);
            return mergeTranscriptText(partial == null ? "" : partial, transcript);
        }
    }

    private static String mergeTranscriptText(String current, String next) {
        String left = current == null ? "" : current.trim();
        String right = next == null ? "" : next.trim();
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        int overlap = longestSuffixPrefixOverlap(left, right);
        return left + right.substring(overlap);
    }

    private static int longestSuffixPrefixOverlap(String left, String right) {
        int max = Math.min(left.length(), right.length());
        for (int length = max; length > 0; length--) {
            if (left.regionMatches(left.length() - length, right, 0, length)) {
                return length;
            }
        }
        return 0;
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
            AudioDiagnostics.log("audio-control-send", diagnosticsContext, Json.fields(
                    "action", "cancel",
                    "assistantTurnId", canceledTurnId,
                    "interruptionId", currentInterruptionId,
                    "speechSequenceId", speechSequenceId,
                    "reason", "user-transcript"));
        }
        if (taskToCancel != null) {
            taskToCancel.cancel(true);
        }
        if (canceledTurnId > 0) {
            synchronized (conversationLock) {
                pendingAssistantChunks.keySet().removeIf(key -> key.assistantTurnId() == canceledTurnId);
            }
        }
        if (event != null) {
            sendToGroupIfOpen(event);
        }
    }

    private void startAssistantReply(String transcript) {
        long assistantTurnId = beginAssistantTurn();
        AudioDiagnostics.log("assistant-turn-start", diagnosticsContext, Json.fields(
                "assistantTurnId", assistantTurnId,
                "transcriptChars", transcript.length(),
                "transcript", transcript));
        FutureTask<Void> task = new FutureTask<>(() -> {
            replyToTranscript(transcript, assistantTurnId);
            return null;
        });
        startAssistantTask(assistantTurnId, task);
    }

    private void startAssistantReplyFromHistory(ChatMessage triggerMessage) {
        long assistantTurnId = beginAssistantTurn();
        AudioDiagnostics.log("assistant-turn-start", diagnosticsContext, Json.fields(
                "assistantTurnId", assistantTurnId,
                "trigger", "conversation-history",
                "triggerChars", triggerMessage.text().length(),
                "triggerText", triggerMessage.text()));
        FutureTask<Void> task = new FutureTask<>(() -> {
            replyToConversationHistory(triggerMessage, assistantTurnId);
            return null;
        });
        startAssistantTask(assistantTurnId, task);
    }

    private void startAssistantTask(long assistantTurnId, FutureTask<Void> task) {
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
        StreamingTextChunker chunker = new StreamingTextChunker();
        ChatMessage userMessage = new ChatMessage("user", transcript);
        List<ChatMessage> requestMessages;
        synchronized (conversationLock) {
            rememberStalePendingUserMessages(assistantTurnId);
            pendingUserMessages.put(assistantTurnId, new PendingUserMessage(userMessage));
            requestMessages = requestMessages(userMessage);
        }
        replyWithMessages(assistantTurnId, chunker, userMessage, requestMessages, true, false);
    }

    private void replyToConversationHistory(ChatMessage triggerMessage, long assistantTurnId) {
        if (isClosed()) {
            return;
        }
        StreamingTextChunker chunker = new StreamingTextChunker();
        List<ChatMessage> requestMessages;
        synchronized (conversationLock) {
            requestMessages = List.copyOf(conversationHistory);
        }
        replyWithMessages(assistantTurnId, chunker, triggerMessage, requestMessages, false, true);
    }

    private void replyWithMessages(
            long assistantTurnId,
            StreamingTextChunker chunker,
            ChatMessage userMessage,
            List<ChatMessage> requestMessages,
            boolean publishUserMessage,
            boolean userMessageAlreadyInHistory) {
        try {
            if (publishUserMessage) {
                sendToGroupIfOpen(ServerEvent.userMessage(userMessage.text()));
            }
            sendToGroupIfOpen(ServerEvent.assistantState("LLM"));
            LanguageModelContext currentLanguageModel = languageModel;
            List<LLM.Message> llmMessages = new ArrayList<>(requestMessages.size() + 1);
            String currentSystemPrompt = currentLanguageModel.systemPrompt();
            if (!currentSystemPrompt.isBlank()) {
                llmMessages.add(new LLM.Message("system", currentSystemPrompt));
            }
            llmMessages.addAll(requestMessages.stream()
                    .map(message -> new LLM.Message(message.role(), message.text()))
                    .toList());
            currentLanguageModel.llm().call(llmMessages, List.of(new PersonTool(assistantTurnId)), delta -> {
                if (!isAssistantTurnActive(assistantTurnId)) {
                    return;
                }
                AudioDiagnostics.log("llm-message-delta", diagnosticsContext, Json.fields(
                        "assistantTurnId", assistantTurnId,
                        "deltaChars", delta.length()));
                speak(assistantTurnId, userMessage, userMessageAlreadyInHistory, chunker.append(delta));
            });
            speak(assistantTurnId, userMessage, userMessageAlreadyInHistory, chunker.finish());
            if (!isAssistantTurnActive(assistantTurnId)) {
                return;
            }
            AudioDiagnostics.log("assistant-turn-message-done", diagnosticsContext, Json.fields(
                    "assistantTurnId", assistantTurnId));
            sendToGroupIfOpen(ServerEvent.messageDone());
            finishAssistantTurn(assistantTurnId);
        } catch (LanguageModelException e) {
            rememberUserMessageWithoutAssistant(assistantTurnId);
            if (isAssistantTurnActive(assistantTurnId)) {
                sendToGroupIfOpen(ServerEvent.system("llm request failed: " + e.getMessage()));
            }
            finishAssistantTurn(assistantTurnId);
        } catch (TextToSpeechException e) {
            rememberUserMessageWithoutAssistant(assistantTurnId);
            if (isAssistantTurnActive(assistantTurnId)) {
                sendToGroupIfOpen(ServerEvent.system("tts request failed: " + e.getMessage()));
            }
            finishAssistantTurn(assistantTurnId);
        } finally {
            sendToGroupIfOpen(ServerEvent.assistantState("IDLE"));
        }
    }

    /** 1回の応答で LLM とシステムプロンプトの組み合わせを固定します。 */
    private record LanguageModelContext(LLM llm, String systemPrompt) {
        private LanguageModelContext {
            if (llm == null) {
                throw new IllegalArgumentException("llm must not be null");
            }
            systemPrompt = systemPrompt == null ? "" : systemPrompt.strip();
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

    private void rememberRecognizedChunk(long assistantTurnId, long chunkId) {
        PendingAssistantChunk chunk;
        synchronized (conversationLock) {
            chunk = pendingAssistantChunks.remove(new AssistantChunkKey(assistantTurnId, chunkId));
            if (chunk == null || chunk.text().isBlank()) {
                return;
            }
            rememberUserMessageForRecognizedAssistant(assistantTurnId, chunk);
            rememberAssistantChunk(assistantTurnId, chunk.text());
            trimConversationHistory();
        }
        AudioDiagnostics.log("assistant-chunk-recognized", diagnosticsContext, Json.fields(
                "assistantTurnId", assistantTurnId,
                "chunkId", chunkId,
                "textChars", chunk.text().length()));
    }

    private void rememberUserMessageForRecognizedAssistant(long assistantTurnId, PendingAssistantChunk chunk) {
        if (chunk.userMessageAlreadyInHistory() || rememberedUserAssistantTurnIds.contains(assistantTurnId)) {
            return;
        }
        PendingUserMessage pendingUserMessage = pendingUserMessages.remove(assistantTurnId);
        ChatMessage userMessage = pendingUserMessage == null ? chunk.userMessage() : pendingUserMessage.userMessage();
        conversationHistory.add(userMessage);
        rememberedUserAssistantTurnIds.add(assistantTurnId);
    }

    private void rememberUserMessageWithoutAssistant(long assistantTurnId) {
        synchronized (conversationLock) {
            PendingUserMessage pendingUserMessage = pendingUserMessages.remove(assistantTurnId);
            if (pendingUserMessage == null || rememberedUserAssistantTurnIds.contains(assistantTurnId)) {
                return;
            }
            conversationHistory.add(pendingUserMessage.userMessage());
            rememberedUserAssistantTurnIds.add(assistantTurnId);
            trimConversationHistory();
        }
    }

    private void rememberStalePendingUserMessages(long currentAssistantTurnId) {
        List<Long> staleAssistantTurnIds = pendingUserMessages.keySet().stream()
                .filter(assistantTurnId -> assistantTurnId < currentAssistantTurnId)
                .toList();
        for (long assistantTurnId : staleAssistantTurnIds) {
            PendingUserMessage pendingUserMessage = pendingUserMessages.remove(assistantTurnId);
            if (pendingUserMessage != null && !rememberedUserAssistantTurnIds.contains(assistantTurnId)) {
                pendingAssistantChunks.keySet().removeIf(key -> key.assistantTurnId() == assistantTurnId);
                conversationHistory.add(pendingUserMessage.userMessage());
                rememberedUserAssistantTurnIds.add(assistantTurnId);
            }
        }
        trimConversationHistory();
    }

    private void rememberAssistantChunk(long assistantTurnId, String text) {
        Integer historyIndex = rememberedAssistantMessageIndexes.get(assistantTurnId);
        if (historyIndex == null || historyIndex < 0 || historyIndex >= conversationHistory.size()) {
            conversationHistory.add(new ChatMessage("assistant", text));
            rememberedAssistantMessageIndexes.put(assistantTurnId, conversationHistory.size() - 1);
            return;
        }
        ChatMessage currentMessage = conversationHistory.get(historyIndex);
        if (!"assistant".equals(currentMessage.role())) {
            conversationHistory.add(new ChatMessage("assistant", text));
            rememberedAssistantMessageIndexes.put(assistantTurnId, conversationHistory.size() - 1);
            return;
        }
        conversationHistory.set(historyIndex, new ChatMessage("assistant", currentMessage.text() + text));
    }

    private void trimConversationHistory() {
        int removedCount = 0;
        while (conversationHistory.size() > MAX_HISTORY_MESSAGES) {
            conversationHistory.removeFirst();
            removedCount++;
        }
        if (removedCount == 0) {
            return;
        }
        int removed = removedCount;
        rememberedAssistantMessageIndexes.entrySet().removeIf(entry -> entry.getValue() < removed);
        rememberedAssistantMessageIndexes.replaceAll((assistantTurnId, index) -> index - removed);
    }

    private void speak(
            long assistantTurnId,
            ChatMessage userMessage,
            boolean userMessageAlreadyInHistory,
            Iterable<String> chunks) {
        for (String chunk : chunks) {
            if (!isAssistantTurnActive(assistantTurnId)) {
                return;
            }
            long chunkId = nextAssistantChunkId();
            List<AudioDelta> audioDeltas = new ArrayList<>();
            AudioDiagnostics.log("tts-chunk-start", diagnosticsContext, Json.fields(
                    "assistantTurnId", assistantTurnId,
                    "chunkId", chunkId,
                    "textChars", chunk.length(),
                    "text", chunk));
            sendToGroupIfOpen(ServerEvent.assistantState("TTS"));
            if (StreamingTextChunker.hasSpeechText(chunk)) {
                textToSpeech.synthesizeStreaming(chunk, audio -> {
                    if (isAssistantTurnActive(assistantTurnId)) {
                        audioDeltas.add(audio);
                        AudioDiagnostics.log("tts-audio-delta", diagnosticsContext, Json.fields(
                                "assistantTurnId", assistantTurnId,
                                "chunkId", chunkId,
                                "deltaIndex", audioDeltas.size(),
                                "base64Chars", audio.data().length(),
                                "format", audio.format(),
                                "sampleRate", audio.sampleRate()));
                    }
                });
            }
            if (!isAssistantTurnActive(assistantTurnId)) {
                return;
            }
            double durationSeconds = audioDurationSeconds(audioDeltas);
            synchronized (conversationLock) {
                pendingAssistantChunks.put(
                        new AssistantChunkKey(assistantTurnId, chunkId),
                        new PendingAssistantChunk(userMessage, userMessageAlreadyInHistory, chunk));
            }
            sendToGroupIfOpen(ServerEvent.assistantAudioChunk(
                    assistantTurnId,
                    chunkId,
                    chunk,
                    audioDeltas,
                    durationSeconds));
            AudioDiagnostics.log("tts-chunk-done", diagnosticsContext, Json.fields(
                    "assistantTurnId", assistantTurnId,
                    "chunkId", chunkId,
                    "audioDeltaCount", audioDeltas.size(),
                    "audioBase64Chars", audioDeltas.stream().mapToLong(audio -> audio.data().length()).sum(),
                    "audioDurationSeconds", durationSeconds));
            if (isAssistantTurnActive(assistantTurnId)) {
                sendToGroupIfOpen(ServerEvent.assistantState("LLM"));
            }
        }
    }

    private long nextAssistantChunkId() {
        synchronized (playbackControlLock) {
            return ++nextAssistantChunkId;
        }
    }

    private static double audioDurationSeconds(List<AudioDelta> audioDeltas) {
        double seconds = 0;
        for (AudioDelta audio : audioDeltas) {
            if ("pcm".equals(audio.format())) {
                int byteLength = java.util.Base64.getDecoder().decode(audio.data()).length;
                seconds += (byteLength / 2.0) / audio.sampleRate();
            }
        }
        return seconds;
    }

    private void sendAudioProcessingFailure(RuntimeException e) {
        AudioDiagnostics.log("audio-processing-error", diagnosticsContext, Json.fields(
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
        close("client-close");
    }

    void close(String reason) {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        cancelActiveWorkNow(reason);
    }

    /**
     * 停止操作で、STT、LLM、TTS、再生待ちの応答をまとめてキャンセルする。
     */
    public void cancelActiveWork(String reason) {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
        }
        cancelActiveWorkNow(reason);
    }

    private void cancelActiveWorkNow(String reason) {
        Future<?> taskToCancel;
        long canceledTurnId;
        synchronized (playbackControlLock) {
            canceledTurnId = currentAssistantTurnId;
            if (canceledTurnId > 0) {
                canceledAssistantTurnIds.add(canceledTurnId);
            }
            taskToCancel = activeAssistantTask;
            activeAssistantTask = null;
            assistantTurnActive = false;
            sttWaitActive = false;
            activePlaybackStartSampleIndex = Long.MIN_VALUE;
        }
        audioProcessor.cancelTranscriptions();
        synchronized (partialTranscriptLock) {
            partialTranscripts.clear();
        }
        synchronized (conversationLock) {
            pendingAssistantChunks.clear();
            pendingUserMessages.clear();
            rememberedUserAssistantTurnIds.clear();
            rememberedAssistantMessageIndexes.clear();
        }
        if (taskToCancel != null) {
            taskToCancel.cancel(true);
        }
        AudioDiagnostics.log("client-work-cancel", diagnosticsContext, Json.fields(
                "reason", reason,
                "assistantTurnId", canceledTurnId > 0 ? canceledTurnId : null));
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

    public record PlaybackEvent(
            long assistantTurnId,
            long chunkId,
            String state,
            boolean recognized,
            double playedSeconds,
            double durationSeconds,
            long clientMicSampleIndex) {
        public PlaybackEvent(long assistantTurnId, String state, long clientMicSampleIndex) {
            this(assistantTurnId, 0, state, false, 0, 0, clientMicSampleIndex);
        }

        public PlaybackEvent {
            if (state == null || state.isBlank()) {
                throw new IllegalArgumentException("playback state is required");
            }
            state = state.trim().toLowerCase();
        }
    }

    private record AssistantChunkKey(long assistantTurnId, long chunkId) {
    }

    private record PendingUserMessage(ChatMessage userMessage) {
    }

    private record PendingAssistantChunk(ChatMessage userMessage, boolean userMessageAlreadyInHistory, String text) {
    }

    public class PersonTool extends LLM.Tool {

        private final long assistantTurnId;

        public PersonTool(long assistantTurnId) {
            super(
                "assign_face_name",
                "trackIdに人物名を登録します。ユーザーが自分の名前を名乗ったときに呼び出します。"
            );
            this.assistantTurnId = assistantTurnId;
        }

        @Override
        public FunctionTool.Parameters parameters() {
            return FunctionTool.Parameters.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                        "trackId", Map.of(
                                "type", "string",
                                "description", "人物映像の追跡ID track-999999"),
                        "name", Map.of(
                            "type", "string",
                            "description", "覚える名前。禁止:不明,unknown"
                        )
                )))
                .putAdditionalProperty("required", JsonValue.from(List.of("trackId", "name")))
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build();
        }

        @Override
        public String exec(ResponseFunctionToolCall toolCall, String arguments ) {
            AudioDiagnostics.log("llm-tool-call", diagnosticsContext, Json.fields(
                    "assistantTurnId", assistantTurnId,
                    "toolName", toolCall.name(),
                    "callId", toolCall.callId()));

            if (!isAssistantTurnActive(assistantTurnId)) {
                AudioDiagnostics.log("llm-tool-call-ignored", diagnosticsContext, Json.fields(
                        "assistantTurnId", assistantTurnId,
                        "toolName", toolCall.name(),
                        "reason", "assistant-turn-inactive"));
                return Json.object(Json.fields(
                        "status", "failed",
                        "error", "assistant turn is no longer active"));
            }

            String trackId = JsonFields.stringOrDefault(arguments, "trackId", "").trim();
            String name = JsonFields.stringOrDefault(arguments, "name", "").trim();
            if (trackId.isBlank() || name.isBlank() || "unknown".equalsIgnoreCase(name) || "不明".equals(name)) {
                AudioDiagnostics.log("llm-tool-call-ignored", diagnosticsContext, Json.fields(
                        "assistantTurnId", assistantTurnId,
                        "toolName", toolCall.name(),
                        "reason", "invalid-argument",
                        "trackId", trackId,
                        "name", name));
                return Json.object(Json.fields(
                        "status", "failed",
                        "error", "ユーザに名前を聞いてね。",
                        "trackId", trackId,
                        "name", name));
            }
            try {
                chatGroup.assignFaceName(trackId, name);
            } catch (IllegalArgumentException | IllegalStateException e) {
                AudioDiagnostics.log("llm-tool-call-ignored", diagnosticsContext, Json.fields(
                    "assistantTurnId", assistantTurnId,
                    "toolName", toolCall.name(),
                    "reason", "assign-failed",
                    "trackId", trackId,
                    "name", name,
                    "error", e.getMessage()));
                return Json.object(Json.fields(
                        "status", "failed",
                        "error", e.getMessage(),
                        "trackId", trackId,
                        "name", name));
            }
            AudioDiagnostics.log("face-name-assigned", diagnosticsContext, Json.fields(
                    "assistantTurnId", assistantTurnId,
                    "trackId", trackId,
                    "name", name));
            return Json.object(Json.fields(
                    "status", "ok",
                    "trackId", trackId,
                    "name", name,
                    "message", "覚えました。’"+name+"'さんとの会話を進めてください。"
            ));
        }
    }

}
