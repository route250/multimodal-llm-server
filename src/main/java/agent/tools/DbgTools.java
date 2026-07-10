package agent.tools;

import json.Json;
import json.JsonFields;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * OpenAI Chat Completions 互換エンドポイントで、通常応答と tool call の有無を確認するデバッグ用クラスです。
 */
public class DbgTools {
    private static final URI DEFAULT_ENDPOINT = URI.create("http://127.0.0.1:8767/v1/chat/completions");
    private static final String DEFAULT_MODEL = "LFM2.5-1.2B-JP-202606-GGUF";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private final HttpClient httpClient;
    private final URI endpoint;
    private final String model;
    private final Duration timeout;

    public DbgTools() {
        this(
                URI.create(env("LFM2_CHAT_COMPLETIONS_URL", DEFAULT_ENDPOINT.toString())),
                env("LFM2_CHAT_COMPLETIONS_MODEL", DEFAULT_MODEL),
                Duration.ofSeconds(Long.parseLong(env(
                        "LFM2_CHAT_COMPLETIONS_TIMEOUT_SECONDS",
                        Long.toString(DEFAULT_TIMEOUT.toSeconds())))));
    }

    DbgTools(URI endpoint, String model, Duration timeout) {
        this.endpoint = endpoint;
        this.model = requireText(model, "model");
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public static void main(String[] args) {
        DbgTools tools = new DbgTools();
        tools.runPlainCompletion();
        tools.runToolCallCompletion();
    }

    /**
     * tool 定義なしで、通常の chat completion 応答が返るか確認します。
     */
    public void runPlainCompletion() {
        CompletionResult result = requestStreaming("""
                {"model":"%s","messages":[{"role":"system","content":"日本語で短く答えてください。"},{"role":"user","content":"こんにちは。今日は何ができますか？"}],"temperature":0,"stream":true}
                """.formatted(Json.escape(model)));

        System.out.println("plain_content=" + result.content());
        System.out.println("plain_tool_call_detected=" + result.toolCallDetected());
    }

    /**
     * current_time tool を指定し、サーバが tool_calls を返すか確認します。
     */
    public void runToolCallCompletion() {
        String firstResponse = requestJson("""
                {
                  "model":"%s",
                  "messages":[
                    {"role":"user","content":"現在時刻を調べてください。"}
                  ],
                  "tools":[
                    {
                      "type":"function",
                      "function":{
                        "name":"current_time",
                        "description":"現在時刻を ISO 8601 形式と Unix epoch 秒で返します。",
                        "parameters":{
                          "type":"object",
                          "properties":{},
                          "additionalProperties":false
                        }
                      }
                    }
                  ],
                  "tool_choice":{"type":"function","function":{"name":"current_time"}},
                  "temperature":0,
                  "stream":false
                }
                """.formatted(Json.escape(model)));

        System.out.println("tool_first_response=" + firstResponse);
        ToolCall toolCall = parseToolCall(firstResponse);
        if (toolCall == null) {
            System.out.println("tool_call_detected=false");
            return;
        }

        String toolOutput = currentTime();
        System.out.println("tool_call_detected=true");
        System.out.println("tool_call_name=" + toolCall.name());
        System.out.println("tool_call_arguments=" + toolCall.arguments());
        System.out.println("tool_output=" + toolOutput);

        String secondResponse = requestJson("""
                {
                  "model":"%s",
                  "messages":[
                    {"role":"user","content":"現在時刻を調べてください。"},
                    {
                      "role":"assistant",
                      "content":"",
                      "tool_calls":[
                        {
                          "id":"%s",
                          "type":"function",
                          "function":{"name":"%s","arguments":"%s"}
                        }
                      ]
                    },
                    {"role":"tool","tool_call_id":"%s","content":"%s"}
                  ],
                  "temperature":0,
                  "stream":false
                }
                """.formatted(
                Json.escape(model),
                Json.escape(toolCall.id()),
                Json.escape(toolCall.name()),
                Json.escape(toolCall.arguments()),
                Json.escape(toolCall.id()),
                Json.escape(toolOutput)));
        System.out.println("tool_second_response=" + secondResponse);
    }

    private CompletionResult requestStreaming(String requestBody) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException("chat completions request failed: HTTP "
                        + response.statusCode() + " " + errorBody);
            }
            return readStreamingResponse(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("failed to request chat completions at " + endpoint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while requesting chat completions at " + endpoint, e);
        }
    }

    private String requestJson(String requestBody) {
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("chat completions request failed: HTTP "
                        + response.statusCode() + " " + response.body());
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("failed to request chat completions at " + endpoint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while requesting chat completions at " + endpoint, e);
        }
    }

    private static CompletionResult readStreamingResponse(InputStream body) throws IOException {
        StringBuilder content = new StringBuilder();
        boolean toolCallDetected = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) {
                    continue;
                }
                String data = line.substring("data: ".length());
                if ("[DONE]".equals(data)) {
                    break;
                }
                toolCallDetected = toolCallDetected || data.contains("\"tool_calls\"");
                JsonFields.string(data, "content", 0).ifPresent(content::append);
            }
        }
        return new CompletionResult(content.toString(), toolCallDetected);
    }

    private static String currentTime() {
        OffsetDateTime now = OffsetDateTime.now(ZoneId.systemDefault());
        return """
                {"iso_offset_datetime":"%s","epoch_second":%d,"zone":"%s"}
                """.formatted(
                Json.escape(now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)),
                now.toEpochSecond(),
                Json.escape(ZoneId.systemDefault().getId())).strip();
    }

    private static ToolCall parseToolCall(String responseBody) {
        int toolCalls = responseBody.indexOf("\"tool_calls\"");
        if (toolCalls < 0) {
            return null;
        }
        String id = JsonFields.stringOrNull(responseBody, "id", toolCalls);
        String name = JsonFields.stringOrNull(responseBody, "name", toolCalls);
        String arguments = JsonFields.stringOrDefault(responseBody, "arguments", "{}");
        if (id == null || name == null) {
            return null;
        }
        return new ToolCall(id, name, arguments);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private static String env(String name, String defaultValue) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        return value;
    }

    private record CompletionResult(String content, boolean toolCallDetected) {
    }

    private record ToolCall(String id, String name, String arguments) {
    }
}
