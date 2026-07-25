package server;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import json.Json;
import json.JsonFields;
import llm.LLM;
import llm.LlmOpenAI;

/** Group ごとの LLM 設定を .local 配下へ保存します。 */
final class GroupLlmSettings {
    private static final String FILE_NAME = "llm.json";
    static final String DEFAULT_BASE_URL = LlmOpenAI.DEFAULT_BASE_URI.toString();

    static final String LFM25_MODEL = LlmOpenAI.DEFAULT_MODEL_PATTERN;
    static final String LFM25_BOT_NAME = "りり";
    static final String LFM25_PROMPT = """
            あなたは会話AI「${BOT_NAME}」。**相手に読み上げる日本語のセリフだけを出力すること**
            HTMLコメントとシステム通知は会話文へ引用・説明・復唱しない。内部データと内部データの文字列を会話文に出力しない。
            未登録の相手が氏名を名乗った場合は、会話文を出力する前に assign_user_name 関数を1回実行する。
            関数には、直前の HTMLコメント内の内部データと、相手が名乗った氏名を渡す。文章だけで登録完了を表現してはならない。
            話題をランダムに決めて、次の会話をすすめること。
            """;
    static final String LFM25_FIRST_MEETING_PROMPT = "未登録の相手です。会話文は「こんにちは！お名前を教えてください。」だけを出力する。";
    static final String LFM25_KNOWN_PERSON_PROMPT = "登録済みの相手です。通知にある人を呼びかけ、こんにちはと今日の体調を尋ねる1文だけを出力する。";
    static final String LFM25_ASSIGNED_PROMPT = "新しく登録された相手です。通知にある人を呼びかけ、こんにちはと今日の体調を尋ねる1文だけを出力する。";
    static final String LFM25_UNKNOWN_PERSON_MESSAGE_FORMAT = "<!-- assign_user_name の内部データ: ${FACE_ID} -->\n未登録の相手です。";
    static final String LFM25_KNOWN_PERSON_MESSAGE_FORMAT = "<!-- 登録済みの相手は ${USER_NAME} さんです。assign_user_name の内部データ: ${FACE_ID} -->";
    static final String LFM25_ASSIGNED_PERSON_MESSAGE_FORMAT = "<!-- ${USER_NAME} さんとして登録しました。assign_user_name の内部データ: ${FACE_ID} -->";

    static final String GEMMA4_MODEL = "gemma[-_]?4[-]?e2b";
    static final String GEMMA4_BOT_NAME = "ジェマ";
    static final String GEMMA4_PROMPT = """
            あなたは会話AIの「${BOT_NAME}」です。相手と親しみのあるフレンドリーな会話をします。
            **あなたのセリフのみ出力して下さい**
            """;
    static final String GEMMA4_FIRST_MEETING_PROMPT = "挨拶をしてお名前を聞いてみましょう。名前がわかったらツールをコール";
    static final String GEMMA4_KNOWN_PERSON_PROMPT = "友人として挨拶してください。";
    static final String GEMMA4_ASSIGNED_PROMPT = "友人として挨拶してください。";
    static final String GEMMA4_UNKNOWN_PERSON_MESSAGE_FORMAT = "だれか他の人が居ます(trackId:${FACE_ID})。";
    static final String GEMMA4_KNOWN_PERSON_MESSAGE_FORMAT = "ユーザ名 ${USER_NAME}(trackId:${FACE_ID})と出会いました。";
    static final String GEMMA4_ASSIGNED_PERSON_MESSAGE_FORMAT = "ユーザ名 ${USER_NAME}(trackId:${FACE_ID})を登録しました。";

    static final String OPENAI_BASE_URL = LlmOpenAI.OPENAI_BASE_URI.toString();
    static final String OPENAI_MODEL = LlmOpenAI.OPENAI_MODEL_PATTERN;
    static final String OPENAI_BOT_NAME = "チャッピー";
    static final String OPENAI_PROMPT = """
            あなたは会話AIの「${BOT_NAME}」です。目の前の相手と親しみのある会話をします。AIの発言だけを出力して下さい。
            あなたのセリフのみ出力して下さい。
            """;
    static final String OPENAI_FIRST_MEETING_PROMPT = "挨拶をしてお名前を聞いてみましょう。名前がわかったらツールをコール";
    static final String OPENAI_KNOWN_PERSON_PROMPT = "友人として挨拶してください。";
    static final String OPENAI_ASSIGNED_PROMPT = "友人として挨拶してください。";
    static final String OPENAI_UNKNOWN_PERSON_MESSAGE_FORMAT = "だれか他の人が居ます(trackId:${FACE_ID})。";
    static final String OPENAI_KNOWN_PERSON_MESSAGE_FORMAT = "ユーザ名 ${USER_NAME}(trackId:${FACE_ID})と出会いました。";
    static final String OPENAI_ASSIGNED_PERSON_MESSAGE_FORMAT = "ユーザ名 ${USER_NAME}(trackId:${FACE_ID})を登録しました。";

    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final String botName;
    private final String systemPrompt;
    private final String firstMeetingPrompt;
    private final String knownPersonPrompt;
    private final String assignedPrompt;
    private final String unknownPersonMessageFormat;
    private final String knownPersonMessageFormat;
    private final String assignedPersonMessageFormat;

