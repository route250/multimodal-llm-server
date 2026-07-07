package tts;

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
import java.util.Optional;
import java.util.function.Consumer;

/**
 * LFM2.5 Audio の OpenAI Chat Completions 互換サーバを使う TTS クライアントです。
 */
public class Lfm2AudioTextToSpeech implements TextToSpeech {
    public static final URI DEFAULT_ENDPOINT = URI.create("http://localhost:8766/v1/chat/completions");

    private static final String DEFAULT_MODEL = "lfm2-audio";
    private static final String DEFAULT_SYSTEM_PROMPT = "Perform TTS in japanese.";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);

    private final URI endpoint;
    private final HttpClient httpClient;
    private final String model;
    private final String systemPrompt;
    private final Duration timeout;

    public Lfm2AudioTextToSpeech() {
        this(Config.fromEnvironment());
    }

    public Lfm2AudioTextToSpeech(URI endpoint) {
        this(new Config(endpoint, DEFAULT_MODEL, DEFAULT_SYSTEM_PROMPT, DEFAULT_TIMEOUT));
    }

    public Lfm2AudioTextToSpeech(Config config) {
        this.endpoint = config.endpoint();
        this.model = requireText(config.model(), "model");
        this.systemPrompt = requireText(config.systemPrompt(), "systemPrompt");
        this.timeout = config.timeout();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public void synthesizeStreaming(String text, Consumer<AudioDelta> onDelta) {
        String speechText = requireText(text, "text");
        HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(speechText), StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new TextToSpeechException("lfm2-audio-server returned HTTP "
                        + response.statusCode() + " " + errorBody);
            }
            readStreamingResponse(response.body(), onDelta);
        } catch (IOException e) {
            throw new TextToSpeechException("failed to request lfm2-audio-server at " + endpoint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TextToSpeechException("interrupted while requesting lfm2-audio-server at " + endpoint, e);
        }
    }

    private String requestBody(String text) {
        return """
                {"model":"%s","messages":[{"role":"system","content":"%s"},{"role":"user","content":"%s"}],"temperature":0,"stream":true}
                """.formatted(Json.escape(model), Json.escape(systemPrompt), Json.escape(text));
    }

    private static void readStreamingResponse(InputStream body, Consumer<AudioDelta> onDelta) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) {
                    continue;
                }
                String data = line.substring("data: ".length());
                if ("[DONE]".equals(data)) {
                    return;
                }
                streamingAudio(data).ifPresent(onDelta);
            }
        }
    }

    static Optional<AudioDelta> streamingAudio(String body) {
        int audioStart = body.indexOf("\"audio\"");
        if (audioStart < 0) {
            return Optional.empty();
        }
        String data = JsonFields.string(body, "data", audioStart).orElse("");
        if (data.isEmpty()) {
            return Optional.empty();
        }
        String format = JsonFields.string(body, "format", audioStart).orElse("pcm");
        Long sampleRateValue = JsonFields.longOrNull(body, "sample_rate", audioStart);
        int sampleRate = Math.toIntExact(sampleRateValue == null ? 24_000 : sampleRateValue);
        return Optional.of(new AudioDelta(data, format, sampleRate));
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

    public record Config(URI endpoint, String model, String systemPrompt, Duration timeout) {
        public Config {
            if (endpoint == null) {
                throw new IllegalArgumentException("endpoint must not be null");
            }
            if (timeout == null || timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
        }

        static Config fromEnvironment() {
            return new Config(
                    URI.create(env("LFM2_AUDIO_TTS_URL", DEFAULT_ENDPOINT.toString())),
                    env("LFM2_AUDIO_TTS_MODEL", DEFAULT_MODEL),
                    env("LFM2_AUDIO_TTS_SYSTEM_PROMPT", DEFAULT_SYSTEM_PROMPT),
                    Duration.ofSeconds(Long.parseLong(env(
                            "LFM2_AUDIO_TTS_TIMEOUT_SECONDS",
                            Long.toString(DEFAULT_TIMEOUT.toSeconds())))));
        }
    }
}
