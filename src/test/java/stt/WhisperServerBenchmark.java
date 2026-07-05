package stt;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * whisper.cpp server の response_format=json、vtt、verbose_json を比較する簡易ベンチマーク。
 *
 * <p>実行例:
 * <pre>
 * mvn -q -DskipTests test-compile
 * java -cp target/test-classes:target/classes stt.WhisperServerBenchmark \
 *   --endpoint http://localhost:8767/inference \
 *   --audio src/test/test-data/voice_mosimosi.wav \
 *   --language ja \
 *   --iterations 10 \
 *   --warmup 2
 * </pre>
 */
public class WhisperServerBenchmark {
    private static final URI DEFAULT_ENDPOINT = URI.create("http://localhost:8767/inference");
    private static final Path DEFAULT_AUDIO_FILE = Path.of("src/test/test-data/voice_mosimosi.wav");
    private static final Pattern TEXT_FIELD = Pattern.compile("\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
    private static final Pattern WORD_OBJECT = Pattern.compile(
            "\\{\\s*\"word\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*,\\s*"
                    + "\"start\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*"
                    + "\"end\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*"
                    + "\"t_dtw\"\\s*:\\s*-?\\d+\\s*,\\s*"
                    + "\"probability\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)\\s*\\}");

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        if (!Files.isRegularFile(config.audioFile())) {
            throw new IllegalArgumentException("Audio file not found: " + config.audioFile().toAbsolutePath());
        }

        Files.createDirectories(config.outputDir());
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        byte[] audio = Files.readAllBytes(config.audioFile());

        // 初回ロードやキャッシュの影響を除くため、計測前に同じ順序でウォームアップする。
        for (int i = 0; i < config.warmup(); i++) {
            request(client, config.endpoint(), config.audioFile().getFileName().toString(), audio, Mode.JSON, config.language());
            request(client, config.endpoint(), config.audioFile().getFileName().toString(), audio, Mode.VTT, config.language());
            request(client, config.endpoint(), config.audioFile().getFileName().toString(), audio, Mode.VERBOSE_JSON, config.language());
        }

        List<Result> results = new ArrayList<>();
        for (int i = 1; i <= config.iterations(); i++) {
            results.add(measure(client, config, audio, i, Mode.JSON));
            results.add(measure(client, config, audio, i, Mode.VTT));
            results.add(measure(client, config, audio, i, Mode.VERBOSE_JSON));
        }

        Summary jsonSummary = Summary.of(Mode.JSON, results);
        Summary vttSummary = Summary.of(Mode.VTT, results);
        Summary verboseSummary = Summary.of(Mode.VERBOSE_JSON, results);
        writeArtifacts(config, results, jsonSummary, vttSummary, verboseSummary);
        printSummary(config, jsonSummary, vttSummary, verboseSummary);
    }

    private static Result measure(HttpClient client, Config config, byte[] audio, int iteration, Mode mode)
            throws IOException, InterruptedException {
        long started = System.nanoTime();
        Response response = request(client, config.endpoint(), config.audioFile().getFileName().toString(), audio, mode, config.language());
        long elapsedNanos = System.nanoTime() - started;
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("whisper-server returned HTTP "
                    + response.statusCode() + " for " + mode.formValue() + "\n" + response.body());
        }

        Path bodyFile = config.outputDir().resolve("whisper-bench-%02d-%s.json"
                .formatted(iteration, mode.artifactName()));
        Files.writeString(bodyFile, response.body(), StandardCharsets.UTF_8);

