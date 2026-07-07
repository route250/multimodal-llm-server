package audio.vad.smartturn;

import audio.feature.LogMelFeatureExtractor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import model.download.ModelDownloader;
import model.download.SmartTurnV3ModelDownloader;

/**
 * Smart Turn v3 が最大 6 秒の音声を処理する時間を測る開発用ベンチマークです。
 *
 * <p>本番コードには組み込まず、設計判断のために手元で実行します。</p>
 *
 * <pre>
 * mvn -q -DskipTests test-compile
 * mvn -q dependency:build-classpath -Dmdep.outputFile=tmp/smartturnv3-classpath.txt
 * java -cp "target/test-classes:target/classes:$(cat tmp/smartturnv3-classpath.txt)" \
 *   audio.vad.smartturn.SmartTurnV3Benchmark
 * </pre>
 */
public class SmartTurnV3Benchmark {
    private static final int DEFAULT_AUDIO_SECONDS = 6;
    private static final int DEFAULT_ITERATIONS = 10;
    private static final int DEFAULT_WARMUP = 2;
    private static final int DEFAULT_PARALLELISM = 3;
    private static final Path DEFAULT_OUTPUT_DIR = Path.of("tmp", "smartturnv3-benchmark");

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        Files.createDirectories(config.outputDir());
        ModelDownloader.ensureDownloaded(SmartTurnV3ModelDownloader.MODEL_URI, config.modelPath());

        float[] samples = config.audioFile() == null
                ? syntheticSpeechLikeSamples(config.audioSeconds())
                : loadWavPcm16Mono(config.audioFile(), config.audioSeconds());

        // モデルロードと JIT の初回コストを計測から外す。
        try (SmartTurnV3 model = new SmartTurnV3(config.modelPath())) {
            for (int i = 0; i < config.warmup(); i++) {
                model.predict(samples);
            }
        }

        List<SingleResult> singleResults = measureSingle(config, samples);
        List<ParallelResult> parallelResults = measureParallel(config, samples);
        SingleSummary singleSummary = SingleSummary.of(singleResults);
        ParallelSummary parallelSummary = ParallelSummary.of(parallelResults);

