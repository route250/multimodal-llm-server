package vad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import audio.AudioBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import stt.SpeechToText;
import vad.silero.VoiceActivityDetector;

class VadAudioProcessorTest {
    @Test
    void trailingSilenceReturnsToDetectedWhenSpeechResumes() {
        ExecutorService transcriptionExecutor = Executors.newSingleThreadExecutor();
        try {
        SequenceVad vad = new SequenceVad();
        RecordingSpeechToText speechToText = new RecordingSpeechToText("hello");
        VadAudioProcessor processor = new VadAudioProcessor(vad, samples -> true, speechToText, transcriptionExecutor);

        vad.add(0.6f);
        assertTrue(processor.acceptPcm16Le(frameBytes()).isEmpty());

        for (int i = 0; i < 5; i++) {
            vad.add(0.0f);
            assertTrue(processor.acceptPcm16Le(frameBytes()).isEmpty());
        }

        vad.add(0.6f);
        assertTrue(processor.acceptPcm16Le(frameBytes()).isEmpty());

        Optional<String> transcript = Optional.empty();
        for (int i = 0; i < 19; i++) {
            vad.add(0.0f);
            transcript = processor.acceptPcm16Le(frameBytes());
        }

        assertTrue(transcript.isEmpty());
        assertTrue(speechToText.completed.await(1, TimeUnit.SECONDS));

        vad.add(0.0f);
        transcript = processor.acceptPcm16Le(frameBytes());

        assertEquals(Optional.of("hello"), transcript);
        assertEquals(1, speechToText.calls);
        assertEquals(0, speechToText.startSampleIndex);
        assertEquals(26L * VadAudioProcessor.VAD_FRAME_SAMPLES, speechToText.endSampleIndexExclusive);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            transcriptionExecutor.shutdownNow();
        }
    }

    @Test
    void turnDetectionRepeatsAfterIntervalUntilComplete() {
        ExecutorService transcriptionExecutor = Executors.newSingleThreadExecutor();
        try {
        SequenceVad vad = new SequenceVad();
        SequenceTurnDetector turnDetector = new SequenceTurnDetector();
        RecordingSpeechToText speechToText = new RecordingSpeechToText("hello");
        VadAudioProcessor processor = new VadAudioProcessor(vad, turnDetector, speechToText, transcriptionExecutor);

        vad.add(0.6f);
        assertTrue(processor.acceptPcm16Le(frameBytes()).isEmpty());

        turnDetector.add(false);
        for (int i = 0; i < 19; i++) {
            vad.add(0.0f);
            assertTrue(processor.acceptPcm16Le(frameBytes()).isEmpty());
        }
        assertEquals(1, turnDetector.calls);
        assertEquals(0, speechToText.calls);

        for (int i = 0; i < 18; i++) {
            vad.add(0.0f);
            assertTrue(processor.acceptPcm16Le(frameBytes()).isEmpty());
        }
        assertEquals(1, turnDetector.calls);
        assertEquals(0, speechToText.calls);

        vad.add(0.0f);
        turnDetector.add(true);
        assertTrue(processor.acceptPcm16Le(frameBytes()).isEmpty());
        assertTrue(speechToText.completed.await(1, TimeUnit.SECONDS));

        vad.add(0.0f);
        assertEquals(Optional.of("hello"), processor.acceptPcm16Le(frameBytes()));
        assertEquals(2, turnDetector.calls);
        assertEquals(1, speechToText.calls);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        } finally {
            transcriptionExecutor.shutdownNow();
        }
    }

