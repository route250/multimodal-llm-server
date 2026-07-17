package llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpenAiResponsesLanguageModelTest {
    @Test
    void postsToResponsesEndpointAndReadsOutputText() throws Exception {
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        try (FakeServer server = new FakeServer(sse("""
                {"type":"response.output_text.delta","delta":"こんにちは"}
                """, """
                {"type":"response.output_text.delta","delta":"\\nどうぞ"}
                """), 200, path, body)) {
            OpenAiResponsesLanguageModel model = new OpenAiResponsesLanguageModel(new LLM.Config(
                    server.baseUri(),
                    "gemma[-_]?4[-]?e2b",
                    "日本語で答えてください。",
                    Duration.ofSeconds(5)));

            String response = model.respond("音声認識の結果");

            assertEquals("/v1/responses", path.get());
            assertTrue(body.get().contains("\"model\":\"gemma_4e2b-it-q4_k_m\""));
            assertTrue(body.get().contains("\"stream\":true"));
            assertTrue(body.get().contains("\"text\":\"音声認識の結果\""));
            assertEquals("こんにちは\nどうぞ", response);
            assertEquals(List.of("/v1/models", "/v1/responses"), server.paths());
        }
    }

    @Test
    void sendsOpenAiApiKeyAsBearerToken() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        try (FakeServer server = new FakeServer(sse("""
                {"type":"response.output_text.delta","delta":"ok"}
                """), 200, new AtomicReference<>(), new AtomicReference<>(), authorization)) {
            OpenAiResponsesLanguageModel model = new OpenAiResponsesLanguageModel(new LLM.Config(
                    server.baseUri(),
                    "gemma[-_]?4[-]?e2b",
                    "",
                    Duration.ofSeconds(5),
                    "test-key"));

            assertEquals("ok", model.respond("こんにちは"));
            assertEquals("Bearer test-key", authorization.get());
        }
    }

    @Test
    void verifiesModelSelectionAndResponse() throws Exception {
        try (FakeServer server = new FakeServer(sse("""
                {"type":"response.output_text.delta","delta":"OK"}
                """), 200, new AtomicReference<>(), new AtomicReference<>())) {
            OpenAiResponsesLanguageModel model = new OpenAiResponsesLanguageModel(new LLM.Config(
                    server.baseUri(), "gemma[-_]?4[-]?e2b", "", Duration.ofSeconds(5)));

            LLM.Verification verification = model.verify();

            assertEquals("gemma_4e2b-it-q4_k_m", verification.model());
            assertEquals("OK", verification.response());
            assertEquals(List.of("/v1/models", "/v1/responses"), server.paths());
        }
    }

    @Test
    void ignoresStreamingEventsThatAreNotOutputDeltas() throws Exception {
        try (FakeServer server = new FakeServer(sse("""
                {"type":"response.output_item.done","item":{"content":[{"type":"output_text","text":"これは差分ではない"}]}}
                """, """
                {"delta":"語順が逆でも","type":"response.output_text.delta"}
                """), 200, new AtomicReference<>(), new AtomicReference<>())) {
            OpenAiResponsesLanguageModel model = new OpenAiResponsesLanguageModel(new LLM.Config(
                    server.baseUri(),
                    "gemma[-_]?4[-]?e2b",
                    "",
                    Duration.ofSeconds(5)));

            assertEquals("語順が逆でも", model.respond("こんにちは"));
        }
    }

    @Test
    void separatesStreamingTextDeltasAndToolCalls() throws Exception {
        try (FakeServer server = new FakeServer(sse("""
                {"type":"response.output_text.delta","delta":"先に話します。"}
                """, """
                {"type":"response.output_item.added","item":{"id":"fc_1","arguments":"","call_id":"call_1","name":"assign_face_name","type":"function_call","status":"in_progress"}}
                """, """
                {"type":"response.function_call_arguments.delta","delta":"{\\"trackId\\":\\"","item_id":"fc_1"}
                """, """
                {"type":"response.function_call_arguments.delta","delta":"trak-000001\\",\\"name\\":\\"山田\\"}","item_id":"fc_1"}
                """, """
                {"type":"response.function_call_arguments.done","item_id":"fc_1","arguments":"{\\"trackId\\":\\"trak-000001\\",\\"name\\":\\"山田\\"}","name":"assign_face_name"}
                """, """
                {"type":"response.output_item.done","item":{"id":"fc_1","type":"function_call","status":"completed","arguments":"{\\"trackId\\":\\"trak-000001\\",\\"name\\":\\"山田\\"}","call_id":"call_1","name":"assign_face_name"}}
                """), 200, new AtomicReference<>(), new AtomicReference<>())) {
            OpenAiResponsesLanguageModel model = new OpenAiResponsesLanguageModel(new LLM.Config(
                    server.baseUri(),
                    "gemma[-_]?4[-]?e2b",
                    "",
                    Duration.ofSeconds(5)));
            StringBuilder text = new StringBuilder();
            List<ToolCall> toolCalls = new ArrayList<>();

            model.respondStreamingEvents(List.of(new ChatMessage("user", "顔を登録して")), new StreamingResponseHandler() {
                @Override
                public void onTextDelta(String delta) {
                    text.append(delta);
                }

                @Override
                public void onToolCall(ToolCall toolCall) {
                    toolCalls.add(toolCall);
                }
            });

            assertEquals("先に話します。", text.toString());
            assertEquals(1, toolCalls.size());
            assertEquals("fc_1", toolCalls.get(0).id());
            assertEquals("call_1", toolCalls.get(0).callId());
            assertEquals("assign_face_name", toolCalls.get(0).name());
            assertEquals("{\"trackId\":\"trak-000001\",\"name\":\"山田\"}", toolCalls.get(0).arguments());
        }
    }

    @Test
    void postsConversationHistoryToResponsesEndpoint() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        try (FakeServer server = new FakeServer(sse("""
                {"type":"response.output_text.delta","delta":"続きです"}
                """), 200, new AtomicReference<>(), body)) {
            OpenAiResponsesLanguageModel model = new OpenAiResponsesLanguageModel(new LLM.Config(
                    server.baseUri(),
                    "gemma[-_]?4[-]?e2b",
                    "日本語で答えてください。",
                    Duration.ofSeconds(5)));

            assertEquals("続きです", model.respond(List.of(
                    new ChatMessage("user", "私の名前は太郎です"),
                    new ChatMessage("assistant", "覚えました"),
                    new ChatMessage("user", "私の名前は何ですか"))));

            assertTrue(body.get().contains("\"role\":\"user\""));
            assertTrue(body.get().contains("\"type\":\"message\",\"role\":\"user\""));
            assertTrue(body.get().contains("\"text\":\"私の名前は太郎です\""));
            assertTrue(body.get().contains("\"role\":\"assistant\""));
            assertTrue(body.get().contains("\"type\":\"message\",\"role\":\"assistant\""));
            assertTrue(body.get().contains("\"type\":\"output_text\""));
            assertTrue(body.get().contains("\"text\":\"覚えました\""));
            assertTrue(body.get().contains("\"text\":\"私の名前は何ですか\""));
        }
    }

    @Test
    void postsToolsToResponsesEndpoint() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        try (FakeServer server = new FakeServer(sse("""
                {"type":"response.output_text.delta","delta":"確認します"}
                """), 200, new AtomicReference<>(), body)) {
            OpenAiResponsesLanguageModel model = new OpenAiResponsesLanguageModel(new LLM.Config(
                    server.baseUri(),
                    "gemma[-_]?4[-]?e2b",
                    "日本語で答えてください。",
                    Duration.ofSeconds(5)));

            model.respondStreamingEvents(
                    List.of(new ChatMessage("user", "私の名前は太郎です")),
                    List.of(assignFaceNameTool()),
                    new StreamingResponseHandler() {
                        @Override
                        public void onTextDelta(String delta) {
                        }
                    });

            assertTrue(body.get().contains("\"tools\":["));
            assertTrue(body.get().contains("\"type\":\"function\""));
            assertTrue(body.get().contains("\"name\":\"assign_face_name\""));
            assertTrue(body.get().contains("\"required\":[\"trackId\",\"name\"]"));
        }
    }

    @Test
    void postsFunctionCallOutputToResponsesEndpoint() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        try (FakeServer server = new FakeServer(sse("""
                {"type":"response.output_text.delta","delta":"登録しました"}
                """), 200, new AtomicReference<>(), body)) {
            OpenAiResponsesLanguageModel model = new OpenAiResponsesLanguageModel(new LLM.Config(
                    server.baseUri(),
                    "gemma[-_]?4[-]?e2b",
                    "日本語で答えてください。",
                    Duration.ofSeconds(5)));
            StringBuilder text = new StringBuilder();
            ToolCall toolCall = new ToolCall(
                    "fc_1",
                    "call_1",
                    "assign_face_name",
                    "{\"trackId\":\"trak-000001\",\"name\":\"太郎\"}");

            model.respondStreamingEvents(
                    List.of(new ChatMessage("user", "私の名前は太郎です")),
                    List.of(assignFaceNameTool()),
                    List.of(new ToolCallResult(toolCall, "{\"status\":\"ok\",\"trackId\":\"trak-000001\",\"name\":\"太郎\"}")),
                    new StreamingResponseHandler() {
                        @Override
                        public void onTextDelta(String delta) {
                            text.append(delta);
                        }
                    });

            assertEquals("登録しました", text.toString());
            assertTrue(body.get().contains("\"type\":\"function_call\""));
            assertTrue(body.get().contains("\"call_id\":\"call_1\""));
            assertTrue(body.get().contains("\"type\":\"function_call_output\""));
            assertTrue(body.get().contains("\"output\":\"{\\\"status\\\":\\\"ok\\\",\\\"trackId\\\":\\\"trak-000001\\\",\\\"name\\\":\\\"太郎\\\"}\""));
        }
    }

    @Test
    void returnsTextAndToolCallsWithoutStreaming() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        try (FakeServer server = new FakeServer("""
                {"output":[
                  {"type":"function_call","id":"fc_1","call_id":"call_1","name":"assign_face_name","arguments":"{\\"trackId\\":\\"trak-000001\\",\\"name\\":\\"山田\\"}"},
                  {"type":"message","content":[{"type":"output_text","text":"確認します。"}]}
                ],"output_text":"確認します。"}
                """, 200, new AtomicReference<>(), body)) {
            OpenAiResponsesLanguageModel model = new OpenAiResponsesLanguageModel(new LLM.Config(
                    server.baseUri(),
                    "gemma[-_]?4[-]?e2b",
                    "日本語で答えてください。",
                    Duration.ofSeconds(5)));

            LanguageModelResponse response = model.respond(
                    List.of(new ChatMessage("user", "私の名前は山田です")),
                    List.of(assignFaceNameTool()));

            assertEquals("確認します。", response.text());
            assertEquals(1, response.toolCalls().size());
            assertEquals("assign_face_name", response.toolCalls().get(0).name());
            assertEquals("{\"trackId\":\"trak-000001\",\"name\":\"山田\"}",
                    response.toolCalls().get(0).arguments());
            assertTrue(body.get().contains("\"stream\":false"));
            assertTrue(body.get().contains("\"tools\":["));
        }
    }

    @Test
    void postsToolResultsWithoutStreaming() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        try (FakeServer server = new FakeServer("""
                {"output":[{"type":"message","content":[{"type":"output_text","text":"登録しました。"}]}],"output_text":"登録しました。"}
                """, 200, new AtomicReference<>(), body)) {
            OpenAiResponsesLanguageModel model = new OpenAiResponsesLanguageModel(new LLM.Config(
                    server.baseUri(),
                    "gemma[-_]?4[-]?e2b",
                    "日本語で答えてください。",
                    Duration.ofSeconds(5)));
            ToolCall toolCall = new ToolCall(
                    "fc_1",
                    "call_1",
                    "assign_face_name",
                    "{\"trackId\":\"trak-000001\",\"name\":\"山田\"}");

            LanguageModelResponse response = model.respond(
                    List.of(new ChatMessage("user", "私の名前は山田です")),
                    List.of(assignFaceNameTool()),
                    List.of(new ToolCallResult(toolCall, "{\"status\":\"ok\"}")));

            assertEquals("登録しました。", response.text());
            assertTrue(response.toolCalls().isEmpty());
            assertTrue(body.get().contains("\"type\":\"function_call\""));
            assertTrue(body.get().contains("\"type\":\"function_call_output\""));
            assertTrue(body.get().contains("\"stream\":false"));
        }
    }

    @Test
    void throwsOnHttpError() throws Exception {
        try (FakeServer server = new FakeServer("""
                {"error":"not ready"}
                """, 503, new AtomicReference<>(), new AtomicReference<>())) {
            OpenAiResponsesLanguageModel model = new OpenAiResponsesLanguageModel(new LLM.Config(
                    server.baseUri(),
                    "gemma[-_]?4[-]?e2b",
                    "",
                    Duration.ofSeconds(5)));

            assertThrows(LanguageModelException.class, () -> model.respond("こんにちは"));
        }
    }

    @Test
    void throwsWhenNoModelMatchesRegularExpression() throws Exception {
        try (FakeServer server = new FakeServer(sse("""
                {"output_text":"unused"}
                """), 200, new AtomicReference<>(), new AtomicReference<>())) {
            OpenAiResponsesLanguageModel model = new OpenAiResponsesLanguageModel(new LLM.Config(
                    server.baseUri(),
                    "not-found-[0-9]+",
                    "",
                    Duration.ofSeconds(5)));

            LanguageModelException error = assertThrows(LanguageModelException.class, () -> model.respond("こんにちは"));
            assertTrue(error.getMessage().contains("pattern=not-found-[0-9]+"));
            assertTrue(error.getMessage().contains("gemma_4e2b-it-q4_k_m"));
        }
    }

    private static String sse(String... events) {
        StringBuilder body = new StringBuilder();
        for (String event : events) {
            body.append("data: ").append(event.strip()).append("\n\n");
        }
        body.append("data: [DONE]\n\n");
        return body.toString();
    }

    static ToolDefinition assignFaceNameTool() {
        return new ToolDefinition(
                "assign_face_name",
                "trackIdに人物名を登録します。ユーザーが自分の名前を名乗ったときに呼び出します。",
                """
                        {"type":"object","properties":{"trackId":{"type":"string"},"name":{"type":"string"}},"required":["trackId","name"],"additionalProperties":false}\
                        """);
    }

    private static class FakeServer implements AutoCloseable {
        private final HttpServer server;
        private final List<String> paths = new ArrayList<>();

        FakeServer(String responseBody, int status, AtomicReference<String> path, AtomicReference<String> body)
                throws IOException {
            this(responseBody, status, path, body, new AtomicReference<>());
        }

        FakeServer(
                String responseBody,
                int status,
                AtomicReference<String> path,
                AtomicReference<String> body,
                AtomicReference<String> authorization) throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/v1/models", exchange -> {
                recordPath(exchange.getRequestURI().getPath());
                byte[] responseBytes = """
                        {"object":"list","data":[{"id":"other-model"},{"id":"gemma_4e2b-it-q4_k_m"},{"id":"gemma-4-e2b-it-q8_0"}]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                exchange.sendResponseHeaders(200, responseBytes.length);
                try (OutputStream response = exchange.getResponseBody()) {
                    response.write(responseBytes);
                }
            });
            server.createContext("/v1/responses", exchange -> {
                recordPath(exchange.getRequestURI().getPath());
                path.set(exchange.getRequestURI().getPath());
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.sendResponseHeaders(status, responseBytes.length);
                try (OutputStream response = exchange.getResponseBody()) {
                    response.write(responseBytes);
                }
            });
            server.start();
        }

        URI baseUri() {
            return URI.create("http://localhost:" + server.getAddress().getPort() + "/v1");
        }

        List<String> paths() {
            return List.copyOf(paths);
        }

        private synchronized void recordPath(String path) {
            paths.add(path);
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
