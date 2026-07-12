package llm;

import json.Json;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LLM に送信した JSON と受信した応答を tmp 配下へ保存する診断用ユーティリティです。
 */
final class LlmDiagnostics {
    private static final Path ROOT = Path.of("tmp", "llm-debug");
    private static final int RETAIN_EXCHANGES = 10;
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final DateTimeFormatter JSON_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private LlmDiagnostics() {
    }

    static Exchange saveRequest(URI endpoint, String requestBody) {
        try {
            Files.createDirectories(ROOT);
            Path exchangeDir = ROOT.resolve(exchangeDirectoryName());
            Files.createDirectories(exchangeDir);
            Files.writeString(
                    exchangeDir.resolve("request.json"),
                    requestJson(endpoint, requestBody),
                    StandardCharsets.UTF_8);
            cleanupOldExchanges();
            return new Exchange(exchangeDir, endpoint);
        } catch (RuntimeException | IOException e) {
            return Exchange.disabled(endpoint);
        }
    }

    private static String requestJson(URI endpoint, String requestBody) {
        return prettyJson("""
                {"timestamp":"%s","endpoint":"%s","body":%s}
                """.formatted(
                Json.escape(OffsetDateTime.now().format(JSON_TIMESTAMP)),
                Json.escape(endpoint.toString()),
                requestBody));
    }

    private static String responseJson(URI endpoint, int statusCode, List<String> events, String errorBody) {
        StringBuilder json = new StringBuilder();
        json.append("""
                {"timestamp":"%s","endpoint":"%s","statusCode":%d,"events":[\
                """.formatted(
                Json.escape(OffsetDateTime.now().format(JSON_TIMESTAMP)),
                Json.escape(endpoint.toString()),
                statusCode));
        for (int i = 0; i < events.size(); i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append(events.get(i));
        }
        json.append("]");
        if (errorBody != null) {
            json.append(",\"errorBody\":\"").append(Json.escape(errorBody)).append("\"");
        }
        json.append("}");
        return prettyJson(json.toString());
    }

    private static String prettyJson(String compactJson) {
        StringBuilder pretty = new StringBuilder(compactJson.length() + 128);
        int indent = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < compactJson.length(); i++) {
            char c = compactJson.charAt(i);
            if (inString) {
                pretty.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            switch (c) {
                case '"':
                    inString = true;
                    pretty.append(c);
                    break;
                case '{', '[':
                    pretty.append(c).append('\n');
                    indent++;
                    appendIndent(pretty, indent);
                    break;
                case '}', ']':
                    trimTrailingWhitespace(pretty);
                    pretty.append('\n');
                    indent--;
                    appendIndent(pretty, indent);
                    pretty.append(c);
                    break;
                case ',':
                    pretty.append(c).append('\n');
                    appendIndent(pretty, indent);
                    break;
                case ':':
                    pretty.append(": ");
                    break;
                default:
                    if (!Character.isWhitespace(c)) {
                        pretty.append(c);
                    }
                    break;
            }
        }
        trimTrailingWhitespace(pretty);
        pretty.append('\n');
        return pretty.toString();
    }

    private static void appendIndent(StringBuilder json, int indent) {
        json.append("  ".repeat(Math.max(0, indent)));
    }

    private static void trimTrailingWhitespace(StringBuilder text) {
        while (!text.isEmpty() && Character.isWhitespace(text.charAt(text.length() - 1))) {
            text.setLength(text.length() - 1);
        }
    }

    private static String exchangeDirectoryName() {
        long sequence = SEQUENCE.incrementAndGet();
        return "%s-%04d".formatted(LocalDateTime.now().format(FILE_TIMESTAMP), sequence);
    }

    private static synchronized void cleanupOldExchanges() throws IOException {
        if (!Files.isDirectory(ROOT)) {
            return;
        }
        try (var paths = Files.list(ROOT)) {
            List<Path> exchangeDirs = paths
                    .filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .toList();
            for (int i = RETAIN_EXCHANGES; i < exchangeDirs.size(); i++) {
                deleteRecursively(exchangeDirs.get(i));
            }
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    static final class Exchange {
        private final Path directory;
        private final URI endpoint;

        private Exchange(Path directory, URI endpoint) {
            this.directory = directory;
            this.endpoint = endpoint;
        }

        private static Exchange disabled(URI endpoint) {
            return new Exchange(null, endpoint);
        }

        void saveResponse(int statusCode, List<String> events, String errorBody) {
            if (directory == null) {
                return;
            }
            try {
                Files.writeString(
                        directory.resolve("response.json"),
                        responseJson(endpoint, statusCode, events, errorBody),
                        StandardCharsets.UTF_8);
                cleanupOldExchanges();
            } catch (RuntimeException | IOException e) {
                // 診断ログの保存失敗で LLM 応答処理を止めない。
            }
        }
    }
}
