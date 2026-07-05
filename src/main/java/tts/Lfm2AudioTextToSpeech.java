package tts;

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
                """.formatted(jsonEscape(model), jsonEscape(systemPrompt), jsonEscape(text));
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
        String data = jsonStringField(body, "\"data\"", audioStart).orElse("");
        if (data.isEmpty()) {
            return Optional.empty();
        }
        String format = jsonStringField(body, "\"format\"", audioStart).orElse("pcm");
        int sampleRate = intField(body, "\"sample_rate\"", audioStart).orElse(24_000);
        return Optional.of(new AudioDelta(data, format, sampleRate));
    }

    private static Optional<String> jsonStringField(String body, String key, int startIndex) {
        int keyIndex = body.indexOf(key, startIndex);
        if (keyIndex < 0) {
            return Optional.empty();
        }
        int colonIndex = body.indexOf(':', keyIndex + key.length());
        if (colonIndex < 0) {
            return Optional.empty();
        }
        int quoteIndex = nextNonWhitespace(body, colonIndex + 1);
        if (quoteIndex < 0 || body.charAt(quoteIndex) != '"') {
            return Optional.empty();
        }

        StringBuilder value = new StringBuilder();
        boolean escaped = false;
        for (int i = quoteIndex + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (escaped) {
                value.append('\\').append(c);
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                return Optional.of(jsonUnescape(value.toString()));
            } else {
                value.append(c);
            }
        }
        return Optional.empty();
    }

    private static Optional<Integer> intField(String body, String key, int startIndex) {
        int keyIndex = body.indexOf(key, startIndex);
        if (keyIndex < 0) {
            return Optional.empty();
        }
        int colonIndex = body.indexOf(':', keyIndex + key.length());
        if (colonIndex < 0) {
            return Optional.empty();
        }
        int valueStart = nextNonWhitespace(body, colonIndex + 1);
        if (valueStart < 0) {
            return Optional.empty();
        }
        int valueEnd = valueStart;
        while (valueEnd < body.length() && Character.isDigit(body.charAt(valueEnd))) {
            valueEnd++;
        }
        if (valueEnd == valueStart) {
            return Optional.empty();
        }
        return Optional.of(Integer.parseInt(body.substring(valueStart, valueEnd)));
    }

    private static int nextNonWhitespace(String value, int startIndex) {
        for (int i = startIndex; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
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

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (c < 0x20) {
                        escaped.append("\\u%04x".formatted((int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    private static String jsonUnescape(String value) {
        StringBuilder unescaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                unescaped.append(c);
                continue;
            }
            char escaped = value.charAt(++i);
            switch (escaped) {
                case '\\' -> unescaped.append('\\');
                case '"' -> unescaped.append('"');
                case '/' -> unescaped.append('/');
                case 'b' -> unescaped.append('\b');
                case 'f' -> unescaped.append('\f');
                case 'n' -> unescaped.append('\n');
                case 'r' -> unescaped.append('\r');
                case 't' -> unescaped.append('\t');
                case 'u' -> {
                    if (i + 4 >= value.length()) {
                        unescaped.append("\\u");
                        continue;
                    }
                    String hex = value.substring(i + 1, i + 5);
                    try {
                        unescaped.append((char) Integer.parseInt(hex, 16));
                        i += 4;
                    } catch (NumberFormatException e) {
                        unescaped.append("\\u").append(hex);
                        i += 4;
                    }
                }
                default -> unescaped.append(escaped);
            }
        }
        return unescaped.toString();
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
