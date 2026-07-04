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
            OpenAiResponsesLanguageModel model = new OpenAiResponsesLanguageModel(new OpenAiResponsesLanguageModel.Config(
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
    void ignoresStreamingEventsThatAreNotOutputDeltas() throws Exception {
        try (FakeServer server = new FakeServer(sse("""
                {"type":"response.output_item.done","item":{"content":[{"type":"output_text","text":"これは差分ではない"}]}}
                """, """
                {"delta":"語順が逆でも","type":"response.output_text.delta"}
                """), 200, new AtomicReference<>(), new AtomicReference<>())) {
            OpenAiResponsesLanguageModel model = new OpenAiResponsesLanguageModel(new OpenAiResponsesLanguageModel.Config(
                    server.baseUri(),
                    "gemma[-_]?4[-]?e2b",
                    "",
                    Duration.ofSeconds(5)));

            assertEquals("語順が逆でも", model.respond("こんにちは"));
        }
    }

    @Test
    void postsConversationHistoryToResponsesEndpoint() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        try (FakeServer server = new FakeServer(sse("""
                {"type":"response.output_text.delta","delta":"続きです"}
                """), 200, new AtomicReference<>(), body)) {
            OpenAiResponsesLanguageModel model = new OpenAiResponsesLanguageModel(new OpenAiResponsesLanguageModel.Config(
                    server.baseUri(),
                    "gemma[-_]?4[-]?e2b",
                    "日本語で答えてください。",
                    Duration.ofSeconds(5)));

            assertEquals("続きです", model.respond(List.of(
                    new ChatMessage("user", "私の名前は太郎です"),
                    new ChatMessage("assistant", "覚えました"),
                    new ChatMessage("user", "私の名前は何ですか"))));

            assertTrue(body.get().contains("\"role\":\"user\""));
            assertTrue(body.get().contains("\"text\":\"私の名前は太郎です\""));
            assertTrue(body.get().contains("\"role\":\"assistant\""));
            assertTrue(body.get().contains("\"type\":\"output_text\""));
            assertTrue(body.get().contains("\"text\":\"覚えました\""));
            assertTrue(body.get().contains("\"text\":\"私の名前は何ですか\""));
        }
    }

    @Test
    void throwsOnHttpError() throws Exception {
        try (FakeServer server = new FakeServer("""
                {"error":"not ready"}
                """, 503, new AtomicReference<>(), new AtomicReference<>())) {
            OpenAiResponsesLanguageModel model = new OpenAiResponsesLanguageModel(new OpenAiResponsesLanguageModel.Config(
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
            OpenAiResponsesLanguageModel model = new OpenAiResponsesLanguageModel(new OpenAiResponsesLanguageModel.Config(
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

    private static class FakeServer implements AutoCloseable {
        private final HttpServer server;
        private final List<String> paths = new ArrayList<>();

        FakeServer(String responseBody, int status, AtomicReference<String> path, AtomicReference<String> body)
                throws IOException {
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
