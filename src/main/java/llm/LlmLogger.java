package llm;

import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import json.Json;

/**
 * LlmOpenAI が Responses API へ送受信する内容を、調査用にファイル保存します。
 * API キーおよび Authorization ヘッダーは保存しません。
 */
final class LlmLogger {
    private static final int RETAIN_EXCHANGES = 10;
    private static final AtomicLong SEQUENCE = new AtomicLong();
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final DateTimeFormatter JSON_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");

    private LlmLogger() {
    }

    static Exchange saveRequest(
            URI endpoint,
            String model,
            boolean reasoning,
            List<Message> messages,
            List<LLM.Tool> tools,
            List<ToolResult> toolResults) {
        try {
            Path directory = root().resolve(exchangeDirectoryName());
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("request.json"), prettyJson(Json.object(Json.fields(
                    "timestamp", timestamp(),
                    "endpoint", endpoint.toString(),
                    "body", Json.raw(requestBody(model, reasoning, messages, tools, toolResults))))), StandardCharsets.UTF_8);
            cleanupOldExchanges();
            return new Exchange(directory, endpoint);
        } catch (RuntimeException | IOException e) {
            return Exchange.disabled(endpoint);
        }
    }

    private static String requestBody(
            String model,
            boolean reasoning,
            List<Message> messages,
            List<LLM.Tool> tools,
            List<ToolResult> toolResults) {
        StringBuilder body = new StringBuilder("{\"model\":").append(Json.string(model));
        body.append(",\"input\":").append(inputJson(messages, toolResults));
        body.append(",\"stream\":true");
        body.append(",\"reasoning\":{\"effort\":").append(Json.string(reasoning ? "medium" : "none")).append("}");
        if (!tools.isEmpty()) {
            body.append(",\"tools\":").append(toolsJson(tools));
        }
        return body.append("}").toString();
    }

    private static String inputJson(List<Message> messages, List<ToolResult> toolResults) {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        for (int i = 0; i < messages.size(); i++) {
            if (!first) json.append(",");
            first = false;
            Message message = messages.get(i);
            message.toJson(json);
        }
        for (ToolResult result : toolResults) {
            if (!first) json.append(",");
            first = false;
            json.append("{\"type\":\"function_call\",\"id\":").append(Json.string(result.id()))
                    .append(",\"call_id\":").append(Json.string(result.callId()))
                    .append(",\"name\":").append(Json.string(result.name()))
                    .append(",\"arguments\":").append(Json.string(result.arguments())).append("}");
            json.append(",{\"type\":\"function_call_output\",\"call_id\":")
                    .append(Json.string(result.callId())).append(",\"output\":")
                    .append(Json.string(result.output())).append("}");
        }
        return json.append("]").toString();
    }

    private static String toolsJson(List<LLM.Tool> tools) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < tools.size(); i++) {
            if (i > 0) json.append(",");
            LLM.Tool tool = tools.get(i);
            FunctionTool definition = tool.definiton();
            json.append("{\"type\":\"function\",\"name\":").append(Json.string(tool.name))
                    .append(",\"description\":").append(Json.string(tool.description))
                    .append(",\"parameters\":")
                    .append(definition.parameters().map(LlmLogger::parametersJson).orElse("{}"))
                    .append("}");
        }
        return json.append("]").toString();
    }

    private static String parametersJson(FunctionTool.Parameters parameters) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonValue> entry : parameters._additionalProperties().entrySet()) {
            values.put(entry.getKey(), entry.getValue().convert(Object.class));
        }
        return jsonValue(values);
    }

    @SuppressWarnings("unchecked")
    private static String jsonValue(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return Json.string(text);
        if (value instanceof Number || value instanceof Boolean) return String.valueOf(value);
        if (value instanceof Map<?, ?> map) {
            StringBuilder json = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) json.append(",");
                first = false;
                json.append(Json.string(String.valueOf(entry.getKey()))).append(":").append(jsonValue(entry.getValue()));
            }
            return json.append("}").toString();
        }
        if (value instanceof List<?> values) {
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < values.size(); i++) {
                if (i > 0) json.append(",");
                json.append(jsonValue(values.get(i)));
            }
            return json.append("]").toString();
        }
        return Json.string(String.valueOf(value));
    }

    private static Path root() {
        return Path.of(System.getProperty("llm.logger.dir", "tmp/llm-log"));
    }

    private static String timestamp() {
        return OffsetDateTime.now().format(JSON_TIMESTAMP);
    }

    private static String exchangeDirectoryName() {
        return "%s-%04d".formatted(LocalDateTime.now().format(FILE_TIMESTAMP), SEQUENCE.incrementAndGet());
    }

    private static synchronized void cleanupOldExchanges() throws IOException {
        Path root = root();
        if (!Files.isDirectory(root)) return;
        try (var paths = Files.list(root)) {
            List<Path> directories = paths.filter(Files::isDirectory)
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed()).toList();
            for (int i = RETAIN_EXCHANGES; i < directories.size(); i++) deleteRecursively(directories.get(i));
        }
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private static String prettyJson(String compactJson) {
        StringBuilder formatted = new StringBuilder(compactJson.length() + 128);
        int indent = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < compactJson.length(); i++) {
            char character = compactJson.charAt(i);
            if (inString) {
                formatted.append(character);
                if (escaped) escaped = false;
                else if (character == '\\') escaped = true;
                else if (character == '"') inString = false;
                continue;
            }
            switch (character) {
                case '"' -> { inString = true; formatted.append(character); }
                case '{', '[' -> { formatted.append(character).append('\n'); appendIndent(formatted, ++indent); }
                case '}', ']' -> { trimTrailingWhitespace(formatted); formatted.append('\n'); appendIndent(formatted, --indent); formatted.append(character); }
                case ',' -> { formatted.append(character).append('\n'); appendIndent(formatted, indent); }
                case ':' -> formatted.append(": ");
                default -> { if (!Character.isWhitespace(character)) formatted.append(character); }
            }
        }
        trimTrailingWhitespace(formatted);
        return formatted.append('\n').toString();
    }

    private static void appendIndent(StringBuilder text, int indent) {
        text.append("  ".repeat(Math.max(0, indent)));
    }

    private static void trimTrailingWhitespace(StringBuilder text) {
        while (!text.isEmpty() && Character.isWhitespace(text.charAt(text.length() - 1))) text.setLength(text.length() - 1);
    }

    /** 後続リクエストへ追加する、モデルのツール呼び出しと実行結果です。 */
    record ToolResult(String id, String callId, String name, String arguments, String output) {
    }

    static final class Exchange {
        private final Path directory;
        private final URI endpoint;
        private final List<String> events = new ArrayList<>();
        private final StringBuilder text = new StringBuilder();
        private String error;

        private Exchange(Path directory, URI endpoint) {
            this.directory = directory;
            this.endpoint = endpoint;
        }

        private static Exchange disabled(URI endpoint) {
            return new Exchange(null, endpoint);
        }

        void textDelta(String delta) {
            events.add(Json.object(Json.fields("type", "response.output_text.delta", "delta", delta)));
            text.append(delta);
        }

        void toolCall(String name, String callId, String arguments) {
            events.add(Json.object(Json.fields("type", "response.function_call", "name", name, "callId", callId, "arguments", arguments)));
        }

        void toolOutput(String callId, String output) {
            events.add(Json.object(Json.fields("type", "function_call_output", "callId", callId, "output", output)));
        }

        void error(Exception exception) {
            error = exception.toString();
        }

        void saveResponse() {
            if (directory == null) return;
            try {
                StringBuilder response = new StringBuilder("{\"timestamp\":").append(Json.string(timestamp()))
                        .append(",\"endpoint\":").append(Json.string(endpoint.toString())).append(",\"events\":[");
                for (int i = 0; i < events.size(); i++) {
                    if (i > 0) response.append(",");
                    response.append(events.get(i));
                }
                response.append("]");
                if (error != null) response.append(",\"error\":").append(Json.string(error));
                response.append("}");
                Files.writeString(directory.resolve("response.json"), prettyJson(response.toString()), StandardCharsets.UTF_8);
                Files.writeString(directory.resolve("response.txt"), text, StandardCharsets.UTF_8);
                cleanupOldExchanges();
            } catch (RuntimeException | IOException ignored) {
                // ログ保存の失敗で LLM 呼び出しを失敗させない。
            }
        }
    }
}
