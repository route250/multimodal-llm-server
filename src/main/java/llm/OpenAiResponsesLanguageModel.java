package llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * OpenAI Responses API 互換エンドポイントへ問い合わせる LLM クライアントです。
 */
public class OpenAiResponsesLanguageModel implements LanguageModel {
    private static final URI DEFAULT_BASE_URI = URI.create("http://localhost:1234/v1");
    private static final String DEFAULT_MODEL_PATTERN = "gemma[-_]?4[-]?e2b";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    private static final String DEFAULT_SYSTEM_PROMPT = "あなたは日本語で簡潔に応答するアシスタントです。";
    private static final Pattern OUTPUT_TEXT_PATTERN =
            Pattern.compile("\"output_text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", Pattern.DOTALL);
    private static final Pattern TEXT_PATTERN =
            Pattern.compile("\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", Pattern.DOTALL);
    private static final Pattern MODEL_ID_PATTERN =
            Pattern.compile("\"id\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", Pattern.DOTALL);
    private static final Pattern STREAM_DELTA_TYPE_PATTERN =
            Pattern.compile("\"type\"\\s*:\\s*\"(?:response\\.output_text\\.delta|response\\.refusal\\.delta)\"");
    private static final Pattern STREAM_DELTA_PATTERN =
            Pattern.compile("\"delta\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"", Pattern.DOTALL);

    private final HttpClient httpClient;
    private final URI responsesEndpoint;
    private final URI modelsEndpoint;
    private final Pattern modelPattern;
    private final String modelPatternText;
    private final String systemPrompt;
    private final Duration timeout;
    private volatile String selectedModel;

    public OpenAiResponsesLanguageModel() {
        this(fromEnvironment());
    }

    public OpenAiResponsesLanguageModel(Config config) {
        this(HttpClient.newHttpClient(), config);
    }

    OpenAiResponsesLanguageModel(HttpClient httpClient, Config config) {
        this.httpClient = httpClient;
        this.responsesEndpoint = endpoint(config.baseUri(), "responses");
        this.modelsEndpoint = endpoint(config.baseUri(), "models");
        this.modelPatternText = requireText(config.model(), "model");
        this.modelPattern = compileModelPattern(modelPatternText);
        this.systemPrompt = config.systemPrompt();
        this.timeout = config.timeout();
    }

    public static Config fromEnvironment() {
        return new Config(
                URI.create(env("LMSTUDIO_BASE_URL", DEFAULT_BASE_URI.toString())),
                env("LLM_MODEL", DEFAULT_MODEL_PATTERN),
                env("LLM_SYSTEM_PROMPT", DEFAULT_SYSTEM_PROMPT),
                Duration.ofSeconds(Long.parseLong(env("LLM_TIMEOUT_SECONDS", Long.toString(DEFAULT_TIMEOUT.toSeconds())))));
    }

    @Override
    public String respond(String userText) {
        return respond(List.of(new ChatMessage("user", requireText(userText, "userText"))));
    }

    @Override
    public String respond(List<ChatMessage> messages) {
        StringBuilder response = new StringBuilder();
        respondStreaming(messages, response::append);
        if (response.isEmpty()) {
            throw new LanguageModelException("LLM response did not contain text");
        }
        return response.toString();
    }

    @Override
    public void respondStreaming(String userText, Consumer<String> onDelta) {
        respondStreaming(List.of(new ChatMessage("user", requireText(userText, "userText"))), onDelta);
    }

    @Override
    public void respondStreaming(List<ChatMessage> messages, Consumer<String> onDelta) {
        List<ChatMessage> safeMessages = List.copyOf(messages);
        if (safeMessages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        String requestBody = requestBody(safeMessages, true);
        HttpRequest request = HttpRequest.newBuilder(responsesEndpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<java.io.InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new LanguageModelException("LLM request failed: HTTP " + response.statusCode() + " " + errorBody);
            }
            readStreamingResponse(response.body(), onDelta);
        } catch (IOException e) {
            throw new LanguageModelException("failed to request LLM at " + responsesEndpoint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LanguageModelException("interrupted while requesting LLM at " + responsesEndpoint, e);
        }
    }

    /**
     * LM Studio と OpenAI Responses API の両方で扱いやすい入力配列形式の JSON を作ります。
     */
    private String requestBody(List<ChatMessage> messages, boolean stream) {
        return """
                {"model":"%s","input":[%s],"stream":%s}
                """.formatted(jsonEscape(resolveModel()), inputMessages(messages), stream);
    }

    private String inputMessages(List<ChatMessage> messages) {
        StringBuilder input = new StringBuilder();
        input.append(inputMessage("system", "input_text", systemPrompt));
        for (ChatMessage message : messages) {
            String contentType = "assistant".equals(message.role()) ? "output_text" : "input_text";
            input.append(",").append(inputMessage(message.role(), contentType, message.text()));
        }
        return input.toString();
    }

    private static String inputMessage(String role, String contentType, String text) {
        return """
                {"role":"%s","content":[{"type":"%s","text":"%s"}]}\
                """.formatted(jsonEscape(role), jsonEscape(contentType), jsonEscape(text));
    }

    /**
     * Responses API の標準的な output_text を優先し、互換実装向けに入れ子の text も読み取ります。
     */
    private static Optional<String> responseText(String body) {
        Optional<String> outputText = firstJsonString(body, OUTPUT_TEXT_PATTERN);
        if (outputText.isPresent()) {
            return outputText;
        }
        return firstJsonString(body, TEXT_PATTERN);
    }

    private static void readStreamingResponse(java.io.InputStream body, Consumer<String> onDelta) throws IOException {
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
                streamingText(data).ifPresent(onDelta);
            }
        }
    }

