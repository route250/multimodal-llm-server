package vad;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import audio.AudioBuffer;
import java.io.IOException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import org.junit.jupiter.api.Test;
import stt.SpeechToText;
import vad.silero.VoiceActivityDetector;

class VadAudioProcessorVirtualThreadWavTest {
    // 実ファイル名は nakagawke01.wav。ユーザ指定の nakagawake01.wav とは 1 文字違うため、
    // 存在するテストデータを明示して参照する。
    private static final Path AUDIO_FILE = Path.of("src/test/test-data/nakagawke01.wav");
    private static final int CHUNK_MILLIS = 200;
    private static final int CHUNK_SAMPLES = VadAudioProcessor.SAMPLE_RATE * CHUNK_MILLIS / 1_000;
    // 全量を実時間で流すとテストが長くなるため、VirtualThread 間の受け渡し検証に必要な長さだけ送る。
    private static final int CHUNKS_TO_SEND = 6;

    @Test
    void processesWavChunksWithSeparateVirtualThreads() throws Exception {
        assertTrue(Files.exists(AUDIO_FILE), "test audio not found: " + AUDIO_FILE);

        short[] samples = readPcm16Mono16k(AUDIO_FILE);
        assertTrue(samples.length >= CHUNK_SAMPLES * CHUNKS_TO_SEND);

        // 音声を読むスレッドと VadAudioProcessor を実行するスレッドを、実運用に近い形で分離する。
        BlockingQueue<AudioChunk> queue = new ArrayBlockingQueue<>(2);
        CountingVad vad = new CountingVad();
        RecordingSpeechToText speechToText = new RecordingSpeechToText("transcript");
        AtomicInteger sentChunks = new AtomicInteger();
        AtomicInteger processedChunks = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Optional<String>> acceptResults = Collections.synchronizedList(new ArrayList<>());

        try (ExecutorService transcriptionExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
            VadAudioProcessor processor = new VadAudioProcessor(
                    vad,
                    ignored -> true,
                    speechToText,
                    transcriptionExecutor);

            Thread producer = Thread.ofVirtual().name("wav-chunk-producer").start(() -> {
                try {
                    for (int i = 0; i < CHUNKS_TO_SEND; i++) {
                        int offset = i * CHUNK_SAMPLES;
                        queue.put(new AudioChunk(pcm16LeBytes(samples, offset, CHUNK_SAMPLES), false));
                        sentChunks.incrementAndGet();
                        // 200ms 分の音声を 200ms ごとに渡し、入力ペースを実時間に合わせる。
                        Thread.sleep(Duration.ofMillis(CHUNK_MILLIS));
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
                        Optional<String> result = processor.acceptPcm16Le(chunk.bytes());
                        acceptResults.add(result);
                        System.out.println("acceptPcm16Le result[" + processedChunks.get() + "] = " + result);
                        processedChunks.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Throwable t) {
                    failure.compareAndSet(null, t);
                }
            });

            producer.join(Duration.ofSeconds(3));
            processorThread.join(Duration.ofSeconds(3));

            assertFalse(producer.isAlive(), "producer virtual thread did not finish");
            assertFalse(processorThread.isAlive(), "processor virtual thread did not finish");
            if (failure.get() != null) {
                throw new AssertionError(failure.get());
            }

            assertTrue(speechToText.completed.await(1, TimeUnit.SECONDS));
        }

        assertEquals(CHUNKS_TO_SEND, sentChunks.get());
        assertEquals(CHUNKS_TO_SEND, processedChunks.get());
        assertEquals(CHUNKS_TO_SEND, acceptResults.size());
        assertTrue(
                acceptResults.stream().anyMatch(result -> result.equals(Optional.of("transcript"))),
                "acceptPcm16Le did not return transcript: " + acceptResults);
        assertTrue(vad.calls.get() > 0);
        assertEquals("transcript", speechToText.text.get());
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

    private record AudioChunk(byte[] bytes, boolean terminal) {
        static AudioChunk end() {
            return new AudioChunk(new byte[0], true);
        }
    }

    private static class CountingVad implements VoiceActivityDetector {
        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public float speechProbability(float[] samples) {
            // 最初の VAD フレームだけ発話として扱い、以降を無音にする。
            // これにより短いテスト入力でも STT 起動まで到達できる。
            return calls.getAndIncrement() == 0 ? 0.6f : 0.0f;
        }
    }

    private static class RecordingSpeechToText implements SpeechToText {
        private final String transcript;
        private final CountDownLatch completed = new CountDownLatch(1);
        private final AtomicReference<String> text = new AtomicReference<>();

        RecordingSpeechToText(String transcript) {
            this.transcript = transcript;
        }

        @Override
        public String transcribe(AudioBuffer audioBuffer, long startSampleIndex, long endSampleIndexExclusive) {
            text.set(transcript);
            completed.countDown();
            return transcript;
        }
    }
}
