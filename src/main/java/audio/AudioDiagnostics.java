package audio;

import json.Json;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;

/**
 * 音声処理の診断ログと、STT 入力 WAV を tmp 配下へ保存する。
 */
public final class AudioDiagnostics {
    private static final int SAMPLE_RATE = 16_000;
    private static final int CHANNELS = 1;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int VAD_FRAME_SAMPLES = 256;
    private static final Path ROOT = Path.of("tmp", "audio-debug");
    private static final Path LOG_FILE = ROOT.resolve("audio-debug.log");
    private static final Path WAV_DIR = ROOT.resolve("wav");
    private static final DateTimeFormatter LOG_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");

    private AudioDiagnostics() {
    }

    public static Path root() {
        return ROOT;
    }

    public static Path logFile() {
        return LOG_FILE;
    }

    public static Path wavDir() {
        return WAV_DIR;
    }

    public static Context context(String groupId, String sessionId) {
        return new Context(groupId, sessionId);
    }

    /**
     * サーバ起動時に前回実行分の診断ログと WAV ファイルを削除する。
     */
    public static synchronized void clearOnStartup() {
        try {
            Files.createDirectories(ROOT);
            Files.createDirectories(WAV_DIR);
            try (var paths = Files.list(WAV_DIR)) {
                paths.filter(Files::isRegularFile).forEach(AudioDiagnostics::deleteQuietly);
            }
            Files.writeString(
                    LOG_FILE,
                    "",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
        } catch (RuntimeException | IOException e) {
            appendDiagnosticsError("clear-on-startup", e);
        }
    }

    public static void log(String event, Context context, Map<String, Object> fields) {
        Map<String, Object> line = Json.fields();
        line.put("timestamp", OffsetDateTime.now().format(LOG_TIMESTAMP));
        line.put("event", event);
        if (context != null) {
            putIfPresent(line, "groupId", context.groupId());
            putIfPresent(line, "sessionId", context.sessionId());
        }
        if (fields != null) {
            fields.forEach((key, value) -> putIfPresent(line, key, value));
        }
        try {
            appendJsonLine(line);
        } catch (RuntimeException | IOException e) {
            appendDiagnosticsError(event, e);
        }
    }

    public static Optional<SavedAudioFiles> saveWav(
            Context context,
            long speechSequenceId,
            String kind,
            long startSampleIndex,
            long endSampleIndexExclusive,
            AudioBuffer audioBuffer) {
        try {
            Files.createDirectories(WAV_DIR);
            Path wavPath = WAV_DIR.resolve(wavFileName(
                    context,
                    speechSequenceId,
                    kind,
                    startSampleIndex,
                    endSampleIndexExclusive));
            Files.write(wavPath, wav(audioBuffer, startSampleIndex, endSampleIndexExclusive));
            Path vadPath = vadPath(wavPath);
            Files.writeString(
                    vadPath,
                    vadCsv(audioBuffer, startSampleIndex, endSampleIndexExclusive),
                    StandardCharsets.UTF_8);
            return Optional.of(new SavedAudioFiles(
                    wavPath.toAbsolutePath().normalize(),
                    vadPath.toAbsolutePath().normalize()));
        } catch (RuntimeException | IOException e) {
            log("diagnostics-error", context, Json.fields(
                    "speechSequenceId", speechSequenceId,
                    "kind", kind,
                    "startSampleIndex", startSampleIndex,
                    "endSampleIndexExclusive", endSampleIndexExclusive,
                    "errorClass", e.getClass().getName(),
                    "errorMessage", e.getMessage()));
            return Optional.empty();
        }
    }

    private static Path vadPath(Path wavPath) {
        String fileName = wavPath.getFileName().toString();
        int extensionStart = fileName.lastIndexOf('.');
        String baseName = extensionStart < 0 ? fileName : fileName.substring(0, extensionStart);
        return wavPath.resolveSibling(baseName + ".vad.csv");
    }

    private static void appendDiagnosticsError(String originalEvent, Exception error) {
        Map<String, Object> line = Json.fields();
        line.put("timestamp", OffsetDateTime.now().format(LOG_TIMESTAMP));
        line.put("event", "diagnostics-error");
        line.put("errorClass", error.getClass().getName());
        line.put("errorMessage", "failed to log event " + originalEvent + ": " + error.getMessage());
        try {
            appendJsonLine(line);
        } catch (RuntimeException | IOException ignored) {
            // 診断ログの失敗で音声処理を止めない。
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            appendDiagnosticsError("delete-wav-on-startup", e);
        }
    }

    private static synchronized void appendJsonLine(Map<String, Object> line) throws IOException {
        Files.createDirectories(ROOT);
        Files.writeString(
                LOG_FILE,
                Json.object(line) + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }

    private static String wavFileName(
            Context context,
            long speechSequenceId,
            String kind,
            long startSampleIndex,
            long endSampleIndexExclusive) {
        String timestamp = LocalDateTime.now().format(FILE_TIMESTAMP);
        String groupId = context == null ? "" : sanitizeFilePart(context.groupId());
        String sessionId = context == null ? "" : sanitizeFilePart(context.sessionId());
        return "%s_%s_%s_%d_%s_%d-%d.wav".formatted(
                timestamp,
                groupId,
                sessionId,
                speechSequenceId,
                sanitizeFilePart(kind),
                startSampleIndex,
                endSampleIndexExclusive);
    }

    private static String sanitizeFilePart(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        StringBuilder sanitized = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') {
                sanitized.append(c);
            } else {
                sanitized.append('_');
            }
        }
        return sanitized.toString();
    }

