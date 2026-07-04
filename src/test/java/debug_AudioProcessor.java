import audio.AudioBuffer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import model.download.SileroVadModelDownloader;
import stt.SpeechToText;
import stt.Transcription;
import stt.WhisperServerSpeechToText;
import vad.VadAudioProcessor;
import vad.silero.SileroVad;
import vad.silero.VoiceActivityDetector;

public class debug_AudioProcessor {
    // 実行例:
    // mvn -q -DskipTests compile
    // javac --release 21 -cp "target/classes:$HOME/.m2/repository/com/microsoft/onnxruntime/onnxruntime/1.26.0/onnxruntime-1.26.0.jar" -d ./tmp/debug-classes src/test/java/debug_AudioProcessor.java
    // java -cp "./tmp/debug-classes:target/classes:$HOME/.m2/repository/com/microsoft/onnxruntime/onnxruntime/1.26.0/onnxruntime-1.26.0.jar" debug_AudioProcessor

    // 存在するテストデータを明示して参照する。
    private static final Path AUDIO_FILE = Path.of("src/test/test-data/nakagawke01.wav");
    private static final Path EXPECTED_TRANSCRIPT_FILE = Path.of("src/test/test-data/nakagawke01-small.txt");
    private static final int CHUNK_MILLIS = 200;
    private static final int CHUNK_WAIT_JITTER_MILLIS = 20;
    private static final int CHUNK_SAMPLES = VadAudioProcessor.VAD_FRAME_SAMPLES * 12;
    private static final int FLUSH_SILENCE_CHUNKS = 10;