    @Test
    void asynchronousTranscriptionDoesNotBlockLaterAudioFrames() throws Exception {
        ExecutorService transcriptionExecutor = Executors.newSingleThreadExecutor();
        try {
            SequenceVad vad = new SequenceVad();
            BlockingSpeechToText speechToText = new BlockingSpeechToText("hello");
            VadAudioProcessor processor = new VadAudioProcessor(
                    vad,
                    samples -> true,
                    speechToText,
                    transcriptionExecutor);

            vad.add(0.6f);
            assertTrue(processor.acceptPcm16Le(frameBytes()).isEmpty());

            for (int i = 0; i < 19; i++) {
                vad.add(0.0f);
                assertTimeoutPreemptively(
                        Duration.ofMillis(200),
                        () -> assertTrue(processor.acceptPcm16Le(frameBytes()).isEmpty()));
            }

            assertTrue(speechToText.started.await(1, TimeUnit.SECONDS));

            vad.add(0.6f);
            assertTimeoutPreemptively(
                    Duration.ofMillis(200),
                    () -> assertTrue(processor.acceptPcm16Le(frameBytes()).isEmpty()));
            assertEquals(21, vad.calls);

            speechToText.release();
        } finally {
            transcriptionExecutor.shutdownNow();
        }
    }

    @Test
    void asynchronousTranscriptionResultIsReturnedByNextAccept() throws Exception {
        ExecutorService transcriptionExecutor = Executors.newSingleThreadExecutor();
        try {
            SequenceVad vad = new SequenceVad();
            BlockingSpeechToText speechToText = new BlockingSpeechToText("hello");
            VadAudioProcessor processor = new VadAudioProcessor(
                    vad,
                    samples -> true,
                    speechToText,
                    transcriptionExecutor);

            vad.add(0.6f);
            assertTrue(processor.acceptPcm16Le(frameBytes()).isEmpty());
            for (int i = 0; i < 19; i++) {
                vad.add(0.0f);
                assertTrue(processor.acceptPcm16Le(frameBytes()).isEmpty());
            }

            assertTrue(speechToText.started.await(1, TimeUnit.SECONDS));
            speechToText.release();
            assertTrue(speechToText.completed.await(1, TimeUnit.SECONDS));

            vad.add(0.0f);
            assertEquals(Optional.of("hello"), processor.acceptPcm16Le(frameBytes()));
        } finally {
            transcriptionExecutor.shutdownNow();
        }
    }

    @Test
    void asynchronousTranscriptionFailureIsThrownByNextAccept() throws Exception {
        ExecutorService transcriptionExecutor = Executors.newSingleThreadExecutor();
        try {
            SequenceVad vad = new SequenceVad();
            FailingSpeechToText speechToText = new FailingSpeechToText();
            VadAudioProcessor processor = new VadAudioProcessor(
                    vad,
                    samples -> true,
                    speechToText,
                    transcriptionExecutor);

            vad.add(0.6f);
            assertTrue(processor.acceptPcm16Le(frameBytes()).isEmpty());
            for (int i = 0; i < 19; i++) {
                vad.add(0.0f);
                assertTrue(processor.acceptPcm16Le(frameBytes()).isEmpty());
            }

            assertTrue(speechToText.completed.await(1, TimeUnit.SECONDS));
            vad.add(0.0f);
            RuntimeException failure = assertThrows(RuntimeException.class, () -> processor.acceptPcm16Le(frameBytes()));
            assertEquals("stt failed", failure.getMessage());
        } finally {
            transcriptionExecutor.shutdownNow();
        }
    }

    @Test
    void staleTranscriptionResultIsDiscardedWhenNewSpeechUpdatesLastSpeechSampleIndex() throws Exception {
        ExecutorService transcriptionExecutor = Executors.newSingleThreadExecutor();
        try {
            SequenceVad vad = new SequenceVad();
            BlockingSpeechToText speechToText = new BlockingSpeechToText("stale");
            VadAudioProcessor processor = new VadAudioProcessor(
                    vad,
                    samples -> true,
                    speechToText,
                    transcriptionExecutor);

            vad.add(0.6f);
            assertTrue(processor.acceptPcm16Le(frameBytes()).isEmpty());
            for (int i = 0; i < 19; i++) {
                vad.add(0.0f);
                assertTrue(processor.acceptPcm16Le(frameBytes()).isEmpty());
            }
            assertTrue(speechToText.started.await(1, TimeUnit.SECONDS));

            vad.add(0.6f);
            assertTrue(processor.acceptPcm16Le(frameBytes()).isEmpty());

            speechToText.release();
            assertTrue(speechToText.completed.await(1, TimeUnit.SECONDS));

            vad.add(0.0f);
            assertTrue(processor.acceptPcm16Le(frameBytes()).isEmpty());
        } finally {
            transcriptionExecutor.shutdownNow();
        }
    }