    private static byte[] wav(AudioBuffer audioBuffer, long startSampleIndex, long endSampleIndexExclusive) {
        if (!audioBuffer.contains(startSampleIndex, endSampleIndexExclusive)) {
            throw new IllegalArgumentException("audio range is no longer available");
        }

        int samples = Math.toIntExact(endSampleIndexExclusive - startSampleIndex);
        int dataBytes = samples * Short.BYTES;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt(36 + dataBytes);
        header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) CHANNELS);
        header.putInt(SAMPLE_RATE);
        header.putInt(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8);
        header.putShort((short) (CHANNELS * BITS_PER_SAMPLE / 8));
        header.putShort((short) BITS_PER_SAMPLE);
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt(dataBytes);

        ByteBuffer body = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        body.put(header.array());
        for (long sampleIndex = startSampleIndex; sampleIndex < endSampleIndexExclusive; sampleIndex++) {
            body.putShort(audioBuffer.sampleAt(sampleIndex));
        }
        return body.array();
    }

    private static String vadCsv(AudioBuffer audioBuffer, long startSampleIndex, long endSampleIndexExclusive) {
        StringBuilder csv = new StringBuilder();
        csv.append("startSampleIndex,endSampleIndexExclusive,vadValue\n");
        long frameStart = alignToFrameStart(startSampleIndex);
        for (long sampleIndex = frameStart; sampleIndex < endSampleIndexExclusive; sampleIndex += VAD_FRAME_SAMPLES) {
            long frameEnd = Math.min(sampleIndex + VAD_FRAME_SAMPLES, endSampleIndexExclusive);
            int value = audioBuffer.vadValue(sampleIndex);
            csv.append(sampleIndex)
                    .append(',')
                    .append(frameEnd)
                    .append(',');
            if (value >= 0) {
                csv.append(value);
            }
            csv.append('\n');
        }
        return csv.toString();
    }

    private static long alignToFrameStart(long sampleIndex) {
        return Math.floorDiv(sampleIndex, VAD_FRAME_SAMPLES) * VAD_FRAME_SAMPLES;
    }

    private static void putIfPresent(Map<String, Object> fields, String key, Object value) {
        if (value != null) {
            fields.put(key, value);
        }
    }

    public record Context(String groupId, String sessionId) {
        public static Context empty() {
            return new Context(null, null);
        }
    }

    public record SavedAudioFiles(Path wavPath, Path vadPath) {
    }
}