    public static void main(String[] args) throws Exception {
        require(Files.exists(AUDIO_FILE), "test audio not found: " + AUDIO_FILE);
        require(Files.exists(EXPECTED_TRANSCRIPT_FILE), "expected transcript not found: " + EXPECTED_TRANSCRIPT_FILE);
        require(Files.exists(SileroVadModelDownloader.MODEL_PATH),
                "Silero VAD model is not downloaded: " + SileroVadModelDownloader.MODEL_PATH);

        URI endpoint = URI.create(System.getProperty(
                "whisper.server.url",
                WhisperServerSpeechToText.DEFAULT_ENDPOINT.toString()));
        requireWhisperServerReachable(endpoint);

        short[] samples = readPcm16Mono16k(AUDIO_FILE);
        int audioChunksToSend = (samples.length + CHUNK_SAMPLES - 1) / CHUNK_SAMPLES;
        int chunksToSend = audioChunksToSend + FLUSH_SILENCE_CHUNKS;
        long streamTimeoutMillis = streamTimeoutMillis(chunksToSend);

        // 音声を読むスレッドと VadAudioProcessor を実行するスレッドを、実運用に近い形で分離する。
        BlockingQueue<AudioChunk> queue = new ArrayBlockingQueue<>(2);
        CountingVad vad = new CountingVad(new SileroVad(SileroVadModelDownloader.MODEL_PATH));
        RecordingSpeechToText speechToText = new RecordingSpeechToText(new WhisperServerSpeechToText(endpoint));
        AtomicInteger sentChunks = new AtomicInteger();
        AtomicInteger processedChunks = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Optional<Transcription>> acceptResults = Collections.synchronizedList(new ArrayList<>());
        String expectedTranscript = Files.readString(EXPECTED_TRANSCRIPT_FILE);

        try (vad; ExecutorService transcriptionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            VadAudioProcessor processor = new VadAudioProcessor(
                    ignored -> true,
                    speechToText,
                    transcriptionExecutor);

            Thread producer = Thread.ofVirtual().name("wav-chunk-producer").start(() -> {
                try {
                    for (int i = 0; i < audioChunksToSend; i++) {
                        int offset = i * CHUNK_SAMPLES;
                        int length = Math.min(CHUNK_SAMPLES, samples.length - offset);
                        queue.put(new AudioChunk(pcm16LeBytesPadded(samples, offset, length, CHUNK_SAMPLES), false));
                        sentChunks.incrementAndGet();
                        waitNextAudioChunk();
                    }
                    byte[] silence = new byte[CHUNK_SAMPLES * Short.BYTES];
                    for (int i = 0; i < FLUSH_SILENCE_CHUNKS; i++) {
                        queue.put(new AudioChunk(silence, false));
                        sentChunks.incrementAndGet();
                        waitNextAudioChunk();
                    }
                    // 空配列をデータとして扱わないよう、明示的な終了チャンクを送る。
                    queue.put(AudioChunk.end());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });

            Thread processorThread = Thread.ofVirtual().name("vad-audio-processor").start(() -> {
                try {
                    while (true) {
                        AudioChunk chunk = queue.take();
                        if (chunk.terminal()) {
                            return;
                        }
                        Optional<Transcription> result = processor.acceptPcm16LeWithVad(
                                chunk.bytes(),
                                browserVadBytes(chunk.bytes(), vad));
                        acceptResults.add(result);
                        // System.out.println("acceptPcm16Le result[" + processedChunks.get() + "] = " + result);
                        result.ifPresent(transcription -> System.out.println("transcription segment: " + transcription.text()));
                        processedChunks.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });

            producer.join(streamTimeoutMillis);
            processorThread.join(streamTimeoutMillis);

            require(!producer.isAlive(), "producer virtual thread did not finish");
            require(!processorThread.isAlive(), "processor virtual thread did not finish");
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }
            processor.awaitTranscriptions(Duration.ofSeconds(120));
        }

        require(chunksToSend == sentChunks.get(),
                "sent chunk count mismatch. expected=" + chunksToSend + ", actual=" + sentChunks.get());
        require(chunksToSend == processedChunks.get(),
                "processed chunk count mismatch. expected=" + chunksToSend + ", actual=" + processedChunks.get());
        require(chunksToSend == acceptResults.size(),
                "accept result count mismatch. expected=" + chunksToSend + ", actual=" + acceptResults.size());
        require(vad.calls.get() > 0, "VAD was not called");

        System.out.println("vad calls: " + vad.calls.get());
        System.out.println("vad max probability: " + vad.maxProbability.get());
        System.out.println("vad start-threshold frames: " + vad.startThresholdFrames.get());
        require(!speechToText.transcripts.isEmpty(), "STT result was not printed");

        String actualTranscript = String.join("\n", speechToText.transcripts);
        printTranscriptComparison(expectedTranscript, actualTranscript);
        require(!actualTranscript.isBlank(), "Transcription text is blank");
        require(actualTranscript.contains("はい") || actualTranscript.contains("どちら"),
                "Transcription did not contain an expected Japanese phrase: " + actualTranscript);
    }

    private static void printTranscriptComparison(String expectedTranscript, String actualTranscript) {
        String expected = normalizeLineEndings(expectedTranscript).trim();
        String actual = normalizeLineEndings(actualTranscript).trim();
        String normalizedExpected = normalizeForComparison(expected);
        String normalizedActual = normalizeForComparison(actual);
        boolean rawEquals = expected.equals(actual);
        boolean normalizedEquals = normalizedExpected.equals(normalizedActual);
        int rawDistance = levenshteinDistance(expected, actual);
        int normalizedDistance = levenshteinDistance(normalizedExpected, normalizedActual);

        System.out.println("expected transcript file: " + EXPECTED_TRANSCRIPT_FILE.toAbsolutePath());
        System.out.println("expected transcription:\n" + expected);
        System.out.println("actual transcription:\n" + actual);
        System.out.println("comparison:");
        System.out.println("  raw equals: " + rawEquals);
        System.out.println("  normalized equals: " + normalizedEquals);
        System.out.println("  expected chars: " + expected.length());
        System.out.println("  actual chars: " + actual.length());
        System.out.println("  raw levenshtein distance: " + rawDistance);
        System.out.printf("  raw similarity: %.2f%%%n", similarityPercent(expected.length(), actual.length(), rawDistance));
        System.out.println("  normalized expected chars: " + normalizedExpected.length());
        System.out.println("  normalized actual chars: " + normalizedActual.length());
        System.out.println("  normalized levenshtein distance: " + normalizedDistance);
        System.out.printf("  normalized similarity: %.2f%%%n",
                similarityPercent(normalizedExpected.length(), normalizedActual.length(), normalizedDistance));
        printFirstDifference("raw", expected, actual);
        printFirstDifference("normalized", normalizedExpected, normalizedActual);
    }

    private static String normalizeLineEndings(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String normalizeForComparison(String text) {
        // 比較方式: Unicode NFKC 正規化後、全 Unicode 空白文字だけを削除する。
        return Normalizer.normalize(normalizeLineEndings(text).trim(), Normalizer.Form.NFKC)
                .replaceAll("\\p{javaWhitespace}+", "");
    }

    private static void printFirstDifference(String label, String expected, String actual) {
        int firstDifference = firstDifferenceIndex(expected, actual);
        if (firstDifference < 0) {
            System.out.println("  " + label + " first difference: none");
            return;
        }
        System.out.println("  " + label + " first difference index: " + firstDifference);
        System.out.println("  " + label + " expected around: " + visibleSlice(expected, firstDifference, 40));
        System.out.println("  " + label + " actual around: " + visibleSlice(actual, firstDifference, 40));
    }

    private static int firstDifferenceIndex(String expected, String actual) {
        int length = Math.min(expected.length(), actual.length());
        for (int i = 0; i < length; i++) {
            if (expected.charAt(i) != actual.charAt(i)) {
                return i;
            }
        }
        if (expected.length() != actual.length()) {
            return length;
        }
        return -1;
    }

    private static String visibleSlice(String text, int centerIndex, int radius) {
        int start = Math.max(0, centerIndex - radius);
        int end = Math.min(text.length(), centerIndex + radius);
        String prefix = start == 0 ? "" : "...";
        String suffix = end == text.length() ? "" : "...";
        return prefix + text.substring(start, end).replace("\n", "\\n").replace("\t", "\\t") + suffix;
    }

    private static int levenshteinDistance(String left, String right) {
        if (left.isEmpty()) {
            return right.length();
        }
        if (right.isEmpty()) {
            return left.length();
        }

        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= right.length(); j++) {
                int replacementCost = left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(
                        Math.min(previous[j] + 1, current[j - 1] + 1),
                        previous[j - 1] + replacementCost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[right.length()];
    }

    private static double similarityPercent(int expectedLength, int actualLength, int distance) {
        int maxLength = Math.max(expectedLength, actualLength);
        if (maxLength == 0) {
            return 100.0;
        }
        return Math.max(0.0, 100.0 * (maxLength - distance) / maxLength);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void requireWhisperServerReachable(URI endpoint) {
        String host = endpoint.getHost();
        int port = endpoint.getPort();
        require(host != null && port >= 0, "whisper.server.url must include host and port: " + endpoint);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) TimeUnit.SECONDS.toMillis(2));
        } catch (IOException e) {
            throw new IllegalStateException("whisper-server is not running at " + endpoint, e);
        }
    }

    private static void waitNextAudioChunk() throws InterruptedException {
        int waitMillis = ThreadLocalRandom.current().nextInt(
                CHUNK_MILLIS - CHUNK_WAIT_JITTER_MILLIS,
                CHUNK_MILLIS + CHUNK_WAIT_JITTER_MILLIS + 1);
        Thread.sleep(waitMillis);
    }

    private static long streamTimeoutMillis(int chunksToSend) {
        long maximumChunkWaitMillis = CHUNK_MILLIS + CHUNK_WAIT_JITTER_MILLIS;
        long streamDurationMillis = chunksToSend * maximumChunkWaitMillis;
        return streamDurationMillis + TimeUnit.SECONDS.toMillis(10);
    }

    private static short[] readPcm16Mono16k(Path path)
            throws IOException, UnsupportedAudioFileException {
        try (AudioInputStream source = AudioSystem.getAudioInputStream(path.toFile())) {
            AudioFormat sourceFormat = source.getFormat();
            // VadAudioProcessor は 16kHz の PCM16LE mono を前提にしている。
            // Java Sound でチャンネル数とエンディアンをそろえ、必要なら後段で 16kHz に変換する。
            AudioFormat pcmFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sourceFormat.getSampleRate(),
                    16,
                    1,
                    2,
                    sourceFormat.getSampleRate(),
                    false);
            try (AudioInputStream pcmStream = AudioSystem.getAudioInputStream(pcmFormat, source)) {
                short[] sourceSamples = decodePcm16Le(pcmStream.readAllBytes());
                if (Math.round(pcmFormat.getSampleRate()) == VadAudioProcessor.SAMPLE_RATE) {
                    return sourceSamples;
                }
                return resample(sourceSamples, pcmFormat.getSampleRate(), VadAudioProcessor.SAMPLE_RATE);
            }
        }
    }

    private static short[] decodePcm16Le(byte[] bytes) {
        if ((bytes.length & 1) != 0) {
            throw new IllegalArgumentException("PCM16LE byte length must be even");
        }
        short[] samples = new short[bytes.length / Short.BYTES];
        for (int i = 0; i < samples.length; i++) {
            int low = bytes[i * 2] & 0xff;
            int high = bytes[i * 2 + 1];
            samples[i] = (short) ((high << 8) | low);
        }
        return samples;
    }

    private static short[] resample(short[] samples, float sourceRate, int targetRate) {
        int outputLength = Math.toIntExact((long) (samples.length - 1) * targetRate / Math.round(sourceRate));
        short[] output = new short[outputLength];
        double sourceSamplesPerOutput = sourceRate / targetRate;
        for (int i = 0; i < output.length; i++) {
            double sourcePosition = i * sourceSamplesPerOutput;
            int left = (int) sourcePosition;
            int right = Math.min(left + 1, samples.length - 1);
            double fraction = sourcePosition - left;
            output[i] = (short) Math.round(samples[left] + (samples[right] - samples[left]) * fraction);
        }
        return output;
    }

    private static byte[] pcm16LeBytes(short[] samples, int offset, int length) {
        short[] chunk = Arrays.copyOfRange(samples, offset, offset + length);
        byte[] bytes = new byte[chunk.length * Short.BYTES];
        for (int i = 0; i < chunk.length; i++) {
            bytes[i * 2] = (byte) (chunk[i] & 0xff);
            bytes[i * 2 + 1] = (byte) ((chunk[i] >>> 8) & 0xff);
        }
        return bytes;
    }

    private static byte[] pcm16LeBytesPadded(short[] samples, int offset, int length, int paddedLength) {
        short[] chunk = new short[paddedLength];
        System.arraycopy(samples, offset, chunk, 0, length);
        return pcm16LeBytes(chunk, 0, chunk.length);
    }

    private static byte[] browserVadBytes(byte[] pcmBytes, CountingVad vad) {
        short[] samples = decodePcm16Le(pcmBytes);
        int frameCount = samples.length / VadAudioProcessor.VAD_FRAME_SAMPLES;
        byte[] vadBytes = new byte[frameCount];
        if (isSilent(samples)) {
            return vadBytes;
        }
        for (int frameIndex = 0; frameIndex < frameCount; frameIndex += 2) {
            float[] sileroFrame = new float[SileroVad.CHUNK_SAMPLES];
            int sampleOffset = frameIndex * VadAudioProcessor.VAD_FRAME_SAMPLES;
            int copySamples = Math.min(SileroVad.CHUNK_SAMPLES, samples.length - sampleOffset);
            for (int i = 0; i < copySamples; i++) {
                sileroFrame[i] = samples[sampleOffset + i] / 32768.0f;
            }
            int value = Math.max(0, Math.min(100, Math.round(vad.speechProbability(sileroFrame) * 100)));
            vadBytes[frameIndex] = (byte) value;
            if (frameIndex + 1 < vadBytes.length) {
                vadBytes[frameIndex + 1] = (byte) value;
            }
        }
        return vadBytes;
    }

    private static boolean isSilent(short[] samples) {
        for (short sample : samples) {
            if (sample != 0) {
                return false;
            }
        }
        return true;
    }

    private record AudioChunk(byte[] bytes, boolean terminal) {
        static AudioChunk end() {
            return new AudioChunk(new byte[0], true);
        }
    }

    private static class CountingVad implements VoiceActivityDetector, AutoCloseable {
        private final SileroVad delegate;
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<Float> maxProbability = new AtomicReference<>(0.0f);
        private final AtomicInteger startThresholdFrames = new AtomicInteger();

        CountingVad(SileroVad delegate) {
            this.delegate = delegate;
        }

        @Override
        public float speechProbability(float[] samples) {
            calls.incrementAndGet();
            float probability = delegate.speechProbability(samples);
            maxProbability.accumulateAndGet(probability, Math::max);
            if (probability >= VadAudioProcessor.START_THRESHOLD) {
                startThresholdFrames.incrementAndGet();
            }
            return probability;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static class RecordingSpeechToText implements SpeechToText {
        private final SpeechToText delegate;
        private final List<String> transcripts = Collections.synchronizedList(new ArrayList<>());

        RecordingSpeechToText(SpeechToText delegate) {
            this.delegate = delegate;
        }

        @Override
        public String transcribe(AudioBuffer audioBuffer, long startSampleIndex, long endSampleIndexExclusive) {
            return transcribeWithSegments(audioBuffer, startSampleIndex, endSampleIndexExclusive).text();
        }

        @Override
        public Transcription transcribeWithSegments(AudioBuffer audioBuffer, long startSampleIndex, long endSampleIndexExclusive) {
            Transcription transcription = delegate.transcribeWithSegments(audioBuffer, startSampleIndex, endSampleIndexExclusive);
            transcripts.add(transcription.text());
            System.out.println("stt [" + startSampleIndex + ", " + endSampleIndexExclusive + ") = " + transcription.text());
            return transcription;
        }
    }
}
