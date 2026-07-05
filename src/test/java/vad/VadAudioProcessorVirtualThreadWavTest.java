package vad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import audio.AudioBuffer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
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
import org.junit.jupiter.api.Test;
import stt.SpeechToText;
import stt.Transcription;
import stt.WhisperServerSpeechToText;
import vad.silero.SileroVad;
import vad.silero.VoiceActivityDetector;

class VadAudioProcessorVirtualThreadWavTest {
    // 実ファイル名は nakagawke01.wav。ユーザ指定の nakagawake01.wav とは 1 文字違うため、
    // 存在するテストデータを明示して参照する。
    private static final Path AUDIO_FILE = Path.of("src/test/test-data/nakagawke01.wav");
    private static final Path EXPECTED_TRANSCRIPT_FILE = Path.of("src/test/test-data/nakagawke01-small.txt");
    private static final int CHUNK_MILLIS = 200;
    private static final int CHUNK_SAMPLES = VadAudioProcessor.VAD_FRAME_SAMPLES * 12;
    // 先頭 10 秒を処理する。音声データは約 3 秒まで音楽で、その後に会話が始まる。
    private static final int AUDIO_CHUNKS_TO_SEND = 50;// 200ms * 50 = 10秒
    private static final int FLUSH_SILENCE_CHUNKS = 10;

    @Test
    void processesWavChunksWithSeparateVirtualThreads() throws Exception {
        assertTrue(Files.exists(AUDIO_FILE), "test audio not found: " + AUDIO_FILE);
        assertTrue(Files.exists(EXPECTED_TRANSCRIPT_FILE), "expected transcript not found: " + EXPECTED_TRANSCRIPT_FILE);
        assumeTrue(Files.exists(SileroVadModelDownloader.MODEL_PATH),
                "Silero VAD model is not downloaded: " + SileroVadModelDownloader.MODEL_PATH);

        URI endpoint = URI.create(System.getProperty(
                "whisper.server.url",
                WhisperServerSpeechToText.DEFAULT_ENDPOINT.toString()));
        assumeWhisperServerReachable(endpoint);

        short[] samples = readPcm16Mono16k(AUDIO_FILE);
        assertTrue(samples.length >= CHUNK_SAMPLES * AUDIO_CHUNKS_TO_SEND);
        int chunksToSend = AUDIO_CHUNKS_TO_SEND + FLUSH_SILENCE_CHUNKS;

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
                    for (int i = 0; i < AUDIO_CHUNKS_TO_SEND; i++) {
                        int offset = i * CHUNK_SAMPLES;
                        queue.put(new AudioChunk(pcm16LeBytes(samples, offset, CHUNK_SAMPLES), false));
                        sentChunks.incrementAndGet();
                    }
                    byte[] silence = new byte[CHUNK_SAMPLES * Short.BYTES];
                    for (int i = 0; i < FLUSH_SILENCE_CHUNKS; i++) {
                        queue.put(new AudioChunk(silence, false));
                        sentChunks.incrementAndGet();
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
                        System.out.println("acceptPcm16Le result[" + processedChunks.get() + "] = " + result);
                        result.ifPresent(transcription -> System.out.println("transcription segment: " + transcription.text()));
                        processedChunks.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });

            producer.join(TimeUnit.SECONDS.toMillis(30));
            processorThread.join(TimeUnit.SECONDS.toMillis(30));

            assertFalse(producer.isAlive(), "producer virtual thread did not finish");
            assertFalse(processorThread.isAlive(), "processor virtual thread did not finish");
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }
            processor.awaitTranscriptions(Duration.ofSeconds(120));
        }

        assertEquals(chunksToSend, sentChunks.get());
        assertEquals(chunksToSend, processedChunks.get());
        assertEquals(chunksToSend, acceptResults.size());
        assertTrue(vad.calls.get() > 0);
        System.out.println("vad calls: " + vad.calls.get());
        System.out.println("vad max probability: " + vad.maxProbability.get());
        System.out.println("vad start-threshold frames: " + vad.startThresholdFrames.get());
        assertFalse(speechToText.transcripts.isEmpty(), "STT result was not printed");

        String actualTranscript = String.join("\n", speechToText.transcripts);
        System.out.println("expected transcript file: " + EXPECTED_TRANSCRIPT_FILE.toAbsolutePath());
        System.out.println("expected full transcription reference:\n" + expectedTranscript);
        System.out.println("actual transcription:\n" + actualTranscript);
        assertFalse(actualTranscript.isBlank(), "Transcription text is blank");
        assertTrue(
                actualTranscript.contains("はい") || actualTranscript.contains("どちら"),
                "Transcription did not contain an expected Japanese phrase: " + actualTranscript);
    }

    private static void assumeWhisperServerReachable(URI endpoint) {
        String host = endpoint.getHost();
        int port = endpoint.getPort();
        if (host == null || port < 0) {
            assumeTrue(false, "whisper.server.url must include host and port: " + endpoint);
            return;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), (int) TimeUnit.SECONDS.toMillis(2));
        } catch (IOException e) {
            assumeTrue(false, "whisper-server is not running at " + endpoint);
        }
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(2))
                .GET()
                .build();
        try {
            HttpResponse<Void> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.discarding());
            assumeTrue(!response.headers()
                    .firstValue("Server")
                    .map(value -> value.contains("lfm2-audio-server"))
                    .orElse(false), "lfm2-audio-server is running at " + endpoint + ", not whisper-server");
        } catch (IOException e) {
            assumeTrue(false, "whisper-server did not respond at " + endpoint);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            assumeTrue(false, "interrupted while probing whisper-server at " + endpoint);
        }
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
        public Transcription transcribe(
                AudioBuffer audioBuffer,
                long startSampleIndex,
                long endSampleIndexExclusive,
                String prompt) {
            Transcription transcription = delegate.transcribe(
                    audioBuffer,
                    startSampleIndex,
                    endSampleIndexExclusive,
                    prompt);
            transcripts.add(transcription.text());
            System.out.println("stt [" + startSampleIndex + ", " + endSampleIndexExclusive + ") = " + transcription.text());
            return transcription;
        }
    }
}