        writeArtifacts(config, samples.length, singleResults, parallelResults, singleSummary, parallelSummary);
        printSummary(config, samples.length, singleSummary, parallelSummary);
    }

    private static List<SingleResult> measureSingle(Config config, float[] samples) {
        List<SingleResult> results = new ArrayList<>();
        try (SmartTurnV3 model = new SmartTurnV3(config.modelPath())) {
            for (int i = 1; i <= config.iterations(); i++) {
                long started = System.nanoTime();
                SmartTurnV3.Prediction prediction = model.predict(samples);
                long elapsedNanos = System.nanoTime() - started;
                results.add(new SingleResult(i, elapsedNanos, prediction.probability(), prediction.complete()));
            }
        }
        return results;
    }

    private static List<ParallelResult> measureParallel(Config config, float[] samples) throws Exception {
        List<ParallelResult> results = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(config.parallelism());
        try {
            List<SmartTurnV3> models = new ArrayList<>();
            for (int worker = 0; worker < config.parallelism(); worker++) {
                models.add(new SmartTurnV3(config.modelPath()));
            }
            try {
                for (int iteration = 1; iteration <= config.iterations(); iteration++) {
                    CountDownLatch startGate = new CountDownLatch(1);
                    List<Future<WorkerResult>> futures = new ArrayList<>();
                    for (int worker = 0; worker < config.parallelism(); worker++) {
                        SmartTurnV3 model = models.get(worker);
                        int workerIndex = worker + 1;
                        Callable<WorkerResult> task = () -> {
                            startGate.await();
                            long started = System.nanoTime();
                            SmartTurnV3.Prediction prediction = model.predict(samples);
                            return new WorkerResult(
                                    workerIndex,
                                    System.nanoTime() - started,
                                    prediction.probability(),
                                    prediction.complete());
                        };
                        futures.add(executor.submit(task));
                    }

                    long wallStarted = System.nanoTime();
                    startGate.countDown();
                    List<WorkerResult> workerResults = new ArrayList<>();
                    for (Future<WorkerResult> future : futures) {
                        workerResults.add(future.get());
                    }
                    long wallElapsedNanos = System.nanoTime() - wallStarted;

                    for (WorkerResult workerResult : workerResults) {
                        results.add(new ParallelResult(
                                iteration,
                                workerResult.worker(),
                                workerResult.elapsedNanos(),
                                wallElapsedNanos,
                                workerResult.probability(),
                                workerResult.complete()));
                    }
                }
            } finally {
                for (SmartTurnV3 model : models) {
                    model.close();
                }
            }
        } finally {
            executor.shutdownNow();
        }
        return results;
    }

    private static void writeArtifacts(
            Config config,
            int sampleCount,
            List<SingleResult> singleResults,
            List<ParallelResult> parallelResults,
            SingleSummary singleSummary,
            ParallelSummary parallelSummary)
            throws IOException {
        Path singleCsv = config.outputDir().resolve("smartturnv3-single-results.csv");
        List<String> singleLines = new ArrayList<>();
        singleLines.add("iteration,elapsed_ms,probability,complete");
        for (SingleResult result : singleResults) {
            singleLines.add(String.format(Locale.ROOT, "%d,%.3f,%.6f,%s",
                    result.iteration(),
                    nanosToMillis(result.elapsedNanos()),
                    result.probability(),
                    result.complete()));
        }
        Files.write(singleCsv, singleLines, StandardCharsets.UTF_8);

        Path parallelCsv = config.outputDir().resolve("smartturnv3-parallel-results.csv");
        List<String> parallelLines = new ArrayList<>();
        parallelLines.add("iteration,worker,worker_elapsed_ms,batch_wall_ms,probability,complete");
        for (ParallelResult result : parallelResults) {
            parallelLines.add(String.format(Locale.ROOT, "%d,%d,%.3f,%.3f,%.6f,%s",
                    result.iteration(),
                    result.worker(),
                    nanosToMillis(result.workerElapsedNanos()),
                    nanosToMillis(result.batchWallNanos()),
                    result.probability(),
                    result.complete()));
        }
        Files.write(parallelCsv, parallelLines, StandardCharsets.UTF_8);

        Path markdown = config.outputDir().resolve("smartturnv3-benchmark-summary.md");
        Files.writeString(markdown, """
                # Smart Turn v3 Benchmark

                - measured_at: %s
                - model: `%s`
                - audio: `%s`
                - sample_rate: %d
                - audio_seconds: %.3f
                - samples: %d
                - warmup: %d
                - iterations: %d
                - parallelism: %d

                | mode | samples | avg_ms | p50_ms | p90_ms | p95_ms | min_ms | max_ms |
                |---|---:|---:|---:|---:|---:|---:|---:|
                | single | %d | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f |
                | parallel_worker | %d | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f |
                | parallel_batch_wall | %d | %.3f | %.3f | %.3f | %.3f | %.3f | %.3f |

                - single_probability_avg: %.6f
                - parallel_probability_avg: %.6f
                - estimate_single_budget_ms: %.3f
                - estimate_parallel_batch_budget_ms: %.3f
                """
                .formatted(
                        Instant.now(),
                        config.modelPath().toAbsolutePath().normalize(),
                        config.audioFile() == null ? "(synthetic)" : config.audioFile().toAbsolutePath().normalize(),
                        LogMelFeatureExtractor.SAMPLE_RATE,
                        sampleCount / (double) LogMelFeatureExtractor.SAMPLE_RATE,
                        sampleCount,
                        config.warmup(),
                        config.iterations(),
                        config.parallelism(),
                        singleSummary.count(), singleSummary.avgMillis(), singleSummary.p50Millis(),
                        singleSummary.p90Millis(), singleSummary.p95Millis(), singleSummary.minMillis(),
                        singleSummary.maxMillis(),
                        parallelSummary.workerCount(), parallelSummary.workerAvgMillis(), parallelSummary.workerP50Millis(),
                        parallelSummary.workerP90Millis(), parallelSummary.workerP95Millis(), parallelSummary.workerMinMillis(),
                        parallelSummary.workerMaxMillis(),
                        parallelSummary.batchCount(), parallelSummary.batchAvgMillis(), parallelSummary.batchP50Millis(),
                        parallelSummary.batchP90Millis(), parallelSummary.batchP95Millis(), parallelSummary.batchMinMillis(),
                        parallelSummary.batchMaxMillis(),
                        singleSummary.avgProbability(),
                        parallelSummary.avgProbability(),
                        singleSummary.p95Millis(),
                        parallelSummary.batchP95Millis()),
                StandardCharsets.UTF_8);
    }

    private static void printSummary(
            Config config,
            int sampleCount,
            SingleSummary singleSummary,
            ParallelSummary parallelSummary) {
        System.out.printf(Locale.ROOT, "model: %s%n", config.modelPath().toAbsolutePath().normalize());
        System.out.printf(Locale.ROOT, "audio: %s%n",
                config.audioFile() == null ? "(synthetic)" : config.audioFile().toAbsolutePath().normalize());
        System.out.printf(Locale.ROOT, "audio seconds: %.3f samples: %d%n",
                sampleCount / (double) LogMelFeatureExtractor.SAMPLE_RATE, sampleCount);
        System.out.printf(Locale.ROOT, "single avg: %.3f ms p50: %.3f ms p95: %.3f ms max: %.3f ms%n",
                singleSummary.avgMillis(), singleSummary.p50Millis(), singleSummary.p95Millis(),
                singleSummary.maxMillis());
        System.out.printf(Locale.ROOT, "parallel worker avg: %.3f ms p50: %.3f ms p95: %.3f ms max: %.3f ms%n",
                parallelSummary.workerAvgMillis(), parallelSummary.workerP50Millis(),
                parallelSummary.workerP95Millis(), parallelSummary.workerMaxMillis());
        System.out.printf(Locale.ROOT, "parallel batch wall avg: %.3f ms p50: %.3f ms p95: %.3f ms max: %.3f ms%n",
                parallelSummary.batchAvgMillis(), parallelSummary.batchP50Millis(),
                parallelSummary.batchP95Millis(), parallelSummary.batchMaxMillis());
        System.out.printf(Locale.ROOT, "artifacts: %s%n", config.outputDir().toAbsolutePath().normalize());
    }

    private static float[] syntheticSpeechLikeSamples(int seconds) {
        int sampleCount = LogMelFeatureExtractor.SAMPLE_RATE * seconds;
        float[] samples = new float[sampleCount];
        SplittableRandom random = new SplittableRandom(1234);
        for (int i = 0; i < samples.length; i++) {
            double t = i / (double) LogMelFeatureExtractor.SAMPLE_RATE;
            double envelope = 0.35 + 0.65 * Math.sin(Math.PI * i / Math.max(1, samples.length - 1));
            double voiced = Math.sin(2.0 * Math.PI * 180.0 * t)
                    + 0.45 * Math.sin(2.0 * Math.PI * 360.0 * t)
                    + 0.20 * Math.sin(2.0 * Math.PI * 720.0 * t);
            double noise = (random.nextDouble() * 2.0 - 1.0) * 0.015;
            samples[i] = (float) Math.max(-1.0, Math.min(1.0, envelope * voiced * 0.18 + noise));
        }
        return samples;
    }

    private static float[] loadWavPcm16Mono(Path path, int maxSeconds) throws IOException {
        byte[] wav = Files.readAllBytes(path);
        if (wav.length < 44) {
            throw new IllegalArgumentException("WAV file is too short: " + path);
        }
        ByteBuffer buffer = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt(0) != 0x46464952 || buffer.getInt(8) != 0x45564157) {
            throw new IllegalArgumentException("WAV must use RIFF/WAVE format: " + path);
        }

        int channels = -1;
        int sampleRate = -1;
        int bitsPerSample = -1;
        int dataOffset = -1;
        int dataSize = -1;
        int offset = 12;
        while (offset + 8 <= wav.length) {
            int chunkId = buffer.getInt(offset);
            int chunkSize = buffer.getInt(offset + 4);
            int chunkDataOffset = offset + 8;
            if (chunkSize < 0 || chunkDataOffset + chunkSize > wav.length) {
                throw new IllegalArgumentException("Invalid WAV chunk size in: " + path);
            }
            if (chunkId == 0x20746d66) {
                int audioFormat = Short.toUnsignedInt(buffer.getShort(chunkDataOffset));
                channels = Short.toUnsignedInt(buffer.getShort(chunkDataOffset + 2));
                sampleRate = buffer.getInt(chunkDataOffset + 4);
                bitsPerSample = Short.toUnsignedInt(buffer.getShort(chunkDataOffset + 14));
                if (audioFormat != 1) {
                    throw new IllegalArgumentException("WAV must be PCM format 1: " + path);
                }
            } else if (chunkId == 0x61746164) {
                dataOffset = chunkDataOffset;
                dataSize = chunkSize;
            }
            offset = chunkDataOffset + chunkSize + (chunkSize & 1);
        }

        if (channels != 1 || sampleRate != LogMelFeatureExtractor.SAMPLE_RATE || bitsPerSample != 16) {
            throw new IllegalArgumentException("WAV must be mono PCM16LE 16000 Hz. actual channels="
                    + channels + " sampleRate=" + sampleRate + " bits=" + bitsPerSample);
        }
        if (dataOffset < 0) {
            throw new IllegalArgumentException("WAV data chunk is not found: " + path);
        }

        int maxBytes = LogMelFeatureExtractor.SAMPLE_RATE * maxSeconds * 2;
        int bytes = Math.min(dataSize, maxBytes);
        float[] samples = new float[bytes / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = buffer.getShort(dataOffset + i * 2) / 32768.0f;
        }
        return samples;
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static double percentile(List<Double> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0.0;
        }
        int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
        return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
    }

    private record Config(
            Path modelPath,
            Path audioFile,
            int audioSeconds,
            int iterations,
            int warmup,
            int parallelism,
            Path outputDir) {
        static Config parse(String[] args) {
            Path modelPath = SmartTurnV3ModelDownloader.MODEL_PATH;
            Path audioFile = null;
            int audioSeconds = DEFAULT_AUDIO_SECONDS;
            int iterations = DEFAULT_ITERATIONS;
            int warmup = DEFAULT_WARMUP;
            int parallelism = DEFAULT_PARALLELISM;
            Path outputDir = DEFAULT_OUTPUT_DIR;

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--model" -> modelPath = Path.of(requireValue(args, ++i, arg));
                    case "--audio" -> audioFile = Path.of(requireValue(args, ++i, arg));
                    case "--audio-seconds" -> audioSeconds = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--iterations" -> iterations = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--warmup" -> warmup = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--parallelism" -> parallelism = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--output-dir" -> outputDir = Path.of(requireValue(args, ++i, arg));
                    case "--help" -> {
                        printHelp();
                        System.exit(0);
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            if (audioSeconds <= 0 || audioSeconds > DEFAULT_AUDIO_SECONDS) {
                throw new IllegalArgumentException("--audio-seconds must be 1.." + DEFAULT_AUDIO_SECONDS);
            }
            if (iterations <= 0) {
                throw new IllegalArgumentException("--iterations must be greater than 0");
            }
            if (warmup < 0) {
                throw new IllegalArgumentException("--warmup must be greater than or equal to 0");
            }
            if (parallelism <= 0) {
                throw new IllegalArgumentException("--parallelism must be greater than 0");
            }
            return new Config(modelPath, audioFile, audioSeconds, iterations, warmup, parallelism, outputDir);
        }

        private static String requireValue(String[] args, int index, String name) {
            if (index >= args.length) {
                throw new IllegalArgumentException(name + " requires a value");
            }
            return args[index];
        }

        private static void printHelp() {
            System.out.println("""
                    Usage: java audio.vad.smartturn.SmartTurnV3Benchmark [options]
                      --model <path>          default: .local/opt/smart-turn-v3/models/smart-turn-v3.1-cpu.onnx
                      --audio <path>          optional mono PCM16LE 16000 Hz WAV. omitted: synthetic 6 second audio
                      --audio-seconds <1..6>  default: 6
                      --iterations <count>    default: 10
                      --warmup <count>        default: 2
                      --parallelism <count>   default: 3
                      --output-dir <path>     default: tmp/smartturnv3-benchmark
                    """);
        }
    }

    private record SingleResult(int iteration, long elapsedNanos, float probability, boolean complete) {
    }

    private record ParallelResult(
            int iteration,
            int worker,
            long workerElapsedNanos,
            long batchWallNanos,
            float probability,
            boolean complete) {
    }

    private record WorkerResult(int worker, long elapsedNanos, float probability, boolean complete) {
    }

    private record SingleSummary(
            int count,
            double avgMillis,
            double p50Millis,
            double p90Millis,
            double p95Millis,
            double minMillis,
            double maxMillis,
            double avgProbability) {
        static SingleSummary of(List<SingleResult> results) {
            List<Double> elapsed = results.stream()
                    .map(result -> nanosToMillis(result.elapsedNanos()))
                    .sorted()
                    .toList();
            return new SingleSummary(
                    results.size(),
                    elapsed.stream().mapToDouble(Double::doubleValue).average().orElse(0.0),
                    percentile(elapsed, 0.50),
                    percentile(elapsed, 0.90),
                    percentile(elapsed, 0.95),
                    elapsed.stream().mapToDouble(Double::doubleValue).min().orElse(0.0),
                    elapsed.stream().mapToDouble(Double::doubleValue).max().orElse(0.0),
                    results.stream().mapToDouble(SingleResult::probability).average().orElse(0.0));
        }
    }

    private record ParallelSummary(
            int workerCount,
            int batchCount,
            double workerAvgMillis,
            double workerP50Millis,
            double workerP90Millis,
            double workerP95Millis,
            double workerMinMillis,
            double workerMaxMillis,
            double batchAvgMillis,
            double batchP50Millis,
            double batchP90Millis,
            double batchP95Millis,
            double batchMinMillis,
            double batchMaxMillis,
            double avgProbability) {
        static ParallelSummary of(List<ParallelResult> results) {
            List<Double> workerElapsed = results.stream()
                    .map(result -> nanosToMillis(result.workerElapsedNanos()))
                    .sorted()
                    .toList();
            List<Double> batchElapsed = results.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            ParallelResult::iteration,
                            java.util.stream.Collectors.collectingAndThen(
                                    java.util.stream.Collectors.maxBy(Comparator.comparingLong(ParallelResult::batchWallNanos)),
                                    value -> nanosToMillis(value.orElseThrow().batchWallNanos()))))
                    .values()
                    .stream()
                    .sorted()
                    .toList();
            return new ParallelSummary(
                    results.size(),
                    batchElapsed.size(),
                    workerElapsed.stream().mapToDouble(Double::doubleValue).average().orElse(0.0),
                    percentile(workerElapsed, 0.50),
                    percentile(workerElapsed, 0.90),
                    percentile(workerElapsed, 0.95),
                    workerElapsed.stream().mapToDouble(Double::doubleValue).min().orElse(0.0),
                    workerElapsed.stream().mapToDouble(Double::doubleValue).max().orElse(0.0),
                    batchElapsed.stream().mapToDouble(Double::doubleValue).average().orElse(0.0),
                    percentile(batchElapsed, 0.50),
                    percentile(batchElapsed, 0.90),
                    percentile(batchElapsed, 0.95),
                    batchElapsed.stream().mapToDouble(Double::doubleValue).min().orElse(0.0),
                    batchElapsed.stream().mapToDouble(Double::doubleValue).max().orElse(0.0),
                    results.stream().mapToDouble(ParallelResult::probability).average().orElse(0.0));
        }
    }
}