        return Result.from(mode, iteration, elapsedNanos, response.body().getBytes(StandardCharsets.UTF_8).length, response.body());
    }

    private static Response request(HttpClient client, URI endpoint, String filename, byte[] audio, Mode mode, String language)
            throws IOException, InterruptedException {
        String boundary = "----java-whisper-bench-" + UUID.randomUUID();
        List<FormPart> parts = new ArrayList<>();
        parts.add(FormPart.file("file", filename, "audio/wav", audio));
        if (!language.isBlank()) {
            parts.add(FormPart.field("language", language));
        }
        parts.add(FormPart.field("temperature", "0.0"));
        parts.add(FormPart.field("response_format", mode.formValue()));

        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(120))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(multipart(boundary, parts))
                .build();

        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return new Response(response.statusCode(), response.body());
        } catch (ConnectException e) {
            throw new IllegalStateException("whisper-server is not running at " + endpoint, e);
        }
    }

    private static HttpRequest.BodyPublisher multipart(String boundary, List<FormPart> parts) {
        List<byte[]> body = new ArrayList<>();
        byte[] newline = "\r\n".getBytes(StandardCharsets.UTF_8);
        for (FormPart part : parts) {
            body.add(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            body.add(part.header().getBytes(StandardCharsets.UTF_8));
            body.add(newline);
            body.add(part.body());
            body.add(newline);
        }
        body.add(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return HttpRequest.BodyPublishers.ofByteArrays(body);
    }

    private static void writeArtifacts(
            Config config,
            List<Result> results,
            Summary jsonSummary,
            Summary vttSummary,
            Summary verboseSummary)
            throws IOException {
        Path csv = config.outputDir().resolve("whisper-benchmark-results.csv");
        List<String> csvLines = new ArrayList<>();
        csvLines.add("iteration,mode,elapsed_ms,response_bytes,text_chars,word_count,word_probability_min,word_probability_avg");
        for (Result result : results) {
            csvLines.add(String.format(Locale.ROOT, "%d,%s,%.3f,%d,%d,%d,%s,%s",
                    result.iteration(),
                    result.mode().artifactName(),
                    result.elapsedMillis(),
                    result.responseBytes(),
                    result.textChars(),
                    result.wordCount(),
                    numberOrBlank(result.wordProbabilityMin()),
                    numberOrBlank(result.wordProbabilityAvg())));
        }
        Files.write(csv, csvLines, StandardCharsets.UTF_8);

        Path markdown = config.outputDir().resolve("whisper-benchmark-summary.md");
        Files.writeString(markdown, """
                # Whisper Server Benchmark

                - measured_at: %s
                - endpoint: `%s`
                - audio: `%s`
                - language: `%s`
                - warmup: %d
                - iterations_per_mode: %d

                | mode | samples | avg_ms | p50_ms | p95_ms | min_ms | max_ms | avg_response_bytes | avg_word_count | min_word_probability | avg_word_probability |
                |---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
                | json | %d | %.3f | %.3f | %.3f | %.3f | %.3f | %.1f | %.1f | %s | %s |
                | vtt | %d | %.3f | %.3f | %.3f | %.3f | %.3f | %.1f | %.1f | %s | %s |
                | verbose_json | %d | %.3f | %.3f | %.3f | %.3f | %.3f | %.1f | %.1f | %s | %s |
                """
                .formatted(
                        Instant.now(),
                        config.endpoint(),
                        config.audioFile().toAbsolutePath(),
                        config.language().isBlank() ? "(auto)" : config.language(),
                        config.warmup(),
                        config.iterations(),
                        jsonSummary.count(), jsonSummary.avgMillis(), jsonSummary.p50Millis(), jsonSummary.p95Millis(),
                        jsonSummary.minMillis(), jsonSummary.maxMillis(), jsonSummary.avgResponseBytes(),
                        jsonSummary.avgWordCount(), numberOrBlank(jsonSummary.minWordProbability()),
                        numberOrBlank(jsonSummary.avgWordProbability()),
                        vttSummary.count(), vttSummary.avgMillis(), vttSummary.p50Millis(), vttSummary.p95Millis(),
                        vttSummary.minMillis(), vttSummary.maxMillis(), vttSummary.avgResponseBytes(),
                        vttSummary.avgWordCount(), numberOrBlank(vttSummary.minWordProbability()),
                        numberOrBlank(vttSummary.avgWordProbability()),
                        verboseSummary.count(), verboseSummary.avgMillis(), verboseSummary.p50Millis(),
                        verboseSummary.p95Millis(), verboseSummary.minMillis(), verboseSummary.maxMillis(),
                        verboseSummary.avgResponseBytes(), verboseSummary.avgWordCount(),
                        numberOrBlank(verboseSummary.minWordProbability()),
                        numberOrBlank(verboseSummary.avgWordProbability())),
                StandardCharsets.UTF_8);
    }

    private static void printSummary(Config config, Summary jsonSummary, Summary vttSummary, Summary verboseSummary) {
        double vttOverhead = vttSummary.avgMillis() - jsonSummary.avgMillis();
        double vttOverheadPercent = jsonSummary.avgMillis() == 0.0 ? 0.0 : vttOverhead / jsonSummary.avgMillis() * 100.0;
        double verboseOverhead = verboseSummary.avgMillis() - jsonSummary.avgMillis();
        double verboseOverheadPercent = jsonSummary.avgMillis() == 0.0
                ? 0.0
                : verboseOverhead / jsonSummary.avgMillis() * 100.0;
        System.out.printf(Locale.ROOT, "endpoint: %s%n", config.endpoint());
        System.out.printf(Locale.ROOT, "audio: %s%n", config.audioFile().toAbsolutePath());
        System.out.printf(Locale.ROOT, "language: %s%n", config.language().isBlank() ? "(auto)" : config.language());
        System.out.printf(Locale.ROOT, "json avg: %.3f ms p50: %.3f ms p95: %.3f ms%n",
                jsonSummary.avgMillis(), jsonSummary.p50Millis(), jsonSummary.p95Millis());
        System.out.printf(Locale.ROOT, "vtt avg: %.3f ms p50: %.3f ms p95: %.3f ms%n",
                vttSummary.avgMillis(), vttSummary.p50Millis(), vttSummary.p95Millis());
        System.out.printf(Locale.ROOT, "verbose_json avg: %.3f ms p50: %.3f ms p95: %.3f ms%n",
                verboseSummary.avgMillis(), verboseSummary.p50Millis(), verboseSummary.p95Millis());
        System.out.printf(Locale.ROOT, "vtt - json: %.3f ms (%.2f%%)%n", vttOverhead, vttOverheadPercent);
        System.out.printf(Locale.ROOT, "verbose_json - json: %.3f ms (%.2f%%)%n", verboseOverhead, verboseOverheadPercent);
        System.out.printf(Locale.ROOT, "verbose_json words avg: %.1f min_probability: %s avg_probability: %s%n",
                verboseSummary.avgWordCount(),
                numberOrBlank(verboseSummary.minWordProbability()),
                numberOrBlank(verboseSummary.avgWordProbability()));
        System.out.printf(Locale.ROOT, "artifacts: %s%n", config.outputDir().toAbsolutePath());
    }

    private static String numberOrBlank(Double value) {
        return value == null ? "" : String.format(Locale.ROOT, "%.6f", value);
    }

    private static String extractText(String body) {
        Matcher matcher = TEXT_FIELD.matcher(body);
        if (matcher.find()) {
            return unescapeJsonString(matcher.group(1)).trim();
        }
        return body.trim();
    }

    private static List<WordTiming> extractWordTimings(String body) {
        List<WordTiming> words = new ArrayList<>();
        Matcher matcher = WORD_OBJECT.matcher(body);
        while (matcher.find()) {
            words.add(new WordTiming(
                    unescapeJsonString(matcher.group(1)),
                    Double.parseDouble(matcher.group(2)),
                    Double.parseDouble(matcher.group(3)),
                    Double.parseDouble(matcher.group(4))));
        }
        return words;
    }

    private static String unescapeJsonString(String value) {
        StringBuilder result = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch != '\\' || i + 1 >= value.length()) {
                result.append(ch);
                continue;
            }
            char escaped = value.charAt(++i);
            switch (escaped) {
                case '"' -> result.append('"');
                case '\\' -> result.append('\\');
                case '/' -> result.append('/');
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> {
                    if (i + 4 >= value.length()) {
                        result.append("\\u");
                        break;
                    }
                    String hex = value.substring(i + 1, i + 5);
                    result.append((char) Integer.parseInt(hex, 16));
                    i += 4;
                }
                default -> result.append(escaped);
            }
        }
        return result.toString();
    }

    private enum Mode {
        JSON("json", "json"),
        VTT("vtt", "vtt"),
        VERBOSE_JSON("verbose_json", "verbose-json");

        private final String formValue;
        private final String artifactName;

        Mode(String formValue, String artifactName) {
            this.formValue = formValue;
            this.artifactName = artifactName;
        }

        String formValue() {
            return formValue;
        }

        String artifactName() {
            return artifactName;
        }
    }

    private record Config(URI endpoint, Path audioFile, String language, int iterations, int warmup, Path outputDir) {
        static Config parse(String[] args) {
            URI endpoint = DEFAULT_ENDPOINT;
            Path audioFile = DEFAULT_AUDIO_FILE;
            String language = "ja";
            int iterations = 10;
            int warmup = 2;
            Path outputDir = Path.of("tmp", "whisper-benchmark");

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                switch (arg) {
                    case "--endpoint" -> endpoint = URI.create(requireValue(args, ++i, arg));
                    case "--audio" -> audioFile = Path.of(requireValue(args, ++i, arg));
                    case "--language" -> language = requireValue(args, ++i, arg);
                    case "--iterations" -> iterations = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--warmup" -> warmup = Integer.parseInt(requireValue(args, ++i, arg));
                    case "--output-dir" -> outputDir = Path.of(requireValue(args, ++i, arg));
                    case "--help" -> {
                        printHelp();
                        System.exit(0);
                    }
                    default -> throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }

            if (iterations <= 0) {
                throw new IllegalArgumentException("--iterations must be greater than 0");
            }
            if (warmup < 0) {
                throw new IllegalArgumentException("--warmup must be greater than or equal to 0");
            }
            return new Config(endpoint, audioFile, language, iterations, warmup, outputDir);
        }

        private static String requireValue(String[] args, int index, String name) {
            if (index >= args.length) {
                throw new IllegalArgumentException(name + " requires a value");
            }
            return args[index];
        }

        private static void printHelp() {
            System.out.println("""
                    Usage: java stt.WhisperServerBenchmark [options]
                      --endpoint <url>       default: http://localhost:8767/inference
                      --audio <path>         default: src/test/test-data/voice_mosimosi.wav
                      --language <code>      default: ja, empty string means auto-detect
                      --iterations <count>   default: 10
                      --warmup <count>       default: 2
                      --output-dir <path>    default: tmp/whisper-benchmark
                    """);
        }
    }

    private record Response(int statusCode, String body) {
    }

    private record FormPart(String name, String filename, String contentType, byte[] body) {
        static FormPart field(String name, String value) {
            return new FormPart(name, null, null, value.getBytes(StandardCharsets.UTF_8));
        }

        static FormPart file(String name, String filename, String contentType, byte[] body) {
            return new FormPart(name, filename, contentType, body);
        }

        String header() {
            StringBuilder header = new StringBuilder("Content-Disposition: form-data; name=\"")
                    .append(name)
                    .append('"');
            if (filename != null) {
                header.append("; filename=\"").append(filename).append('"');
            }
            header.append("\r\n");
            if (contentType != null) {
                header.append("Content-Type: ").append(contentType).append("\r\n");
            }
            return header.toString();
        }
    }

    private record WordTiming(String word, double start, double end, double probability) {
    }

    private record Result(
            Mode mode,
            int iteration,
            long elapsedNanos,
            int responseBytes,
            int textChars,
            int wordCount,
            Double wordProbabilityMin,
            Double wordProbabilityAvg) {
        static Result from(Mode mode, int iteration, long elapsedNanos, int responseBytes, String body) {
            String text = extractText(body);
            List<WordTiming> words = extractWordTimings(body);
            Double minProbability = words.isEmpty()
                    ? null
                    : words.stream().map(WordTiming::probability).min(Double::compareTo).orElseThrow();
            Double avgProbability = words.isEmpty()
                    ? null
                    : words.stream().mapToDouble(WordTiming::probability).average().orElseThrow();
            return new Result(
                    mode,
                    iteration,
                    elapsedNanos,
                    responseBytes,
                    text.length(),
                    words.size(),
                    minProbability,
                    avgProbability);
        }

        double elapsedMillis() {
            return elapsedNanos / 1_000_000.0;
        }
    }

    private record Summary(
            Mode mode,
            int count,
            double avgMillis,
            double p50Millis,
            double p95Millis,
            double minMillis,
            double maxMillis,
            double avgResponseBytes,
            double avgWordCount,
            Double minWordProbability,
            Double avgWordProbability) {
        static Summary of(Mode mode, List<Result> allResults) {
            List<Result> results = allResults.stream()
                    .filter(result -> result.mode() == mode)
                    .toList();
            List<Double> elapsed = results.stream()
                    .map(Result::elapsedMillis)
                    .sorted()
                    .toList();
            List<Double> probabilities = results.stream()
                    .filter(result -> result.wordProbabilityAvg() != null)
                    .map(Result::wordProbabilityAvg)
                    .toList();
            return new Summary(
                    mode,
                    results.size(),
                    elapsed.stream().mapToDouble(Double::doubleValue).average().orElse(0.0),
                    percentile(elapsed, 0.50),
                    percentile(elapsed, 0.95),
                    elapsed.stream().mapToDouble(Double::doubleValue).min().orElse(0.0),
                    elapsed.stream().mapToDouble(Double::doubleValue).max().orElse(0.0),
                    results.stream().mapToInt(Result::responseBytes).average().orElse(0.0),
                    results.stream().mapToInt(Result::wordCount).average().orElse(0.0),
                    results.stream()
                            .map(Result::wordProbabilityMin)
                            .filter(value -> value != null)
                            .min(Comparator.naturalOrder())
                            .orElse(null),
                    probabilities.isEmpty()
                            ? null
                            : probabilities.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
        }

        private static double percentile(List<Double> sortedValues, double percentile) {
            if (sortedValues.isEmpty()) {
                return 0.0;
            }
            int index = (int) Math.ceil(percentile * sortedValues.size()) - 1;
            return sortedValues.get(Math.max(0, Math.min(index, sortedValues.size() - 1)));
        }
    }
}
