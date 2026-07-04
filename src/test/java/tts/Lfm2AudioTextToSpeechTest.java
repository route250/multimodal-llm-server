package tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class Lfm2AudioTextToSpeechTest {
    @Test
    void postsTextToChatCompletionsAndReadsStreamingAudio() throws Exception {
        AtomicReference<String> body = new AtomicReference<>();
        try (FakeServer server = new FakeServer(sse("""
                {"object":"chat.completion.chunk","choices":[{"delta":{"audio":{"data":"AAAA","format":"pcm","sample_rate":24000}}}]}
                """, """
                {"object":"chat.completion.chunk","choices":[{"delta":{"audio":{"data":"","format":"pcm","sample_rate":24000}}}]}
                """, """
                {"object":"chat.completion.chunk","choices":[{"delta":{},"finish_reason":"stop"}]}
                """), body)) {
            Lfm2AudioTextToSpeech textToSpeech = new Lfm2AudioTextToSpeech(new Lfm2AudioTextToSpeech.Config(
                    server.endpoint(),
                    "lfm2-audio",
                    "Perform TTS.",
                    Duration.ofSeconds(5)));
            List<AudioDelta> deltas = new ArrayList<>();

            textToSpeech.synthesizeStreaming("こんにちは。", deltas::add);

            assertEquals(1, deltas.size());
            assertEquals(new AudioDelta("AAAA", "pcm", 24000), deltas.getFirst());
            assertTrue(body.get().contains("\"stream\":true"));
            assertTrue(body.get().contains("\"content\":\"Perform TTS.\""));
            assertTrue(body.get().contains("\"content\":\"こんにちは。\""));
        }
    }

    @Test
    void extractsAudioDelta() {
        AudioDelta audio = Lfm2AudioTextToSpeech.streamingAudio("""
                {"choices":[{"delta":{"audio":{"data":"AQID","format":"pcm","sample_rate":24000}}}]}
                """).orElseThrow();

        assertEquals(new AudioDelta("AQID", "pcm", 24000), audio);
    }

    @Test
    void extractsLargeAudioDeltaWithoutRegexStackOverflow() {
        String data = "A".repeat(100_000);
        AudioDelta audio = Lfm2AudioTextToSpeech.streamingAudio("""
                {"choices":[{"delta":{"audio":{"data":"%s","format":"pcm","sample_rate":24000}}}]}
                """.formatted(data)).orElseThrow();

        assertEquals(data, audio.data());
        assertEquals("pcm", audio.format());
        assertEquals(24000, audio.sampleRate());
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

        FakeServer(String responseBody, AtomicReference<String> body) throws IOException {
            server = HttpServer.create(new InetSocketAddress(0), 0);
            server.createContext("/v1/chat/completions", exchange -> {
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

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
