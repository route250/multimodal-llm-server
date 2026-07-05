package stt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import audio.AudioBuffer;
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

class Lfm2AudioSpeechToTextTest {
    @Test
    void postsAudioToChatCompletionsAndReadsStreamingContent() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        try (FakeServer server = new FakeServer(sse("""
                {"object":"chat.completion.chunk","choices":[{"delta":{"content":"もし"}}]}
                """, """
                {"object":"chat.completion.chunk","choices":[{"delta":{"content":"もし\\n"}}]}
                """, """
                {"object":"chat.completion.chunk","choices":[{"delta":{},"finish_reason":"stop"}]}
                """), body)) {
            Lfm2AudioSpeechToText speechToText = new Lfm2AudioSpeechToText(new Lfm2AudioSpeechToText.Config(
                    server.endpoint(),
                    "lfm2-audio",
                    "Perform ASR in japanese.",
                    Duration.ofSeconds(5)));
            AudioBuffer audio = new AudioBuffer(16_000, 16_000);
            audio.append(new short[] {1, 2}, 0);

            Transcription transcription = speechToText.transcribe(audio, 0, 2, "");

            assertEquals("もしもし", transcription.text());
            assertEquals(1, transcription.segments().size());
            assertEquals("/v1/chat/completions", server.paths().getFirst());
            assertTrue(body.get().contains("\"stream\":true"));
            assertTrue(body.get().contains("\"type\":\"input_audio\""));
            assertTrue(body.get().contains("\"format\":\"wav\""));
        }
    }

    @Test
    void extractsChatCompletionDeltaContent() {
        assertEquals("こんにちは", Lfm2AudioSpeechToText.streamingText("""
                {"choices":[{"index":0,"delta":{"content":"こんにちは"},"finish_reason":null}]}
                """).orElseThrow());
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

        FakeServer(String responseBody, AtomicReference<String> body) throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
                recordPath(exchange.getRequestURI().getPath());
                body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
                exchange.sendResponseHeaders(200, responseBytes.length);
                try (OutputStream response = exchange.getResponseBody()) {
                    response.write(responseBytes);
                }
            });
            server.start();
        }

        URI endpoint() {
            return URI.create("http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions");
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