    @Test
    void staleTranscriptionFailureIsDiscardedWhenNewSpeechUpdatesLastSpeechSampleIndex() throws Exception {
        ExecutorService transcriptionExecutor = Executors.newSingleThreadExecutor();
        try {
            SequenceVad vad = new SequenceVad();
            DelayedFailingSpeechToText speechToText = new DelayedFailingSpeechToText();
            VadAudioProcessor processor = new VadAudioProcessor(
                    vad,
                    samples -> true,
                    speechToText,
                    transcriptionExecutor);

            vad.add(0.6f);
            assertTrue(processor.acceptPcm16Le(frameBytes()).isEmpty());
            for (int i = 0; i < 19; i++) {
                vad.add(0.0f);
                assertTrue(processor.acceptPcm16Le(frameBytes()).isEmpty());
            }
            assertTrue(speechToText.started.await(1, TimeUnit.SECONDS));

            vad.add(0.6f);
            assertTrue(processor.acceptPcm16Le(frameBytes()).isEmpty());

            speechToText.release();
            assertTrue(speechToText.completed.await(1, TimeUnit.SECONDS));

            vad.add(0.0f);
            assertTrue(processor.acceptPcm16Le(frameBytes()).isEmpty());
        } finally {
            transcriptionExecutor.shutdownNow();
        }
    }

    private static byte[] frameBytes() {
        return new byte[VadAudioProcessor.VAD_FRAME_SAMPLES * Short.BYTES];
    }

    private static class SequenceVad implements VoiceActivityDetector {
        private final ArrayDeque<Float> probabilities = new ArrayDeque<>();
        private int calls;

        void add(float probability) {
            probabilities.add(probability);
        }

        @Override
        public float speechProbability(float[] samples) {
            calls++;
            return probabilities.removeFirst();
        }
    }

    private static class RecordingSpeechToText implements SpeechToText {
        private final String transcript;
        private final CountDownLatch completed = new CountDownLatch(1);
        private int calls;
        private long startSampleIndex;
        private long endSampleIndexExclusive;

        RecordingSpeechToText(String transcript) {
            this.transcript = transcript;
        }

        @Override
        public String transcribe(AudioBuffer audioBuffer, long startSampleIndex, long endSampleIndexExclusive) {
            calls++;
            this.startSampleIndex = startSampleIndex;
            this.endSampleIndexExclusive = endSampleIndexExclusive;
            completed.countDown();
            return transcript;
        }
    }

    private static class BlockingSpeechToText implements SpeechToText {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);
        private final String transcript;

        BlockingSpeechToText(String transcript) {
            this.transcript = transcript;
        }

        @Override
        public String transcribe(AudioBuffer audioBuffer, long startSampleIndex, long endSampleIndexExclusive) {
            started.countDown();
            try {
                release.await(1, TimeUnit.SECONDS);
                return transcript;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            } finally {
                completed.countDown();
            }
        }

        void release() {
            release.countDown();
        }
    }

    private static class FailingSpeechToText implements SpeechToText {
        private final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public String transcribe(AudioBuffer audioBuffer, long startSampleIndex, long endSampleIndexExclusive) {
            completed.countDown();
            throw new RuntimeException("stt failed");
        }
    }

    private static class DelayedFailingSpeechToText implements SpeechToText {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public String transcribe(AudioBuffer audioBuffer, long startSampleIndex, long endSampleIndexExclusive) {
            started.countDown();
            try {
                release.await(1, TimeUnit.SECONDS);
                throw new RuntimeException("stale failure");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            } finally {
                completed.countDown();
            }
        }

        void release() {
            release.countDown();
        }
    }

    private static class SequenceTurnDetector implements TurnDetector {
        private final ArrayDeque<Boolean> completions = new ArrayDeque<>();
        private int calls;

        void add(boolean complete) {
            completions.add(complete);
        }

        @Override
        public boolean isTurnComplete(float[] samples) {
            calls++;
            return completions.removeFirst();
        }
    }
}
