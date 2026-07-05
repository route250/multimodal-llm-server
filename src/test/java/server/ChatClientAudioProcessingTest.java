package server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import audio.AudioBuffer;
import audio.AudioDiagnostics;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import llm.ChatMessage;
import llm.LanguageModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import stt.SpeechToText;
import stt.Transcription;
import tts.AudioDelta;
import tts.TextToSpeech;
import vad.VadAudioProcessor;
import vad.VadAudioProcessor.SpeechState;
import vad.VadAudioProcessor.SpeechStateChange;

@Timeout(5)
class ChatClientAudioProcessingTest {
    private static final String PCM16LE = "audio/pcm; rate=16000; channels=1; format=s16le";
    private static final String PCM_VAD = "audio/pcm-vad; rate=16000; channels=1; format=s16le; vad-frame-samples=256";

    @Test
    void audioRequestReturnsBeforeAudioProcessorCompletes() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            BlockingAudioProcessor processor = new BlockingAudioProcessor();
            ChatClient client = new ChatClient("client-1", group, processor);

            client.handle(audioRequest(new byte[]{0, 0}));

            assertTrue(processor.started.await(1, TimeUnit.SECONDS));
            assertFalse(processor.finished.await(50, TimeUnit.MILLISECONDS));

            processor.release();
            assertTrue(processor.finished.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void audioChunksAreProcessedInRequestOrder() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            RecordingAudioProcessor processor = new RecordingAudioProcessor();
            ChatClient client = new ChatClient("client-1", group, processor);

            client.handle(audioRequest(new byte[]{1, 0}));
            client.handle(audioRequest(new byte[]{2, 0}));
            client.handle(audioRequest(new byte[]{3, 0}));

            assertTrue(processor.awaitCalls(3));
            assertEquals(List.of(1, 2, 3), processor.firstBytes());
        }
    }

    @Test
    void frontendEnablesBrowserEchoCancellationAndLocalTenVadPause() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/html/index.html"));

        assertTrue(html.contains("echoCancellation: true"));
        assertTrue(html.contains("noiseSuppression: true"));
        assertTrue(html.contains("localPauseVadThreshold = 50"));
        assertTrue(html.contains("localPauseConsecutiveVadFrames = 3"));
        assertTrue(html.contains("localResumeVadThreshold = 35"));
        assertTrue(html.contains("localResumeSilenceVadFrames = 8"));
        assertTrue(html.contains("X-Client-Mic-Start-Sample"));
        assertTrue(html.contains("/chat/playback"));
        assertTrue(html.contains("import createVADModule from \"/tenvad/ten_vad.js\""));
        assertTrue(html.contains("audio/pcm-vad; rate=16000; channels=1; format=s16le; vad-frame-samples=256"));
        assertTrue(html.contains("tenVadHopSamples = 256"));
        assertTrue(html.contains("const playbackFlag = (currentPlayback || pausedPlayback) ? 0x80 : 0"));
        assertTrue(html.contains("vadBytes[frameIndex] = (value & 0x7f) | playbackFlag"));
        assertTrue(html.contains("const speechValue = vadValue & 0x7f"));
        assertTrue(html.contains("analyzeLocalTenVad(vadBytes)"));
    }

    @Test
    void audioPcmWithoutBrowserVadIsRejected() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient client = new ChatClient("client-1", group, new RecordingBodyAudioProcessor());

            HttpRequestException error = assertThrows(
                    HttpRequestException.class,
                    () -> client.handleAudio(ChatRequest.from(PCM16LE, new byte[]{0, 0}), 0, 1));

            assertEquals(400, error.status());
        }
    }

    @Test
    void activePlaybackRangeIsKeptBeforeAudioProcessorReceivesPcm() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            RecordingBodyAudioProcessor processor = new RecordingBodyAudioProcessor();
            ChatClient client = new ChatClient("client-1", group, processor);

            client.handlePlayback(new ChatClient.PlaybackEvent(1, "start", 0));
            client.handleAudio(audioRequest(new byte[]{1, 0, 2, 0, 3, 0}), 0, 3);

            assertTrue(processor.awaitCalls(1));
            assertEquals(List.of(1, 0, 2, 0, 3, 0), processor.receivedBytes());
        }
    }

    @Test
    void browserVadBytesArePassedToAudioProcessor() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            RecordingBodyAudioProcessor processor = new RecordingBodyAudioProcessor();
            ChatClient client = new ChatClient("client-1", group, processor);

            client.handle(audioRequest(new byte[VadAudioProcessor.VAD_FRAME_SAMPLES * Short.BYTES], new byte[] {(byte) 0x80}));

            assertTrue(processor.awaitCalls(1));
            assertEquals(List.of(0x80), processor.receivedVadBytes());
        }
    }

    @Test
    void audioChunkProcessingIsWrittenToDiagnosticsLog() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            RecordingBodyAudioProcessor processor = new RecordingBodyAudioProcessor();
            String sessionId = "processor-log-" + System.nanoTime();
            ChatClient client = new ChatClient(sessionId, group, processor);

            client.handleAudio(audioRequest(new byte[]{1, 0, 2, 0}), 10, 12);

            assertTrue(processor.awaitCalls(1));
            String log = Files.readString(AudioDiagnostics.logFile());
            assertTrue(log.contains("\"event\":\"audio-chunk-process\""));
            assertTrue(log.contains("\"groupId\":\"group-test\""));
            assertTrue(log.contains("\"sessionId\":\"" + sessionId + "\""));
            assertTrue(log.contains("\"pcmBytes\":4"));
            assertTrue(log.contains("\"startSampleIndex\":10"));
            assertTrue(log.contains("\"endSampleIndexExclusive\":12"));
        }
    }

    @Test
    void transcriptIsPublishedAfterAsyncAudioProcessing() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient client = group.join("client-1");
            ChatClient processorClient = new ChatClient(
                    "processor",
                    group,
                    new TranscriptAudioProcessor("hello"),
                    text -> "llm response: " + text);
            drainJoinEvents(client);

            processorClient.handle(audioRequest(new byte[]{0, 0}));

            ServerEvent delta = client.events().poll(1, TimeUnit.SECONDS);
            ServerEvent done = client.events().poll(1, TimeUnit.SECONDS);
            assertNotNull(delta);
            assertNotNull(done);
            assertEquals("message-delta", delta.type());
            assertEquals("llm response: hello", delta.message());
            assertEquals("message-done", done.type());
        }
    }

    @Test
    void llmDeltasAreSynthesizedAndPublishedAsAudioDeltas() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("client-1");
            RecordingTextToSpeech textToSpeech = new RecordingTextToSpeech();
            ChatClient processorClient = new ChatClient(
                    "processor",
                    group,
                    new TranscriptAudioProcessor("hello"),
                    new StreamingLanguageModel("こんにちは。", "次です"),
                    textToSpeech);
            drainJoinEvents(listener);

            processorClient.handle(audioRequest(new byte[]{0, 0}));

            ServerEvent text1 = listener.events().poll(1, TimeUnit.SECONDS);
            ServerEvent audio1 = listener.events().poll(1, TimeUnit.SECONDS);
            ServerEvent text2 = listener.events().poll(1, TimeUnit.SECONDS);
            ServerEvent audio2 = listener.events().poll(1, TimeUnit.SECONDS);
            ServerEvent done = listener.events().poll(1, TimeUnit.SECONDS);
            assertNotNull(text1);
            assertNotNull(audio1);
            assertNotNull(text2);
            assertNotNull(audio2);
            assertNotNull(done);
            assertEquals("message-delta", text1.type());
            assertEquals("こんにちは。", text1.message());
            assertEquals("audio-delta", audio1.type());
            assertTrue(audio1.message().contains("\"data\":\"AAAA\""));
            assertTrue(audio1.message().contains("\"sampleRate\":24000"));
            assertEquals("message-delta", text2.type());
            assertEquals("次です", text2.message());
            assertEquals("audio-delta", audio2.type());
            assertEquals("message-done", done.type());
            assertEquals(List.of("こんにちは。", "次です"), textToSpeech.texts);
        }
    }

    @Test
    void textRequestStartsLlmAndTtsTurn() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("client-1");
            RecordingTextToSpeech textToSpeech = new RecordingTextToSpeech();
            ChatClient processorClient = new ChatClient(
                    "processor",
                    group,
                    new TranscriptAudioProcessor("unused"),
                    new StreamingLanguageModel("テキスト応答。"),
                    textToSpeech);
            drainJoinEvents(listener);

            processorClient.handle(ChatRequest.from("text/plain; charset=utf-8", "こんにちは".getBytes()));

            ServerEvent text = listener.events().poll(1, TimeUnit.SECONDS);
            ServerEvent audio = listener.events().poll(1, TimeUnit.SECONDS);
            ServerEvent done = listener.events().poll(1, TimeUnit.SECONDS);
            assertNotNull(text);
            assertNotNull(audio);
            assertNotNull(done);
            assertEquals("message-delta", text.type());
            assertEquals("テキスト応答。", text.message());
            assertEquals("audio-delta", audio.type());
            assertEquals("message-done", done.type());
            assertEquals(List.of("テキスト応答。"), textToSpeech.texts);
        }
    }

    @Test
    void vadDetectionPublishesAudioPauseControl() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("client-1");
            StateChangeAudioProcessor processor = new StateChangeAudioProcessor(
                    VadAudioProcessor.SpeechState.UNDETECTED,
                    VadAudioProcessor.SpeechState.DETECTED);
            ChatClient processorClient = new ChatClient("processor", group, processor);
            drainJoinEvents(listener);

            processorClient.handle(audioRequest(new byte[]{0, 0}));

            ServerEvent pause = listener.events().poll(1, TimeUnit.SECONDS);
            assertNotNull(pause);
            assertEquals("audio-control", pause.type());
            assertTrue(pause.message().contains("\"action\":\"pause\""));
            assertTrue(pause.message().contains("\"reason\":\"vad\""));
        }
    }

    @Test
    void transcriptCancelsActiveAssistantAudioAndDropsLateTtsDelta() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("client-1");
            BlockingTextToSpeech textToSpeech = new BlockingTextToSpeech();
            ChatClient processorClient = new ChatClient(
                    "processor",
                    group,
                    new TranscriptAudioProcessor("interrupt"),
                    new StreamingLanguageModel("長い応答。"),
                    textToSpeech);
            drainJoinEvents(listener);

            processorClient.handle(ChatRequest.from("text/plain; charset=utf-8", "開始".getBytes()));
            ServerEvent text = listener.events().poll(1, TimeUnit.SECONDS);
            assertNotNull(text);
            assertEquals("message-delta", text.type());
            assertTrue(textToSpeech.started.await(1, TimeUnit.SECONDS));

            processorClient.handle(audioRequest(new byte[]{0, 0}));
            ServerEvent cancel = listener.events().poll(1, TimeUnit.SECONDS);
            assertNotNull(cancel);
            assertEquals("audio-control", cancel.type());
            assertTrue(cancel.message().contains("\"action\":\"cancel\""));

            textToSpeech.release();
            Thread.sleep(100);
            assertFalse(containsMessageFragment(listener, "\"assistantTurnId\":1"));
        }
    }

    @Test
    void vadDetectionDuringAssistantTurnDoesNotSkipSpeechStartForStt() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("client-1");
            BlockingTextToSpeech textToSpeech = new BlockingTextToSpeech();
            StateChangeAudioProcessor processor = new StateChangeAudioProcessor(
                    VadAudioProcessor.SpeechState.UNDETECTED,
                    VadAudioProcessor.SpeechState.DETECTED);
            ChatClient processorClient = new ChatClient(
                    "processor",
                    group,
                    processor,
                    new StreamingLanguageModel("応答。"),
                    textToSpeech);
            drainJoinEvents(listener);

            processorClient.handle(ChatRequest.from("text/plain; charset=utf-8", "開始".getBytes()));
            assertNotNull(listener.events().poll(1, TimeUnit.SECONDS));
            assertTrue(textToSpeech.started.await(1, TimeUnit.SECONDS));

            processorClient.handle(audioRequest(new byte[]{0, 0}));
            assertNotNull(listener.events().poll(1, TimeUnit.SECONDS));

            assertEquals(0, processor.ignoredBeforeSampleIndex);
            textToSpeech.release();
        }
    }

    @Test
    void textTurnsSendConversationHistoryToLanguageModel() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("client-1");
            RecordingConversationLanguageModel languageModel = new RecordingConversationLanguageModel("太郎です", "太郎です");
            ChatClient processorClient = new ChatClient(
                    "processor",
                    group,
                    new TranscriptAudioProcessor("unused"),
                    languageModel,
                    TextToSpeech.disabled());
            drainJoinEvents(listener);

            processorClient.handle(ChatRequest.from("text/plain; charset=utf-8", "私の名前は太郎です".getBytes()));
            assertNotNull(listener.events().poll(1, TimeUnit.SECONDS));
            assertNotNull(listener.events().poll(1, TimeUnit.SECONDS));
            processorClient.handle(ChatRequest.from("text/plain; charset=utf-8", "私の名前は何ですか".getBytes()));
            assertNotNull(listener.events().poll(1, TimeUnit.SECONDS));
            assertNotNull(listener.events().poll(1, TimeUnit.SECONDS));

            assertEquals(2, languageModel.calls.size());
            assertEquals(List.of("user:私の名前は太郎です"), languageModel.calls.get(0));
            assertEquals(List.of(
                    "user:私の名前は太郎です",
                    "assistant:太郎です",
                    "user:私の名前は何ですか"), languageModel.calls.get(1));
        }
    }

    @Test
    void asyncAudioFailureIsPublishedAndNextChunkContinues() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient client = group.join("client-1");
            FailingThenTranscriptAudioProcessor processor = new FailingThenTranscriptAudioProcessor();
            ChatClient processorClient = new ChatClient("processor", group, processor, text -> "llm response: " + text);
            drainJoinEvents(client);

            processorClient.handle(audioRequest(new byte[]{0, 0}));
            processorClient.handle(audioRequest(new byte[]{1, 0}));

            ServerEvent failure = client.events().poll(1, TimeUnit.SECONDS);
            ServerEvent delta = client.events().poll(1, TimeUnit.SECONDS);
            ServerEvent done = client.events().poll(1, TimeUnit.SECONDS);
            assertNotNull(failure);
            assertNotNull(delta);
            assertNotNull(done);
            assertEquals("system", failure.type());
            assertEquals("audio processing failed: boom", failure.message());
            assertEquals("message-delta", delta.type());
            assertEquals("llm response: after failure", delta.message());
            assertEquals("message-done", done.type());
        }
    }

    @Test
    void closedClientDoesNotPublishPendingAudioResult() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("listener");
            BlockingAudioProcessor processor = new BlockingAudioProcessor("late result");
            CountingLanguageModel languageModel = new CountingLanguageModel();
            ChatClient processorClient = group.join("processor", processor, languageModel);

            processorClient.handle(audioRequest(new byte[]{0, 0}));
            assertTrue(processor.started.await(1, TimeUnit.SECONDS));

            group.leave(processorClient);
            processor.release();

            assertTrue(processor.finished.await(1, TimeUnit.SECONDS));
            drainJoinEvents(listener);
            assertFalse(containsMessage(listener, "late result"));
            assertEquals(0, languageModel.calls);
        }
    }

    private static ChatRequest audioRequest(byte[] body) {
        byte[] vadBytes = new byte[body.length / (VadAudioProcessor.VAD_FRAME_SAMPLES * Short.BYTES)];
        return audioRequest(body, vadBytes);
    }

    private static ChatRequest audioRequest(byte[] body, byte[] vadBytes) {
        return ChatRequest.from(PCM_VAD, pcmVadBody(body, vadBytes));
    }

    private static byte[] pcmVadBody(byte[] pcm, byte[] vadBytes) {
        ByteBuffer buffer = ByteBuffer
                .allocate(32 + pcm.length + vadBytes.length)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 'M');
        buffer.put((byte) 'V');
        buffer.put((byte) 'A');
        buffer.put((byte) 'D');
        buffer.putShort((short) 1);
        buffer.putShort((short) 0);
        buffer.putInt(16_000);
        buffer.putShort((short) 1);
        buffer.putShort((short) 1);
        buffer.putInt(pcm.length / Short.BYTES);
        buffer.putInt(VadAudioProcessor.VAD_FRAME_SAMPLES);
        buffer.putInt(vadBytes.length);
        buffer.putInt(0);
        buffer.put(pcm);
        buffer.put(vadBytes);
        return buffer.array();
    }

    private static void drainJoinEvents(ChatClient client) {
        client.events().removeIf(event -> event.message().contains(" joined "));
    }

    private static boolean containsMessage(ChatClient client, String message) {
        return client.events().stream().anyMatch(event -> message.equals(event.message()));
    }

    private static boolean containsType(ChatClient client, String type) {
        return client.events().stream().anyMatch(event -> type.equals(event.type()));
    }

    private static boolean containsMessageFragment(ChatClient client, String fragment) {
        return client.events().stream().anyMatch(event -> event.message().contains(fragment));
    }

    private static SpeechToText emptySpeechToText() {
        return new SpeechToText() {
            @Override
            public Transcription transcribe(
                    AudioBuffer audioBuffer,
                    long startSampleIndex,
                    long endSampleIndexExclusive,
                    String prompt) {
                return Transcription.empty();
            }
        };
    }

    private static class RecordingAudioProcessor extends VadAudioProcessor {
        private final List<Integer> firstBytes = new ArrayList<>();
        private final CountDownLatch calls = new CountDownLatch(3);

        RecordingAudioProcessor() {
            super(samples -> true, emptySpeechToText(), Runnable::run);
        }

        @Override
        public synchronized Optional<Transcription> acceptPcm16Le(byte[] bytes) {
            return acceptPcm16LeWithVadDetailed(bytes, new byte[bytes.length / (VadAudioProcessor.VAD_FRAME_SAMPLES * Short.BYTES)]).map(VadAudioProcessor.TranscriptionResult::transcription);
        }

        @Override
        public synchronized Optional<VadAudioProcessor.TranscriptionResult> acceptPcm16LeWithVadDetailed(byte[] bytes, byte[] vadBytes) {
            firstBytes.add(bytes[0] & 0xff);
            calls.countDown();
            return Optional.empty();
        }

        boolean awaitCalls(int expected) throws InterruptedException {
            return calls.await(1, TimeUnit.SECONDS) && firstBytes.size() == expected;
        }

        List<Integer> firstBytes() {
            return List.copyOf(firstBytes);
        }
    }

    private static class RecordingBodyAudioProcessor extends VadAudioProcessor {
        private final CountDownLatch calls = new CountDownLatch(1);
        private byte[] receivedBytes = new byte[0];
        private byte[] receivedVadBytes = new byte[0];

        RecordingBodyAudioProcessor() {
            super(samples -> true, emptySpeechToText(), Runnable::run);
        }

        @Override
        public synchronized Optional<VadAudioProcessor.TranscriptionResult> acceptPcm16LeWithVadDetailed(byte[] bytes, byte[] vadBytes) {
            receivedBytes = bytes.clone();
            receivedVadBytes = vadBytes.clone();
            calls.countDown();
            return Optional.empty();
        }

        boolean awaitCalls(int expected) throws InterruptedException {
            return calls.await(1, TimeUnit.SECONDS) && expected == 1;
        }

        List<Integer> receivedBytes() {
            return Arrays.stream(toUnsignedInts(receivedBytes)).boxed().toList();
        }

        List<Integer> receivedVadBytes() {
            return Arrays.stream(toUnsignedInts(receivedVadBytes)).boxed().toList();
        }

        private static int[] toUnsignedInts(byte[] bytes) {
            int[] values = new int[bytes.length];
            for (int i = 0; i < bytes.length; i++) {
                values[i] = bytes[i] & 0xff;
            }
            return values;
        }
    }

    private static class StateChangeAudioProcessor extends VadAudioProcessor {
        private final SpeechState previousState;
        private final SpeechState currentState;
        private long ignoredBeforeSampleIndex;
        private java.util.function.Consumer<SpeechStateChange> listener = change -> {
        };

        StateChangeAudioProcessor(SpeechState previousState, SpeechState currentState) {
            super(samples -> true, emptySpeechToText(), Runnable::run);
            this.previousState = previousState;
            this.currentState = currentState;
        }

        @Override
        public synchronized void setSpeechStateListener(java.util.function.Consumer<SpeechStateChange> listener) {
            this.listener = listener == null ? change -> {
            } : listener;
        }

        @Override
        public synchronized Optional<VadAudioProcessor.TranscriptionResult> acceptPcm16LeWithVadDetailed(byte[] bytes, byte[] vadBytes) {
            listener.accept(new SpeechStateChange(previousState, currentState, 1, 0));
            return Optional.empty();
        }

        @Override
        public synchronized void ignoreTranscriptionBefore(long sampleIndex) {
            ignoredBeforeSampleIndex = sampleIndex;
        }
    }

    private static class TranscriptAudioProcessor extends VadAudioProcessor {
        private final String transcript;

        TranscriptAudioProcessor(String transcript) {
            super(samples -> true, emptySpeechToText(), Runnable::run);
            this.transcript = transcript;
        }

        @Override
        public Optional<Transcription> acceptPcm16Le(byte[] bytes) {
            return acceptPcm16LeWithVadDetailed(bytes, new byte[bytes.length / (VadAudioProcessor.VAD_FRAME_SAMPLES * Short.BYTES)]).map(VadAudioProcessor.TranscriptionResult::transcription);
        }

        @Override
        public Optional<VadAudioProcessor.TranscriptionResult> acceptPcm16LeWithVadDetailed(byte[] bytes, byte[] vadBytes) {
            Transcription transcription = Transcription.singleSegment(transcript, 0, 16_000);
            return Optional.of(new VadAudioProcessor.TranscriptionResult(
                    1,
                    0,
                    0,
                    VadAudioProcessor.TranscriptionKind.FINAL,
                    transcription));
        }
    }

    private static class FailingThenTranscriptAudioProcessor extends VadAudioProcessor {
        private int calls;

        FailingThenTranscriptAudioProcessor() {
            super(samples -> true, emptySpeechToText(), Runnable::run);
        }

        @Override
        public synchronized Optional<Transcription> acceptPcm16Le(byte[] bytes) {
            return acceptPcm16LeWithVadDetailed(bytes, new byte[bytes.length / (VadAudioProcessor.VAD_FRAME_SAMPLES * Short.BYTES)]).map(VadAudioProcessor.TranscriptionResult::transcription);
        }

        @Override
        public synchronized Optional<VadAudioProcessor.TranscriptionResult> acceptPcm16LeWithVadDetailed(byte[] bytes, byte[] vadBytes) {
            calls++;
            if (calls == 1) {
                throw new IllegalArgumentException("boom");
            }
            return Optional.of(new VadAudioProcessor.TranscriptionResult(
                    1,
                    0,
                    0,
                    VadAudioProcessor.TranscriptionKind.FINAL,
                    Transcription.singleSegment("after failure", 0, 16_000)));
        }
    }

    private static class BlockingAudioProcessor extends VadAudioProcessor {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private final String transcript;

        BlockingAudioProcessor() {
            this(null);
        }

        BlockingAudioProcessor(String transcript) {
            super(samples -> true, emptySpeechToText(), Runnable::run);
            this.transcript = transcript;
        }

        @Override
        public Optional<Transcription> acceptPcm16Le(byte[] bytes) {
            return acceptPcm16LeWithVadDetailed(bytes, new byte[bytes.length / (VadAudioProcessor.VAD_FRAME_SAMPLES * Short.BYTES)]).map(VadAudioProcessor.TranscriptionResult::transcription);
        }

        @Override
        public Optional<VadAudioProcessor.TranscriptionResult> acceptPcm16LeWithVadDetailed(byte[] bytes, byte[] vadBytes) {
            started.countDown();
            try {
                release.await(1, TimeUnit.SECONDS);
                return Optional.ofNullable(transcript)
                        .map(value -> new VadAudioProcessor.TranscriptionResult(
                                1,
                                0,
                                0,
                                VadAudioProcessor.TranscriptionKind.FINAL,
                                Transcription.singleSegment(value, 0, 16_000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } finally {
                finished.countDown();
            }
        }

        void release() {
            release.countDown();
        }
    }

    private static class CountingLanguageModel implements LanguageModel {
        private int calls;

        @Override
        public String respond(String userText) {
            calls++;
            return userText;
        }
    }

    private static class StreamingLanguageModel implements LanguageModel {
        private final List<String> deltas;

        StreamingLanguageModel(String... deltas) {
            this.deltas = List.of(deltas);
        }

        @Override
        public String respond(String userText) {
            return String.join("", deltas);
        }

        @Override
        public void respondStreaming(String userText, java.util.function.Consumer<String> onDelta) {
            deltas.forEach(onDelta);
        }
    }

    private static class RecordingConversationLanguageModel implements LanguageModel {
        private final List<String> responses;
        private final List<List<String>> calls = new ArrayList<>();
        private int nextResponse;

        RecordingConversationLanguageModel(String... responses) {
            this.responses = List.of(responses);
        }

        @Override
        public String respond(String userText) {
            return responses.get(nextResponse++);
        }

        @Override
        public void respondStreaming(List<ChatMessage> messages, java.util.function.Consumer<String> onDelta) {
            calls.add(messages.stream()
                    .map(message -> message.role() + ":" + message.text())
                    .toList());
            onDelta.accept(responses.get(nextResponse++));
        }
    }

    private static class RecordingTextToSpeech implements TextToSpeech {
        private final List<String> texts = new ArrayList<>();

        @Override
        public void synthesizeStreaming(String text, java.util.function.Consumer<AudioDelta> onDelta) {
            texts.add(text);
            onDelta.accept(new AudioDelta("AAAA", "pcm", 24000));
        }
    }

    private static class BlockingTextToSpeech implements TextToSpeech {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void synthesizeStreaming(String text, java.util.function.Consumer<AudioDelta> onDelta) {
            started.countDown();
            try {
                release.await(1, TimeUnit.SECONDS);
                onDelta.accept(new AudioDelta("AAAA", "pcm", 24000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        void release() {
            release.countDown();
        }
    }
}
