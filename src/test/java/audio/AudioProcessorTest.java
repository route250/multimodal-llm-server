package audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import audio.vad.TurnDetector;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import audio.stt.SpeechToText;
import audio.stt.TranscriptSegment;
import audio.stt.Transcription;

class AudioProcessorTest {
    @Test
    void trailingSilenceReturnsToDetectedWhenSpeechResumes() {
        ExecutorService transcriptionExecutor = Executors.newSingleThreadExecutor();
        try {
        RecordingSpeechToText speechToText = new RecordingSpeechToText("hello");
        AudioProcessor processor = new AudioProcessor(samples -> true, speechToText, transcriptionExecutor);

        assertTrue(acceptSpeechStart(processor).isEmpty());

        for (int i = 0; i < 5; i++) {
            assertTrue(acceptFrame(processor, 0).isEmpty());
        }

        assertTrue(acceptSpeechStart(processor).isEmpty());

        Optional<Transcription> transcript = Optional.empty();
        for (int i = 0; i < silenceFramesForTurnDetection(); i++) {
            transcript = acceptFrame(processor, 0);
        }

        assertTrue(transcript.isEmpty());
        assertTrue(speechToText.completed.await(1, TimeUnit.SECONDS));

        transcript = acceptUntilTranscript(processor);

        assertTranscriptText("hello", transcript);
        assertEquals(1, speechToText.calls);
        assertEquals(0, speechToText.startSampleIndex);
        assertEquals(71L * AudioProcessor.VAD_FRAME_SAMPLES, speechToText.endSampleIndexExclusive);
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
        SequenceTurnDetector turnDetector = new SequenceTurnDetector();
        RecordingSpeechToText speechToText = new RecordingSpeechToText("hello");
        AudioProcessor processor = new AudioProcessor(turnDetector, speechToText, transcriptionExecutor);

        assertTrue(acceptSpeechStart(processor).isEmpty());

        turnDetector.add(false);
        for (int i = 0; i < silenceFramesForTurnDetection(); i++) {
            assertTrue(acceptFrame(processor, 0).isEmpty());
        }
        assertEquals(1, turnDetector.calls);
        assertEquals(0, speechToText.calls);

        for (int i = 0; i < silenceFramesForTurnDetection() - 1; i++) {
            assertTrue(acceptFrame(processor, 0).isEmpty());
        }
        assertEquals(1, turnDetector.calls);
        assertEquals(0, speechToText.calls);

        turnDetector.add(true);
        assertTrue(acceptFrame(processor, 0).isEmpty());
        assertTrue(speechToText.completed.await(1, TimeUnit.SECONDS));

        assertTranscriptText("hello", acceptUntilTranscript(processor));
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
    void incompleteTurnDetectionForcesFinalAfterMaxSilence() {
        SequenceTurnDetector turnDetector = new SequenceTurnDetector();
        RecordingSpeechToText speechToText = new RecordingSpeechToText("forced final");
        AudioProcessor processor = new AudioProcessor(turnDetector, speechToText, Runnable::run);

        acceptSpeechStart(processor);

        turnDetector.add(false);
        for (int i = 0; i < silenceFramesForTurnDetection(); i++) {
            assertTrue(acceptFrame(processor, 0).isEmpty());
        }
        assertEquals(1, turnDetector.calls);
        assertEquals(0, speechToText.calls);

        turnDetector.add(false);
        Optional<Transcription> transcript = Optional.empty();
        for (int i = silenceFramesForTurnDetection(); i < silenceFramesForForcedFinal(); i++) {
            transcript = acceptFrame(processor, 0);
        }

        assertTranscriptText("forced final", transcript);
        assertEquals(2, turnDetector.calls);
        assertEquals(1, speechToText.calls);
    }

    @Test
    void incompleteTurnDetectionDoesNotForceFinalBeforeMaxSilence() {
        SequenceTurnDetector turnDetector = new SequenceTurnDetector();
        RecordingSpeechToText speechToText = new RecordingSpeechToText("too early");
        AudioProcessor processor = new AudioProcessor(turnDetector, speechToText, Runnable::run);

        acceptSpeechStart(processor);

        turnDetector.add(false);
        for (int i = 0; i < silenceFramesForTurnDetection(); i++) {
            assertTrue(acceptFrame(processor, 0).isEmpty());
        }
        for (int i = silenceFramesForTurnDetection(); i < silenceFramesForForcedFinal() - 1; i++) {
            assertTrue(acceptFrame(processor, 0).isEmpty());
        }

        assertEquals(1, turnDetector.calls);
        assertEquals(0, speechToText.calls);
    }

    @Test
    void crowdNoiseAtSixtyNineDoesNotStartSpeech() {
        RecordingSpeechToText speechToText = new RecordingSpeechToText("noise");
        AudioProcessor processor = new AudioProcessor(samples -> true, speechToText, Runnable::run);

        for (int i = 0; i < 300; i++) {
            assertTrue(acceptFrame(processor, 69).isEmpty());
        }

        assertEquals(0, speechToText.calls);
    }

    @Test
    void crowdNoiseAtSixtyNineEndsDetectedSpeech() {
        RecordingSpeechToText speechToText = new RecordingSpeechToText("hello");
        AudioProcessor processor = new AudioProcessor(samples -> true, speechToText, Runnable::run);

        assertTrue(acceptSpeechStart(processor).isEmpty());

        Optional<Transcription> transcript = Optional.empty();
        for (int i = 0; i < silenceFramesForTurnDetection(); i++) {
            transcript = acceptFrame(processor, 69, (short) 0);
        }

        assertTranscriptText("hello", transcript);
        assertEquals(1, speechToText.calls);
    }

    @Test
    void lowRmsDoesNotStartSpeechEvenWhenVadIsHigh() {
        RecordingSpeechToText speechToText = new RecordingSpeechToText("ignored");
        AudioProcessor processor = new AudioProcessor(samples -> true, speechToText, Runnable::run);

        for (int i = 0; i < speechStartFrames() * 2; i++) {
            assertTrue(acceptFrame(processor, 90, 5).isEmpty());
        }

        assertEquals(0, speechToText.calls);
    }

    @Test
    void lowRmsEndsDetectedSpeechEvenWhenVadIsHigh() {
        RecordingSpeechToText speechToText = new RecordingSpeechToText("hello");
        AudioProcessor processor = new AudioProcessor(samples -> true, speechToText, Runnable::run);

        assertTrue(acceptSpeechStart(processor).isEmpty());

        Optional<Transcription> transcript = Optional.empty();
        for (int i = 0; i < silenceFramesForTurnDetection(); i++) {
            transcript = acceptFrame(processor, 90, 0);
        }

        assertTranscriptText("hello", transcript);
        assertEquals(1, speechToText.calls);
    }

    @Test
    void emptyFinalRangeStillCallsSpeechToText() {
        RecordingSpeechToText speechToText = new RecordingSpeechToText("empty range");
        AudioProcessor processor = new AudioProcessor(samples -> true, speechToText, Runnable::run);

        acceptSpeechStart(processor);
        processor.ignoreTranscriptionBefore(Long.MAX_VALUE);
        acceptCompleteSpeechSilence(processor);

        assertEquals(1, speechToText.calls);
        assertEquals(speechToText.startSampleIndex, speechToText.endSampleIndexExclusive);
    }

    @Test
    void asynchronousTranscriptionDoesNotBlockLaterAudioFrames() throws Exception {
        ExecutorService transcriptionExecutor = Executors.newSingleThreadExecutor();
        try {
            BlockingSpeechToText speechToText = new BlockingSpeechToText("hello");
            AudioProcessor processor = new AudioProcessor(
                    samples -> true,
                    speechToText,
                    transcriptionExecutor);

            assertTrue(acceptSpeechStart(processor).isEmpty());

            for (int i = 0; i < silenceFramesForTurnDetection(); i++) {
                assertTimeoutPreemptively(
                        Duration.ofMillis(200),
                        () -> assertTrue(acceptFrame(processor, 0).isEmpty()));
            }

            assertTrue(speechToText.started.await(1, TimeUnit.SECONDS));

            assertTimeoutPreemptively(
                    Duration.ofMillis(200),
                    () -> assertTrue(acceptFrame(processor, 60).isEmpty()));

            speechToText.release();
        } finally {
            transcriptionExecutor.shutdownNow();
        }
    }

    @Test
    void asynchronousTranscriptionResultIsReturnedByNextAccept() throws Exception {
        ExecutorService transcriptionExecutor = Executors.newSingleThreadExecutor();
        try {
            BlockingSpeechToText speechToText = new BlockingSpeechToText("hello");
            AudioProcessor processor = new AudioProcessor(
                    samples -> true,
                    speechToText,
                    transcriptionExecutor);

            assertTrue(acceptSpeechStart(processor).isEmpty());
            for (int i = 0; i < silenceFramesForTurnDetection(); i++) {
                assertTrue(acceptFrame(processor, 0).isEmpty());
            }

            assertTrue(speechToText.started.await(1, TimeUnit.SECONDS));
            speechToText.release();
            assertTrue(speechToText.completed.await(1, TimeUnit.SECONDS));

            assertTranscriptText("hello", acceptFrame(processor, 0));
        } finally {
            transcriptionExecutor.shutdownNow();
        }
    }

    @Test
    void asynchronousTranscriptionFailureIsThrownByNextAccept() throws Exception {
        ExecutorService transcriptionExecutor = Executors.newSingleThreadExecutor();
        try {
            FailingSpeechToText speechToText = new FailingSpeechToText();
            AudioProcessor processor = new AudioProcessor(
                    samples -> true,
                    speechToText,
                    transcriptionExecutor);

            assertTrue(acceptSpeechStart(processor).isEmpty());
            for (int i = 0; i < silenceFramesForTurnDetection(); i++) {
                assertTrue(acceptFrame(processor, 0).isEmpty());
            }

            assertTrue(speechToText.completed.await(1, TimeUnit.SECONDS));
            RuntimeException failure = assertThrows(RuntimeException.class, () -> acceptUntilFailure(processor));
            assertEquals("stt failed", failure.getMessage());
        } finally {
            transcriptionExecutor.shutdownNow();
        }
    }

    @Test
    void staleTranscriptionResultIsDiscardedWhenNewSpeechUpdatesLastSpeechSampleIndex() throws Exception {
        ExecutorService transcriptionExecutor = Executors.newSingleThreadExecutor();
        try {
            BlockingSpeechToText speechToText = new BlockingSpeechToText("stale");
            AudioProcessor processor = new AudioProcessor(
                    samples -> true,
                    speechToText,
                    transcriptionExecutor);

            assertTrue(acceptSpeechStart(processor).isEmpty());
            for (int i = 0; i < silenceFramesForTurnDetection(); i++) {
                assertTrue(acceptFrame(processor, 0).isEmpty());
            }
            assertTrue(speechToText.started.await(1, TimeUnit.SECONDS));

            assertTrue(acceptSpeechStart(processor).isEmpty());

            speechToText.release();
            assertTrue(speechToText.completed.await(1, TimeUnit.SECONDS));

            assertTrue(acceptFrame(processor, 0).isEmpty());
        } finally {
            transcriptionExecutor.shutdownNow();
        }
    }

    @Test
    void staleTranscriptionFailureIsDiscardedWhenNewSpeechUpdatesLastSpeechSampleIndex() throws Exception {
        ExecutorService transcriptionExecutor = Executors.newSingleThreadExecutor();
        try {
            DelayedFailingSpeechToText speechToText = new DelayedFailingSpeechToText();
            AudioProcessor processor = new AudioProcessor(
                    samples -> true,
                    speechToText,
                    transcriptionExecutor);

            assertTrue(acceptSpeechStart(processor).isEmpty());
            for (int i = 0; i < silenceFramesForTurnDetection(); i++) {
                assertTrue(acceptFrame(processor, 0).isEmpty());
            }
            assertTrue(speechToText.started.await(1, TimeUnit.SECONDS));

            assertTrue(acceptSpeechStart(processor).isEmpty());

            speechToText.release();
            assertTrue(speechToText.completed.await(1, TimeUnit.SECONDS));

            assertTrue(acceptFrame(processor, 0).isEmpty());
        } finally {
            transcriptionExecutor.shutdownNow();
        }
    }

    @Test
    void matchingLeadingSegmentUpdatesNextTranscriptionStart() {
        QueuedSegmentSpeechToText speechToText = new QueuedSegmentSpeechToText();
        speechToText.add(segmentTranscription("overlap12", Duration.ofSeconds(1)));
        speechToText.add(segmentTranscription("overlap12", Duration.ofSeconds(1)));
        speechToText.add(segmentTranscription("next", Duration.ofMillis(500)));
        AudioProcessor processor = new AudioProcessor(samples -> true, speechToText, Runnable::run);

        acceptCompleteSpeech(processor);
        acceptCompleteSpeech(processor);
        acceptCompleteSpeech(processor);

        assertEquals(List.of(0L, 3_712L, 18_112L), speechToText.startSampleIndexes);
    }

    @Test
    void detectedSpeechLongerThanPartialIntervalStartsPartialTranscription() {
        QueuedSegmentSpeechToText speechToText = new QueuedSegmentSpeechToText();
        speechToText.add(segmentTranscription("partial", Duration.ofMillis(4_800)));
        speechToText.add(segmentTranscription("final", Duration.ofMillis(700)));
        AudioProcessor processor = new AudioProcessor(samples -> true, speechToText, Runnable::run);

        assertTrue(acceptSpeechStart(processor).isEmpty());
        for (int i = speechStartFrames(); i < 300; i++) {
            acceptFrame(processor, 71);
        }

        assertEquals(List.of(0L), speechToText.startSampleIndexes);
        assertEquals(List.of(76_800L), speechToText.endSampleIndexes);

        Optional<Transcription> transcript = Optional.empty();
        for (int i = 0; i < silenceFramesForTurnDetection(); i++) {
            transcript = acceptFrame(processor, 0);
        }

        assertTranscriptText("final", transcript);
        assertEquals(List.of(0L, 75_200L), speechToText.startSampleIndexes);
    }

    @Test
    void detectedSpeechQueuesPartialTranscriptionsEveryInterval() {
        QueuedSegmentSpeechToText speechToText = new QueuedSegmentSpeechToText();
        speechToText.add(segmentTranscription("partial1", Duration.ofMillis(4_800)));
        speechToText.add(segmentTranscription("partial2", Duration.ofMillis(4_800)));
        speechToText.add(segmentTranscription("final", Duration.ofMillis(700)));
        AudioProcessor processor = new AudioProcessor(samples -> true, speechToText, Runnable::run);

        acceptSpeechStart(processor);
        for (int i = speechStartFrames(); i < 600; i++) {
            acceptFrame(processor, 71);
        }
        for (int i = 0; i < silenceFramesForTurnDetection(); i++) {
            acceptFrame(processor, 0);
        }

        assertEquals(List.of(0L, 75_200L, 152_000L), speechToText.startSampleIndexes);
        assertEquals(List.of(76_800L, 153_600L, 163_328L), speechToText.endSampleIndexes);
    }

    @Test
    void sttInputWavAndVadCsvAreSavedBeforeTranscription() throws Exception {
        RecordingSpeechToText speechToText = new RecordingSpeechToText("hello");
        AudioProcessor processor = new AudioProcessor(samples -> true, speechToText, Runnable::run);
        processor.setDiagnosticsContext("diag/group", "diag session");

        Optional<Transcription> transcript = acceptCompleteSpeech(processor);

        assertTranscriptText("hello", transcript);
        Path wavPath;
        try (var paths = Files.list(AudioDiagnostics.wavDir())) {
            wavPath = paths
                    .filter(path -> path.getFileName().toString()
                            .contains("_diag_group_diag_session_1_FINAL_0-13312.wav"))
                    .findFirst()
                    .orElseThrow();
        }

        byte[] wav = Files.readAllBytes(wavPath);
        assertEquals("RIFF", new String(wav, 0, 4));
        assertEquals("WAVE", new String(wav, 8, 4));
        assertEquals(44 + 13_312 * Short.BYTES, wav.length);
        assertEquals(13_312 * Short.BYTES, ByteBuffer.wrap(wav, 40, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .getInt());

        Path vadPath = wavPath.resolveSibling(wavPath.getFileName().toString().replace(".wav", ".vad.csv"));
        List<String> vadLines = Files.readAllLines(vadPath);
        assertEquals("startSampleIndex,endSampleIndexExclusive,vadValue", vadLines.getFirst());
        assertEquals("0,256,90", vadLines.get(1));
        assertTrue(vadLines.contains("3328,3584,90"));
        assertTrue(vadLines.contains("3584,3840,0"));
    }

    @Test
    void shortLowRmsAudioSuppressesPrompt() {
        PromptRecordingSpeechToText speechToText = new PromptRecordingSpeechToText("first", "second");
        AudioProcessor processor = new AudioProcessor(samples -> true, speechToText, Runnable::run);

        acceptCompleteSpeech(processor);
        acceptCompleteSpeech(processor);

        assertEquals(List.of("", ""), speechToText.prompts);
    }

    @Test
    void shortAudibleAudioKeepsPrompt() {
        PromptRecordingSpeechToText speechToText = new PromptRecordingSpeechToText("first", "second");
        AudioProcessor processor = new AudioProcessor(samples -> true, speechToText, Runnable::run);

        acceptCompleteSpeech(processor);
        acceptCompleteSpeech(processor, (short) 3_000);

        assertEquals(List.of("", "first"), speechToText.prompts);
    }

    private static byte[] frameBytes() {
        return new byte[AudioProcessor.VAD_FRAME_SAMPLES * Short.BYTES];
    }

    private static Optional<Transcription> acceptCompleteSpeech(AudioProcessor processor) {
        return acceptCompleteSpeech(processor, (short) 0);
    }

    private static Optional<Transcription> acceptCompleteSpeech(AudioProcessor processor, short sample) {
        Optional<Transcription> transcript;
        transcript = sample == 0 ? acceptSpeechStart(processor) : acceptSpeechStart(processor, sample);
        for (int i = 0; i < silenceFramesForTurnDetection(); i++) {
            transcript = sample == 0 ? acceptFrame(processor, 0) : acceptFrame(processor, 0, sample);
        }
        return transcript;
    }

    private static Optional<Transcription> acceptSpeechStart(AudioProcessor processor) {
        Optional<Transcription> transcript = Optional.empty();
        for (int i = 0; i < speechStartFrames(); i++) {
            transcript = acceptFrame(processor, 90);
        }
        return transcript;
    }

    private static Optional<Transcription> acceptSpeechStart(AudioProcessor processor, short sample) {
        Optional<Transcription> transcript = Optional.empty();
        for (int i = 0; i < speechStartFrames(); i++) {
            transcript = acceptFrame(processor, 90, sample);
        }
        return transcript;
    }

    private static int speechStartFrames() {
        return AudioProcessor.SPIKE_SAMPLES / AudioProcessor.VAD_FRAME_SAMPLES + 2;
    }

    private static Transcription segmentTranscription(String text, Duration end) {
        return new Transcription(text, List.of(new TranscriptSegment(Duration.ZERO, end, text)));
    }

    private static Optional<Transcription> acceptUntilTranscript(AudioProcessor processor) {
        Optional<Transcription> transcript = Optional.empty();
        for (int i = 0; i < 50 && transcript.isEmpty(); i++) {
            transcript = acceptFrame(processor, 0);
            if (transcript.isEmpty()) {
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return Optional.empty();
                }
            }
        }
        return transcript;
    }

    private static void acceptUntilFailure(AudioProcessor processor) {
        for (int i = 0; i < 50; i++) {
            acceptFrame(processor, 0);
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static Optional<Transcription> acceptFrame(AudioProcessor processor, int vadValue) {
        return processor.acceptPcm16LeWithVadDetailed(
                        frameBytes(),
                        new byte[] {(byte) vadValue},
                        new byte[] {(byte) (vadValue == 0 ? 0 : 100)})
                .map(AudioProcessor.TranscriptionResult::transcription);
    }

    private static Optional<Transcription> acceptFrame(AudioProcessor processor, int vadValue, int rmsValue) {
        return processor.acceptPcm16LeWithVadDetailed(
                        frameBytes(),
                        new byte[] {(byte) vadValue},
                        new byte[] {(byte) rmsValue})
                .map(AudioProcessor.TranscriptionResult::transcription);
    }

    private static Optional<Transcription> acceptFrame(AudioProcessor processor, int vadValue, short sample) {
        byte[] bytes = frameBytes();
        if (sample != 0) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < AudioProcessor.VAD_FRAME_SAMPLES; i++) {
                buffer.putShort(sample);
            }
        }
        return processor.acceptPcm16LeWithVadDetailed(
                        bytes,
                        new byte[] {(byte) vadValue},
                        new byte[] {rmsByte(bytes)})
                .map(AudioProcessor.TranscriptionResult::transcription);
    }

    private static byte rmsByte(byte[] bytes) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        double squareSum = 0.0;
        int sampleCount = bytes.length / Short.BYTES;
        for (int i = 0; i < sampleCount; i++) {
            double sample = buffer.getShort() / 32768.0;
            squareSum += sample * sample;
        }
        int value = (int) Math.round(Math.sqrt(squareSum / Math.max(1, sampleCount)) * 100.0);
        return (byte) Math.max(0, Math.min(100, value));
    }

    private static int silenceFramesForTurnDetection() {
        return AudioProcessor.MIN_TURN_DETECTION_SILENCE_SAMPLES / AudioProcessor.VAD_FRAME_SAMPLES + 1;
    }

    private static int silenceFramesForForcedFinal() {
        return AudioProcessor.MAX_TURN_DETECTION_SILENCE_SAMPLES / AudioProcessor.VAD_FRAME_SAMPLES + 1;
    }

    private static Optional<Transcription> acceptCompleteSpeechSilence(AudioProcessor processor) {
        Optional<Transcription> transcript = Optional.empty();
        for (int i = 0; i < silenceFramesForTurnDetection(); i++) {
            transcript = acceptFrame(processor, 0);
        }
        return transcript;
    }

    private static void assertTranscriptText(String expected, Optional<Transcription> actual) {
        assertEquals(Optional.of(expected), actual.map(Transcription::text));
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
        public Transcription transcribe(
                AudioBuffer audioBuffer,
                long startSampleIndex,
                long endSampleIndexExclusive,
                String prompt) {
            calls++;
            this.startSampleIndex = startSampleIndex;
            this.endSampleIndexExclusive = endSampleIndexExclusive;
            completed.countDown();
            return Transcription.singleSegment(
                    transcript,
                    endSampleIndexExclusive - startSampleIndex,
                    16_000);
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
        public Transcription transcribe(
                AudioBuffer audioBuffer,
                long startSampleIndex,
                long endSampleIndexExclusive,
                String prompt) {
            started.countDown();
            try {
                release.await(1, TimeUnit.SECONDS);
                return Transcription.singleSegment(
                        transcript,
                        endSampleIndexExclusive - startSampleIndex,
                        16_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Transcription.empty();
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
        public Transcription transcribe(
                AudioBuffer audioBuffer,
                long startSampleIndex,
                long endSampleIndexExclusive,
                String prompt) {
            completed.countDown();
            throw new RuntimeException("stt failed");
        }
    }

    private static class DelayedFailingSpeechToText implements SpeechToText {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch completed = new CountDownLatch(1);

        @Override
        public Transcription transcribe(
                AudioBuffer audioBuffer,
                long startSampleIndex,
                long endSampleIndexExclusive,
                String prompt) {
            started.countDown();
            try {
                release.await(1, TimeUnit.SECONDS);
                throw new RuntimeException("stale failure");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Transcription.empty();
            } finally {
                completed.countDown();
            }
        }

        void release() {
            release.countDown();
        }
    }

    private static class QueuedSegmentSpeechToText implements SpeechToText {
        private final ArrayDeque<Transcription> transcriptions = new ArrayDeque<>();
        private final List<Long> startSampleIndexes = new ArrayList<>();
        private final List<Long> endSampleIndexes = new ArrayList<>();

        void add(Transcription transcription) {
            transcriptions.addLast(transcription);
        }

        @Override
        public Transcription transcribe(
                AudioBuffer audioBuffer,
                long startSampleIndex,
                long endSampleIndexExclusive,
                String prompt) {
            startSampleIndexes.add(startSampleIndex);
            endSampleIndexes.add(endSampleIndexExclusive);
            return transcriptions.removeFirst();
        }
    }

    private static class PromptRecordingSpeechToText implements SpeechToText {
        private final ArrayDeque<String> transcripts = new ArrayDeque<>();
        private final List<String> prompts = new ArrayList<>();

        PromptRecordingSpeechToText(String... transcripts) {
            this.transcripts.addAll(List.of(transcripts));
        }

        @Override
        public Transcription transcribe(
                AudioBuffer audioBuffer,
                long startSampleIndex,
                long endSampleIndexExclusive,
                String prompt) {
            prompts.add(prompt);
            return Transcription.singleSegment(
                    transcripts.removeFirst(),
                    endSampleIndexExclusive - startSampleIndex,
                    16_000);
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