    private static Optional<String> streamingText(String body) {
        if (!STREAM_DELTA_TYPE_PATTERN.matcher(body).find()) {
            return Optional.empty();
        }
        return firstJsonString(body, STREAM_DELTA_PATTERN);
    }

    private String resolveModel() {
        String current = selectedModel;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (selectedModel == null) {
                selectedModel = selectModel(fetchModels());
            }
            return selectedModel;
        }
    }

    private List<String> fetchModels() {
        HttpRequest request = HttpRequest.newBuilder(modelsEndpoint)
                .timeout(timeout)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new LanguageModelException("LLM models request failed: HTTP "
                        + response.statusCode() + " " + response.body());
            }
            return modelIds(response.body());
        } catch (IOException e) {
            throw new LanguageModelException("failed to request LLM models at " + modelsEndpoint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LanguageModelException("interrupted while requesting LLM models at " + modelsEndpoint, e);
        }
    }

    private String selectModel(List<String> models) {
        return models.stream()
                .filter(model -> modelPattern.matcher(model).find())
                .findFirst()
                .orElseThrow(() -> new LanguageModelException("LLM model not found: pattern="
                        + modelPatternText + ", available=" + models));
    }

    private static List<String> modelIds(String body) {
        Matcher matcher = MODEL_ID_PATTERN.matcher(body);
        List<String> models = new ArrayList<>();
        while (matcher.find()) {
            models.add(jsonUnescape(matcher.group(1)));
        }
        return models;
    }

    private static Optional<String> firstJsonString(String body, Pattern pattern) {
        Matcher matcher = pattern.matcher(body);
        if (matcher.find()) {
            return Optional.of(jsonUnescape(matcher.group(1)));
        }
        return Optional.empty();
    }

    private static URI endpoint(URI baseUri, String path) {
        String text = baseUri.toString();
        if (text.endsWith("/" + path)) {
            return baseUri;
        }
        if (text.endsWith("/")) {
            return URI.create(text + path);
        }
        return URI.create(text + "/" + path);
    }

    private static Pattern compileModelPattern(String value) {
        try {
            return Pattern.compile(value);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("model must be a valid regular expression: " + value, e);
        }
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
                case 'n' -> unescaped.append('\n');
                case 'r' -> unescaped.append('\r');
                case 't' -> unescaped.append('\t');
                case 'b' -> unescaped.append('\b');
                case 'f' -> unescaped.append('\f');
                case 'u' -> {
                    if (i + 4 >= value.length()) {
                        throw new LanguageModelException("invalid JSON unicode escape");
                    }
                    String hex = value.substring(i + 1, i + 5);
                    unescaped.append((char) Integer.parseInt(hex, 16));
                    i += 4;
                }
                default -> unescaped.append(escaped);
            }
        }
        return unescaped.toString();
    }

    public record Config(URI baseUri, String model, String systemPrompt, Duration timeout) {
    }
}