    GroupLlmSettings(
            String baseUrl, String model, String apiKey, String botName, String systemPrompt,
            String firstMeetingPrompt, String knownPersonPrompt, String assignedPrompt,
            String unknownPersonMessageFormat, String knownPersonMessageFormat, String assignedPersonMessageFormat) {
        this.baseUrl = required(baseUrl, "baseUrl");
        this.model = required(model, "model");
        this.apiKey = text(apiKey);
        this.botName = text(botName);
        this.systemPrompt = text(systemPrompt);
        this.firstMeetingPrompt = text(firstMeetingPrompt);
        this.knownPersonPrompt = text(knownPersonPrompt);
        this.assignedPrompt = text(assignedPrompt);
        this.unknownPersonMessageFormat = text(unknownPersonMessageFormat);
        this.knownPersonMessageFormat = text(knownPersonMessageFormat);
        this.assignedPersonMessageFormat = text(assignedPersonMessageFormat);
    }

    public static GroupLlmSettings custom(String systemPrompt) {
        return new GroupLlmSettings(DEFAULT_BASE_URL, LFM25_MODEL, "",
            LFM25_BOT_NAME, systemPrompt, LFM25_FIRST_MEETING_PROMPT, LFM25_KNOWN_PERSON_PROMPT, LFM25_ASSIGNED_PROMPT,
            LFM25_UNKNOWN_PERSON_MESSAGE_FORMAT, LFM25_KNOWN_PERSON_MESSAGE_FORMAT, LFM25_ASSIGNED_PERSON_MESSAGE_FORMAT
        );
    }

    static GroupLlmSettings defaults() {
        return custom(LFM25_PROMPT);
    }

    static GroupLlmSettings defaults(String groupId) {
        if ("group-2".equals(groupId)) {
            return new GroupLlmSettings(DEFAULT_BASE_URL, GEMMA4_MODEL, "",
                GEMMA4_BOT_NAME, GEMMA4_PROMPT, GEMMA4_FIRST_MEETING_PROMPT, GEMMA4_KNOWN_PERSON_PROMPT, GEMMA4_ASSIGNED_PROMPT,
                GEMMA4_UNKNOWN_PERSON_MESSAGE_FORMAT, GEMMA4_KNOWN_PERSON_MESSAGE_FORMAT, GEMMA4_ASSIGNED_PERSON_MESSAGE_FORMAT
            );
        } else if ("group-3".equals(groupId)) {
            return new GroupLlmSettings(OPENAI_BASE_URL, OPENAI_MODEL, "",
                OPENAI_BOT_NAME, OPENAI_PROMPT, OPENAI_FIRST_MEETING_PROMPT, OPENAI_KNOWN_PERSON_PROMPT, OPENAI_ASSIGNED_PROMPT,
                OPENAI_UNKNOWN_PERSON_MESSAGE_FORMAT, OPENAI_KNOWN_PERSON_MESSAGE_FORMAT, OPENAI_ASSIGNED_PERSON_MESSAGE_FORMAT
            );
        } else {
            return defaults();
        }
    }

    static GroupLlmSettings load(Path localRoot, String groupId) throws IOException {
        Path file = file(localRoot, groupId);
        if (!Files.isRegularFile(file)) return defaults(groupId);
        return fromJson(Files.readString(file, StandardCharsets.UTF_8), groupId);
    }

    static GroupLlmSettings fromJson(String json, String groupId) {
        GroupLlmSettings defaults = defaults(groupId);
        return new GroupLlmSettings(
                defaultIfBlank(JsonFields.stringOrDefault(json, "baseUrl", ""), defaults.baseUrl),
                defaultIfBlank(JsonFields.stringOrDefault(json, "model", ""), defaults.model),
                defaultIfBlank(JsonFields.stringOrDefault(json, "apiKey", ""), defaults.apiKey),
                defaultIfBlank(JsonFields.stringOrDefault(json, "botName", ""), defaults.botName),
                defaultIfBlank(JsonFields.stringOrDefault(json, "systemPrompt", ""), defaults.systemPrompt),
                defaultIfBlank(JsonFields.stringOrDefault(json, "firstMeetingPrompt", ""), defaults.firstMeetingPrompt),
                defaultIfBlank(JsonFields.stringOrDefault(json, "knownPersonPrompt", ""), defaults.knownPersonPrompt),
                defaultIfBlank(JsonFields.stringOrDefault(json, "assignedPrompt", ""), defaults.assignedPrompt),
                defaultIfBlank(JsonFields.stringOrDefault(json, "unknownPersonMessageFormat", ""), defaults.unknownPersonMessageFormat),
                defaultIfBlank(JsonFields.stringOrDefault(json, "knownPersonMessageFormat", ""), defaults.knownPersonMessageFormat),
                defaultIfBlank(JsonFields.stringOrDefault(json, "assignedPersonMessageFormat", ""), defaults.assignedPersonMessageFormat));
    }

