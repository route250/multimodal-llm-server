package server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import vad.VadAudioProcessor;

@Timeout(5)
class ChatClientAudioProcessingTest {
    private static final String PCM16LE = "audio/pcm; rate=16000; channels=1; format=s16le";

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
    void transcriptIsPublishedAfterAsyncAudioProcessing() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient client = group.join("client-1");
            ChatClient processorClient = new ChatClient("processor", group, new TranscriptAudioProcessor("hello"));
            drainJoinEvents(client);

            processorClient.handle(audioRequest(new byte[]{0, 0}));

            ServerEvent event = client.events().poll(1, TimeUnit.SECONDS);
            assertNotNull(event);
            assertEquals("message", event.type());
            assertEquals("hello", event.message());
        }
    }

    @Test
    void asyncAudioFailureIsPublishedAndNextChunkContinues() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient client = group.join("client-1");
            FailingThenTranscriptAudioProcessor processor = new FailingThenTranscriptAudioProcessor();
            ChatClient processorClient = new ChatClient("processor", group, processor);
            drainJoinEvents(client);

            processorClient.handle(audioRequest(new byte[]{0, 0}));
            processorClient.handle(audioRequest(new byte[]{1, 0}));

            ServerEvent failure = client.events().poll(1, TimeUnit.SECONDS);
            ServerEvent transcript = client.events().poll(1, TimeUnit.SECONDS);
            assertNotNull(failure);
            assertNotNull(transcript);
            assertEquals("system", failure.type());
            assertEquals("audio processing failed: boom", failure.message());
            assertEquals("message", transcript.type());
            assertEquals("after failure", transcript.message());
        }
    }

    @Test
    void closedClientDoesNotPublishPendingAudioResult() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("listener");
            BlockingAudioProcessor processor = new BlockingAudioProcessor("late result");
            ChatClient processorClient = group.join("processor", processor);

            processorClient.handle(audioRequest(new byte[]{0, 0}));
            assertTrue(processor.started.await(1, TimeUnit.SECONDS));

            group.leave(processorClient);
            processor.release();

            assertTrue(processor.finished.await(1, TimeUnit.SECONDS));
            drainJoinEvents(listener);
            assertFalse(containsMessage(listener, "late result"));
        }
    }

    private static ChatRequest audioRequest(byte[] body) {
        return ChatRequest.from(PCM16LE, body);
    }

    private static void drainJoinEvents(ChatClient client) {
        client.events().removeIf(event -> event.message().contains(" joined "));
    }

    private static boolean containsMessage(ChatClient client, String message) {
        return client.events().stream().anyMatch(event -> message.equals(event.message()));
    }

    private static class RecordingAudioProcessor extends VadAudioProcessor {
        private final List<Integer> firstBytes = new ArrayList<>();
        private final CountDownLatch calls = new CountDownLatch(3);

        RecordingAudioProcessor() {
            super(samples -> 0.0f, samples -> true, (audioBuffer, startSampleIndex, endSampleIndexExclusive) -> "", Runnable::run);
        }

        @Override
        public synchronized Optional<String> acceptPcm16Le(byte[] bytes) {
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

    private static class TranscriptAudioProcessor extends VadAudioProcessor {
        private final String transcript;

        TranscriptAudioProcessor(String transcript) {
            super(samples -> 0.0f, samples -> true, (audioBuffer, startSampleIndex, endSampleIndexExclusive) -> "", Runnable::run);
            this.transcript = transcript;
        }

        @Override
        public Optional<String> acceptPcm16Le(byte[] bytes) {
            return Optional.of(transcript);
        }
    }

    private static class FailingThenTranscriptAudioProcessor extends VadAudioProcessor {
        private int calls;

        FailingThenTranscriptAudioProcessor() {
            super(samples -> 0.0f, samples -> true, (audioBuffer, startSampleIndex, endSampleIndexExclusive) -> "", Runnable::run);
        }

        @Override
        public synchronized Optional<String> acceptPcm16Le(byte[] bytes) {
            calls++;
            if (calls == 1) {
                throw new IllegalArgumentException("boom");
            }
            return Optional.of("after failure");
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
            super(samples -> 0.0f, samples -> true, (audioBuffer, startSampleIndex, endSampleIndexExclusive) -> "", Runnable::run);
            this.transcript = transcript;
        }

        @Override
        public Optional<String> acceptPcm16Le(byte[] bytes) {
            started.countDown();
            try {
                release.await(1, TimeUnit.SECONDS);
                return Optional.ofNullable(transcript);
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
}
