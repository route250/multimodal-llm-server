package audio.stt;

import audio.AudioBuffer;
import json.Json;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LFM2.5 Audio の OpenAI Chat Completions 互換サーバを使う音声認識クライアントです。
 */
public class Lfm2AudioSpeechToText implements SpeechToText {
    public static final URI DEFAULT_ENDPOINT = URI.create("http://localhost:8766/v1/chat/completions");

    private static final int SAMPLE_RATE = 16_000;
    private static final int CHANNELS = 1;
    private static final int BITS_PER_SAMPLE = 16;
    private static final String DEFAULT_MODEL = "lfm2-audio";
    private static final String DEFAULT_SYSTEM_PROMPT = "Perform ASR in japanese.";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    private static final Pattern CHAT_DELTA_CONTENT_PATTERN =
            Pattern.compile("\"content\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", Pattern.DOTALL);

    private final URI endpoint;
    private final HttpClient httpClient;
    private final String model;
    private final String systemPrompt;
    private final Duration timeout;

    public Lfm2AudioSpeechToText() {
        this(Config.fromEnvironment());
    }

    public Lfm2AudioSpeechToText(URI endpoint) {
        this(new Config(endpoint, DEFAULT_MODEL, DEFAULT_SYSTEM_PROMPT, DEFAULT_TIMEOUT));
    }

    public Lfm2AudioSpeechToText(Config config) {
        this.endpoint = config.endpoint();
        this.model = requireText(config.model(), "model");
        this.systemPrompt = requireText(config.systemPrompt(), "systemPrompt");
        this.timeout = config.timeout();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public Transcription transcribe(
            AudioBuffer audioBuffer,
            long startSampleIndex,
            long endSampleIndexExclusive,
            String prompt) {
        if (endSampleIndexExclusive <= startSampleIndex) {
            return Transcription.empty();
        }
        if (!audioBuffer.contains(startSampleIndex, endSampleIndexExclusive)) {
            throw new SpeechToTextException("audio range is no longer available");
        }

        byte[] wav = wav(audioBuffer, startSampleIndex, endSampleIndexExclusive);
        String text = requestTranscription(wav, prompt).trim();
        return Transcription.singleSegment(text, endSampleIndexExclusive - startSampleIndex, SAMPLE_RATE);
    }

    private String requestTranscription(byte[] wav, String prompt) {
        String requestBody = requestBody(wav, prompt);
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
                throw new SpeechToTextException("lfm2-audio-server returned HTTP "
                        + response.statusCode() + " " + errorBody);
            }
            return readStreamingResponse(response.body());
        } catch (IOException e) {
            throw new SpeechToTextException("failed to request lfm2-audio-server at " + endpoint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpeechToTextException("interrupted while requesting lfm2-audio-server at " + endpoint, e);
        }
    }

    /**
     * llama-liquid-audio-server は非streamingを未実装として返すため、stream=trueで要求します。
     */
    private String requestBody(byte[] wav, String prompt) {
        String instruction = systemPrompt;
        if (prompt != null && !prompt.isBlank()) {
            instruction = instruction + "\n" + prompt;
        }
        String audio = Base64.getEncoder().encodeToString(wav);
        return """
                {"model":"%s","messages":[{"role":"system","content":"%s"},{"role":"user","content":[{"type":"input_audio","input_audio":{"data":"%s","format":"wav"}}]}],"temperature":0,"stream":true}
                """.formatted(Json.escape(model), Json.escape(instruction), audio);
    }

    private static String readStreamingResponse(InputStream body) throws IOException {
        StringBuilder text = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) {
                    continue;
                }
                String data = line.substring("data: ".length());
                if ("[DONE]".equals(data)) {
                    return text.toString();
                }
                streamingText(data).ifPresent(text::append);
            }
        }
        return text.toString();
    }

    static Optional<String> streamingText(String body) {
        Matcher matcher = CHAT_DELTA_CONTENT_PATTERN.matcher(body);
        if (matcher.find()) {
            return Optional.of(Json.unescape(matcher.group(1)));
        }
        return Optional.empty();
    }

    private static byte[] wav(AudioBuffer audioBuffer, long startSampleIndex, long endSampleIndexExclusive) {
        int samples = Math.toIntExact(endSampleIndexExclusive - startSampleIndex);
        int dataBytes = samples * Short.BYTES;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        header.putInt(36 + dataBytes);
        header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) CHANNELS);
        header.putInt(SAMPLE_RATE);
        header.putInt(SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8);
        header.putShort((short) (CHANNELS * BITS_PER_SAMPLE / 8));
        header.putShort((short) BITS_PER_SAMPLE);
        header.put("data".getBytes(StandardCharsets.US_ASCII));
        header.putInt(dataBytes);

        ByteBuffer body = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        body.put(header.array());
        for (long sampleIndex = startSampleIndex; sampleIndex < endSampleIndexExclusive; sampleIndex++) {
            body.putShort(audioBuffer.sampleAt(sampleIndex));
        }
        return body.array();
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
                    URI.create(env("LFM2_AUDIO_STT_URL", DEFAULT_ENDPOINT.toString())),
                    env("LFM2_AUDIO_STT_MODEL", DEFAULT_MODEL),
                    env("LFM2_AUDIO_STT_SYSTEM_PROMPT", DEFAULT_SYSTEM_PROMPT),
                    Duration.ofSeconds(Long.parseLong(env(
                            "LFM2_AUDIO_STT_TIMEOUT_SECONDS",
                            Long.toString(DEFAULT_TIMEOUT.toSeconds())))));
        }
    }
}
