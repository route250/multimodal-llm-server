package llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** LlmOpenAI のモデル解決、通常呼び出し、ストリーミング、ツール呼び出しを検証します。 */
class LlmOpenAITest {
    @Test
    void resolvesModelAndStreamsResponse() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        try (FakeServer server = new FakeServer(sse(
                "{\"type\":\"response.output_text.delta\",\"delta\":\"こんにちは\"}",
                "{\"type\":\"response.output_text.delta\",\"delta\":\"\\nどうぞ\"}"), body)) {
            LlmOpenAI model = model(server);
            List<String> deltas = new ArrayList<>();

            List<LLM.Message> response = model.call(
                    List.of(new LLM.Message("user", "音声認識の結果")), null, deltas::add);

            assertEquals("gemma_4e2b-it-q4_k_m", model.model());
            assertEquals("こんにちは\nどうぞ", response.get(0).message);
            assertEquals(List.of("こんにちは", "\nどうぞ"), deltas);
            assertTrue(body.get().contains("\"model\":\"gemma_4e2b-it-q4_k_m\""));
            assertTrue(body.get().contains("\"input\":"));
        }
    }

    @Test
    void sendsApiKeyAndConversationRoles() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        try (FakeServer server = new FakeServer(
                sse("{\"type\":\"response.output_text.delta\",\"delta\":\"ok\"}"),
                body, authorization)) {
            LlmOpenAI model = new LlmOpenAI(new LLM.Config(
                    server.baseUri(), "gemma", Duration.ofSeconds(5), "test-key"));

            assertEquals("ok", model.call(List.of(
                    new LLM.Message("system", "日本語で答えてください"),
                    new LLM.Message("assistant", "覚えました"),
                    new LLM.Message("user", "続けてください")) ).get(0).message);
            assertEquals("Bearer test-key", authorization.get());
            assertTrue(body.get().contains("\"role\":\"system\""));
            assertTrue(body.get().contains("\"role\":\"assistant\""));
            assertTrue(body.get().contains("\"text\":\"続けてください\""));
        }
    }

    @Test
    void executesToolAndSendsToolOutputOnNextRequest() throws Exception {
        AtomicReference<String> firstBody = new AtomicReference<>();
        AtomicReference<String> secondBody = new AtomicReference<>();
        try (FakeServer server = new FakeServer(firstBody, secondBody)) {
            RecordingTool tool = new RecordingTool();
            LlmOpenAI model = model(server);

            List<LLM.Message> response = model.call(
                    List.of(new LLM.Message("user", "太郎を登録して")), List.of(tool));

            assertEquals("登録しました", response.get(0).message);
            assertEquals("{\"trackId\":\"trak-000001\",\"name\":\"太郎\"}", tool.arguments.get());
            assertTrue(secondBody.get().contains("\"type\":\"function_call\""));
            assertTrue(secondBody.get().contains("\"type\":\"function_call_output\""));
        }
    }

    private static LlmOpenAI model(FakeServer server) {
        return new LlmOpenAI(new LLM.Config(
                server.baseUri(), "gemma[-_]?4[-]?e2b", Duration.ofSeconds(5), ""));
    }

    private static String sse(String... events) {
        StringBuilder body = new StringBuilder();
        for (String event : events) {
            body.append("data: ").append(event).append("\n\n");
        }
        return body.append("data: [DONE]\n\n").toString();
    }

    static final class RecordingTool extends LLM.Tool {
        private final AtomicReference<String> arguments = new AtomicReference<>();

        RecordingTool() {
            super("assign_face_name", "人物名を登録します");
        }

        @Override
        public FunctionTool.Parameters parameters() {
            return FunctionTool.Parameters.builder()
                    .putAdditionalProperty("type", JsonValue.from("object"))
                    .putAdditionalProperty("properties", JsonValue.from(Map.of(
                            "trackId", Map.of("type", "string"),
                            "name", Map.of("type", "string"))))
                    .putAdditionalProperty("required", JsonValue.from(List.of("trackId", "name")))
                    .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                    .build();
        }

        @Override
        public String exec(ResponseFunctionToolCall call, String arguments) {
            this.arguments.set(arguments);
            return "{\"status\":\"ok\"}";
        }
    }

    private static final class FakeServer implements AutoCloseable {
        private final HttpServer server;

        private FakeServer(String response, AtomicReference<String> body) throws IOException {
            this(response, body, new AtomicReference<>());
        }

        private FakeServer(String response, AtomicReference<String> body,
                AtomicReference<String> authorization) throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/v1/models", exchange -> respond(exchange,
                    "{\"data\":[{\"id\":\"other-model\"},{\"id\":\"gemma_4e2b-it-q4_k_m\"}]}"));
            server.createContext("/v1/responses", exchange -> {
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
                respond(exchange, response);
            });
            server.start();
        }

        private FakeServer(AtomicReference<String> firstBody, AtomicReference<String> secondBody)
                throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/v1/models", exchange -> respond(exchange,
                    "{\"data\":[{\"id\":\"gemma_4e2b-it-q4_k_m\"}]}"));
            server.createContext("/v1/responses", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (firstBody.get() == null) {
                    firstBody.set(body);
                    respond(exchange, sse("{\"type\":\"response.output_item.done\",\"item\":{"+
                            "\"id\":\"fc_1\",\"type\":\"function_call\",\"status\":\"completed\","+
                            "\"arguments\":\"{\\\"trackId\\\":\\\"trak-000001\\\",\\\"name\\\":\\\"太郎\\\"}\","+
                            "\"call_id\":\"call_1\",\"name\":\"assign_face_name\"}}"));
                } else {
                    secondBody.set(body);
                    respond(exchange, sse("{\"type\":\"response.output_text.delta\",\"delta\":\"登録しました\"}"));
                }
            });
            server.start();
        }

        private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }

        private java.net.URI baseUri() {
            return java.net.URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
