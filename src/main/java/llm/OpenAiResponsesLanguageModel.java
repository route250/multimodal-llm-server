package llm;

import json.Json;
import json.JsonFields;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * OpenAI Responses API へ問い合わせる LLM クライアントです。
 */
public class OpenAiResponsesLanguageModel implements LanguageModel {
    public static final URI DEFAULT_BASE_URI = URI.create("http://127.0.0.1:8767/v1");
    public static final String DEFAULT_MODEL_PATTERN = "LFM2\\.5.*JP";
    public static final URI OPENAI_BASE_URI = URI.create("https://api.openai.com/v1");
    public static final String OPENAI_MODEL_PATTERN = "gpt-4.1-nano";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(120);
    // public static final String DEFAULT_SYSTEM_PROMPT = "ボクはおしゃべり大好きなAI。名前はリキッドリリ。口癖は「君たちはいつもそうだ」「わけがわからないよ」。カメラ情報で話す相手を認識したら挨拶したり文句言ったりするんだよ。";
    public static final String DEFAULT_SYSTEM_PROMPT = """
                あなたは会話AIの「りり」です。目の前の相手に、親しみのある丁寧な日本語で話しかけます。

                system ロールの「人物認識通知」は、相手が来たことを知らせる内部通知です。相手の発言ではありません。
                通知の「相手の名前」は、りり自身ではなく、目の前にいる相手の名前です。

                人物認識通知を受けた場合だけ、次の形式の発話を一度出力します。
                - 認識結果が「名前不明」: 「こんにちは、りりです。よろしければ、お名前を教えてください。」
                - 認識結果が「登録済み」: 通知の「相手の名前:」の後にある人名を呼び、「さん、こんにちは。今日は何をお話ししましょうか？」と続ける。

                例: 通知に「認識結果: 登録済み」「相手の名前: 山田」とあれば、発話は「山田さん、こんにちは。今日は何をお話ししましょうか？」です。
                登録済みの場合は相手の名前から発話を始め、「こんにちは」は一度だけ使用します。人名の前に挨拶を追加しません。
                「相手の名前」や「＋」という文字は発話しません。引用符も出力しません。
                カメラ、人物認識、顔ID、通知、応答規則には言及しません。
                通知にない外見、表情、感情、行動、過去の関係は想像しません。
                りりが実際に話す言葉以外は出力しません。

                名前不明の人物認識通知の後で相手が自分の名前を名乗った場合は、assign_face_name ツールを必ず呼び出します。
                ツール引数の trackId には直前の人物認識通知にある trackId を、name には相手が名乗った人名だけを指定します。
                ツールの実行結果を受け取るまでは、名前を登録したとは発話しません。
                """;
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
    private final String apiKey;
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
        this.apiKey = config.apiKey() == null ? "" : config.apiKey().strip();
    }

    public static Config fromEnvironment() {
        URI baseUrl = URI.create(env("LLAMACPP_BASE_URL", DEFAULT_BASE_URI.toString()));
        String model = env("LLM_MODEL", baseUrl.getHost().contains("openai") ? OPENAI_MODEL_PATTERN : DEFAULT_MODEL_PATTERN);
        return new Config( baseUrl, model,
            env("LLM_SYSTEM_PROMPT", DEFAULT_SYSTEM_PROMPT),
            Duration.ofSeconds(Long.parseLong(env("LLM_TIMEOUT_SECONDS", Long.toString(DEFAULT_TIMEOUT.toSeconds())))),
            "");
    }

    /**
     * 設定画面の保存前に、モデル解決と実際の応答を確認します。
     */
    public Verification verify() {
        String model = resolveModel();
        String response = respond("設定確認です。OK とだけ答えてください。");
        if (response.isBlank()) {
            throw new LanguageModelException("LLM response did not contain text");
        }
        return new Verification(model, response);
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
    public LanguageModelResponse respond(
            List<ChatMessage> messages,
            List<ToolDefinition> tools,
            List<ToolCallResult> toolResults) {
        List<ChatMessage> safeMessages = List.copyOf(messages);
        if (safeMessages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        String requestBody = requestBody(safeMessages, List.copyOf(tools), List.copyOf(toolResults), false);
        LlmDiagnostics.Exchange diagnostics = LlmDiagnostics.saveRequest(responsesEndpoint, requestBody);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(responsesEndpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        addAuthorization(requestBuilder);
        try {
            HttpResponse<String> response = httpClient.send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                diagnostics.saveResponse(response.statusCode(), List.of(), response.body());
                throw new LanguageModelException(
                        "LLM request failed: HTTP " + response.statusCode() + " " + response.body());
            }
            diagnostics.saveResponse(response.statusCode(), List.of(response.body()), null);
            return new LanguageModelResponse(
                    responseText(response.body()).orElse(""),
                    responseToolCalls(response.body()));
        } catch (IOException e) {
            diagnostics.saveResponse(0, List.of(), e.toString());
            throw new LanguageModelException("failed to request LLM at " + responsesEndpoint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            diagnostics.saveResponse(0, List.of(), e.toString());
            throw new LanguageModelException("interrupted while requesting LLM at " + responsesEndpoint, e);
        }
    }

    @Override
    public void respondStreaming(String userText, Consumer<String> onDelta) {
        respondStreaming(List.of(new ChatMessage("user", requireText(userText, "userText"))), onDelta);
    }

    @Override
    public void respondStreaming(List<ChatMessage> messages, Consumer<String> onDelta) {
        respondStreamingEvents(messages, new StreamingResponseHandler() {
            @Override
            public void onTextDelta(String delta) {
                onDelta.accept(delta);
            }
        });
    }

    @Override
    public void respondStreamingEvents(List<ChatMessage> messages, StreamingResponseHandler handler) {
        respondStreamingEvents(messages, List.of(), handler);
    }

    public void respondStreamingEvents(
            List<ChatMessage> messages,
            List<ToolDefinition> tools,
            StreamingResponseHandler handler) {
        respondStreamingEvents(messages, tools, List.of(), handler);
    }

    @Override
    public void respondStreamingEvents(
            List<ChatMessage> messages,
            List<ToolDefinition> tools,
            List<ToolCallResult> toolResults,
            StreamingResponseHandler handler) {
        List<ChatMessage> safeMessages = List.copyOf(messages);
        if (safeMessages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        String requestBody = requestBody(safeMessages, List.copyOf(tools), List.copyOf(toolResults), true);
        LlmDiagnostics.Exchange diagnostics = LlmDiagnostics.saveRequest(responsesEndpoint, requestBody);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(responsesEndpoint)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8));
        addAuthorization(requestBuilder);
        HttpRequest request = requestBuilder.build();
        try {
            HttpResponse<java.io.InputStream> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                diagnostics.saveResponse(response.statusCode(), List.of(), errorBody);
                throw new LanguageModelException("LLM request failed: HTTP " + response.statusCode() + " " + errorBody);
            }
            List<String> events = readStreamingResponse(response.body(), handler);
            diagnostics.saveResponse(response.statusCode(), events, null);
        } catch (IOException e) {
            diagnostics.saveResponse(0, List.of(), e.toString());
            throw new LanguageModelException("failed to request LLM at " + responsesEndpoint, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            diagnostics.saveResponse(0, List.of(), e.toString());
            throw new LanguageModelException("interrupted while requesting LLM at " + responsesEndpoint, e);
        }
    }

    /**
     * OpenAI Responses API の入力配列形式の JSON を作ります。
     */
    private String requestBody(List<ChatMessage> messages, boolean stream) {
        return requestBody(messages, List.of(), List.of(), stream);
    }

    private String requestBody(List<ChatMessage> messages, List<ToolDefinition> tools, boolean stream) {
        return requestBody(messages, tools, List.of(), stream);
    }

    private String requestBody(
            List<ChatMessage> messages,
            List<ToolDefinition> tools,
            List<ToolCallResult> toolResults,
            boolean stream) {
        String toolJson = toolsJson(tools);
        return """
                {"model":"%s","input":[%s]%s,"stream":%s}
                """.formatted(Json.escape(resolveModel()), inputItems(messages, toolResults), toolJson, stream);
    }

    private static String toolsJson(List<ToolDefinition> tools) {
        if (tools.isEmpty()) {
            return "";
        }
        StringBuilder json = new StringBuilder(",\"tools\":[");
        for (int i = 0; i < tools.size(); i++) {
            if (i > 0) {
                json.append(",");
            }
            json.append(tools.get(i).toJson());
        }
        return json.append("]").toString();
    }

    private String inputItems(List<ChatMessage> messages, List<ToolCallResult> toolResults) {
        StringBuilder input = new StringBuilder();
        input.append(inputMessage("system", "input_text", systemPrompt));
        for (ChatMessage message : messages) {
            String contentType = "assistant".equals(message.role()) ? "output_text" : "input_text";
            input.append(",").append(inputMessage(message.role(), contentType, message.text()));
        }
        for (ToolCallResult result : toolResults) {
            input.append(",").append(functionCall(result.toolCall()));
            input.append(",").append(functionCallOutput(result));
        }
        return input.toString();
    }

    private static String inputMessage(String role, String contentType, String text) {
        return """
                {"type":"message","role":"%s","content":[{"type":"%s","text":"%s"}]}\
                """.formatted(Json.escape(role), Json.escape(contentType), Json.escape(text));
    }

    private static String functionCall(ToolCall toolCall) {
        return """
                {"type":"function_call","id":"%s","call_id":"%s","name":"%s","arguments":"%s"}\
                """.formatted(
                Json.escape(toolCall.id()),
                Json.escape(toolCall.callId()),
                Json.escape(toolCall.name()),
                Json.escape(toolCall.arguments()));
    }

    private static String functionCallOutput(ToolCallResult result) {
        return """
                {"type":"function_call_output","call_id":"%s","output":"%s"}\
                """.formatted(Json.escape(result.toolCall().callId()), Json.escape(result.output()));
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

    /**
     * 非ストリーミング応答の output 配列から function_call を取り出します。
     */
    private static List<ToolCall> responseToolCalls(String body) {
        List<ToolCall> toolCalls = new ArrayList<>();
        for (String item : outputItems(body)) {
            if (!"function_call".equals(JsonFields.stringOrNull(item, "type"))) {
                continue;
            }
            toolCalls.add(partialToolCall(item).toToolCall());
        }
        return List.copyOf(toolCalls);
    }

    private static List<String> outputItems(String body) {
        int outputKey = body.indexOf(Json.string("output"));
        if (outputKey < 0) {
            return List.of();
        }
        int arrayStart = body.indexOf('[', outputKey + Json.string("output").length());
        if (arrayStart < 0) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        int depth = 0;
        int objectStart = -1;
        boolean inString = false;
        boolean escaped = false;
        for (int i = arrayStart + 1; i < body.length(); i++) {
            char c = body.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) {
                    objectStart = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && objectStart >= 0) {
                    items.add(body.substring(objectStart, i + 1));
                    objectStart = -1;
                }
            } else if (c == ']' && depth == 0) {
                break;
            }
        }
        return items;
    }

    private static List<String> readStreamingResponse(java.io.InputStream body, StreamingResponseHandler handler)
            throws IOException {
        List<String> events = new ArrayList<>();
        Map<String, PartialToolCall> toolCalls = new LinkedHashMap<>();
        Set<String> completedToolCallIds = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data: ")) {
                    continue;
                }
                String data = line.substring("data: ".length());
                if ("[DONE]".equals(data)) {
                    return events;
                }
                events.add(data);
                streamingText(data).ifPresent(handler::onTextDelta);
                streamToolCall(data, toolCalls, completedToolCallIds).ifPresent(handler::onToolCall);
            }
        }
        return events;
    }

    private static Optional<String> streamingText(String body) {
        if (!STREAM_DELTA_TYPE_PATTERN.matcher(body).find()) {
            return Optional.empty();
        }
        return firstJsonString(body, STREAM_DELTA_PATTERN);
    }

    private static Optional<ToolCall> streamToolCall(
            String body,
            Map<String, PartialToolCall> toolCalls,
            Set<String> completedToolCallIds) {
        String eventType = JsonFields.stringOrNull(body, "type");
        if ("response.output_item.added".equals(eventType) && isFunctionCallItem(body)) {
            PartialToolCall toolCall = partialToolCall(body);
            if (completedToolCallIds.contains(toolCall.id)) {
                return Optional.empty();
            }
            toolCalls.put(toolCall.id, toolCall);
            return Optional.empty();
        }
        if ("response.function_call_arguments.delta".equals(eventType)) {
            String itemId = JsonFields.stringOrNull(body, "item_id");
            if (itemId == null) {
                return Optional.empty();
            }
            if (completedToolCallIds.contains(itemId)) {
                return Optional.empty();
            }
            PartialToolCall toolCall = toolCalls.computeIfAbsent(itemId, PartialToolCall::new);
            JsonFields.string(body, "delta", 0).ifPresent(toolCall.arguments::append);
            return Optional.empty();
        }
        if ("response.function_call_arguments.done".equals(eventType)) {
            PartialToolCall done = partialToolCall(body);
            if (completedToolCallIds.contains(done.id)) {
                return Optional.empty();
            }
            PartialToolCall current = toolCalls.getOrDefault(done.id, done);
            current.merge(done);
            toolCalls.remove(current.id);
            completedToolCallIds.add(current.id);
            return Optional.of(current.toToolCall());
        }
        if ("response.output_item.done".equals(eventType) && isFunctionCallItem(body)) {
            PartialToolCall done = partialToolCall(body);
            if (completedToolCallIds.contains(done.id)) {
                return Optional.empty();
            }
            PartialToolCall current = toolCalls.getOrDefault(done.id, done);
            current.merge(done);
            toolCalls.remove(current.id);
            completedToolCallIds.add(current.id);
            return Optional.of(current.toToolCall());
        }
        return Optional.empty();
    }

    private static boolean isFunctionCallItem(String body) {
        int itemStart = body.indexOf("\"item\"");
        int start = itemStart < 0 ? 0 : itemStart;
        return "function_call".equals(JsonFields.stringOrNull(body, "type", start));
    }

    private static PartialToolCall partialToolCall(String body) {
        int itemStart = body.indexOf("\"item\"");
        int start = itemStart < 0 ? 0 : itemStart;
        String id = JsonFields.stringOrNull(body, "id", start);
        if (id == null) {
            id = JsonFields.stringOrNull(body, "item_id");
        }
        String callId = JsonFields.stringOrNull(body, "call_id", start);
        String name = JsonFields.stringOrNull(body, "name", start);
        String arguments = JsonFields.stringOrNull(body, "arguments", start);
        PartialToolCall toolCall = new PartialToolCall(id == null ? "unknown" : id);
        toolCall.callId = callId;
        toolCall.name = name;
        if (arguments != null) {
            toolCall.arguments.setLength(0);
            toolCall.arguments.append(arguments);
        }
        return toolCall;
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
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(modelsEndpoint)
                .timeout(timeout)
                .GET();
        addAuthorization(requestBuilder);
        HttpRequest request = requestBuilder.build();
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
            models.add(Json.unescape(matcher.group(1)));
        }
        return models;
    }

    private static Optional<String> firstJsonString(String body, Pattern pattern) {
        Matcher matcher = pattern.matcher(body);
        if (matcher.find()) {
            return Optional.of(Json.unescape(matcher.group(1)));
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

    private void addAuthorization(HttpRequest.Builder requestBuilder) {
        String auth_key = !apiKey.isBlank() ? apiKey.strip() : null;
        if( auth_key == null ) {
            if( this.responsesEndpoint.getHost().contains("openai") ) {
                auth_key = env("OPENAI_API_KEY", "").strip();
            }
        }
        if (auth_key!=null&&auth_key.length()>0) {
            requestBuilder.header("Authorization", "Bearer " + auth_key);
        }
    }

    public record Config(URI baseUri, String model, String systemPrompt, Duration timeout, String apiKey) {
        public Config(URI baseUri, String model, String systemPrompt, Duration timeout) {
            this(baseUri, model, systemPrompt, timeout, "");
        }
    }

    /** 保存前検証で特定したモデルと応答です。 */
    public record Verification(String model, String response) {
    }

    private static final class PartialToolCall {
        private final String id;
        private String callId;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        private PartialToolCall(String id) {
            this.id = id;
        }

        private void merge(PartialToolCall other) {
            if (other.callId != null) {
                callId = other.callId;
            }
            if (other.name != null) {
                name = other.name;
            }
            if (!other.arguments.isEmpty()) {
                arguments.setLength(0);
                arguments.append(other.arguments);
            }
        }

        private ToolCall toToolCall() {
            return new ToolCall(
                    id,
                    callId == null ? id : callId,
                    name == null ? "unknown" : name,
                    arguments.isEmpty() ? "{}" : arguments.toString());
        }
    }
}