    String systemPrompt() { return systemPrompt; }
    PromptTemplates promptTemplates() {
        return new PromptTemplates(botName, systemPrompt, firstMeetingPrompt, knownPersonPrompt, assignedPrompt,
                unknownPersonMessageFormat, knownPersonMessageFormat, assignedPersonMessageFormat);
    }

    void save(Path localRoot, String groupId) throws IOException {
        Path target = file(localRoot, groupId);
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(FILE_NAME + ".tmp");
        Files.writeString(temporary, toStorageJson(defaults(groupId)) + "\n", StandardCharsets.UTF_8);
        try { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
    }

    LLM.Config toConfig() {
        LLM.Config defaults = LlmOpenAI.fromEnvironment();
        URI uri = URI.create(baseUrl);
        return new LLM.Config(uri, model, defaults.timeout(), resolveApiKey(uri, apiKey, System.getenv("OPENAI_API_KEY")));
    }

    String toJson() {
        return Json.object(Json.fields("baseUrl", baseUrl, "model", model, "apiKey", apiKey,
                "botName", botName, "systemPrompt", systemPrompt,
                "firstMeetingPrompt", firstMeetingPrompt,
                "knownPersonPrompt", knownPersonPrompt,
                "assignedPrompt", assignedPrompt,
                "unknownPersonMessageFormat", unknownPersonMessageFormat,
                "knownPersonMessageFormat", knownPersonMessageFormat,
                "assignedPersonMessageFormat", assignedPersonMessageFormat));
    }

    String toSettingsJson(GroupLlmSettings defaults) {
        return Json.object(Json.fields("baseUrl", baseUrl, "model", model, "apiKey", apiKey,
                "botName", botName, "systemPrompt", systemPrompt,
                "firstMeetingPrompt", firstMeetingPrompt,
                "knownPersonPrompt", knownPersonPrompt,
                "assignedPrompt", assignedPrompt,
                "unknownPersonMessageFormat", unknownPersonMessageFormat,
                "knownPersonMessageFormat", knownPersonMessageFormat,
                "assignedPersonMessageFormat", assignedPersonMessageFormat,
                "defaults", Json.raw(defaults.toJson())));
    }

    /** 既定値と同じ項目は空欄にして、変更された項目だけを保存します。 */
    String toStorageJson(GroupLlmSettings defaults) {
        return Json.object(Json.fields("baseUrl", stored(baseUrl, defaults.baseUrl), "model", stored(model, defaults.model),
                "apiKey", stored(apiKey, defaults.apiKey), "botName", stored(botName, defaults.botName),
                "systemPrompt", stored(systemPrompt, defaults.systemPrompt),
                "firstMeetingPrompt", stored(firstMeetingPrompt, defaults.firstMeetingPrompt),
                "knownPersonPrompt", stored(knownPersonPrompt, defaults.knownPersonPrompt),
                "assignedPrompt", stored(assignedPrompt, defaults.assignedPrompt),
                "unknownPersonMessageFormat", stored(unknownPersonMessageFormat, defaults.unknownPersonMessageFormat),
                "knownPersonMessageFormat", stored(knownPersonMessageFormat, defaults.knownPersonMessageFormat),
                "assignedPersonMessageFormat", stored(assignedPersonMessageFormat, defaults.assignedPersonMessageFormat)));
    }

    private static Path file(Path localRoot, String groupId) { return localRoot.resolve(groupId).resolve(FILE_NAME); }
    private static String text(String value) { return value == null ? "" : value.strip(); }
    private static String required(String value, String name) {
        String result = text(value);
        if (result.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return result;
    }
    private static String defaultIfBlank(String value, String defaultValue) { return text(value).isEmpty() ? defaultValue : text(value); }
    private static String stored(String value, String defaultValue) { return text(value).equals(text(defaultValue)) ? "" : value; }

    /** OpenAI 公式 API だけは、空欄の設定値をサーバー環境変数で補完します。 */
    static String resolveApiKey(URI baseUri, String configuredApiKey, String environmentApiKey) {
        String configured = text(configuredApiKey);
        if (!configured.isEmpty()) return configured;
        String host = baseUri.getHost();
        if (host == null || !(host.equalsIgnoreCase("openai.com") || host.toLowerCase().endsWith(".openai.com"))) return "";
        String environment = text(environmentApiKey);
        if (environment.isEmpty()) throw new IllegalArgumentException("OPENAI_API_KEY must be set when API KEY is empty for an openai.com Base URL");
        return environment;
    }
}
