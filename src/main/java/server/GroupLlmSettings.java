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
    static final String DEFAULT_MODEL = LlmOpenAI.DEFAULT_MODEL_PATTERN;
    static final String DEFAULT_SYSTEM_PROMPT = ChatClient.DEFAULT_SYSTEM_PROMPT;
    private final String baseUrl;
    private final String model;
    private final String apiKey;
    private final String systemPrompt;

    GroupLlmSettings(String baseUrl, String model, String apiKey, String systemPrompt) {
        this.baseUrl = required(baseUrl, "baseUrl");
        this.model = required(model, "model");
        this.apiKey = text(apiKey);
        this.systemPrompt = text(systemPrompt);
    }

    static GroupLlmSettings defaults(String groupId) {
        if ("group-2".equals(groupId)) {
            return new GroupLlmSettings(
                LlmOpenAI.OPENAI_BASE_URI.toString(),
                LlmOpenAI.OPENAI_MODEL_PATTERN,
                "", DEFAULT_SYSTEM_PROMPT);
        }
        return new GroupLlmSettings(DEFAULT_BASE_URL, DEFAULT_MODEL, "", DEFAULT_SYSTEM_PROMPT);
    }

    static GroupLlmSettings load(Path localRoot, String groupId) throws IOException {
        Path file = file(localRoot, groupId);
        if (!Files.isRegularFile(file)) {
            return defaults(groupId);
        }
        String json = Files.readString(file, StandardCharsets.UTF_8);
        return new GroupLlmSettings(
                defaultIfBlank(JsonFields.stringOrDefault(json, "baseUrl", ""), DEFAULT_BASE_URL),
                JsonFields.string(json, "model"),
                JsonFields.stringOrDefault(json, "apiKey", ""),
                JsonFields.stringOrDefault(json, "systemPrompt", ""));
    }

    static GroupLlmSettings fromJson(String json) {
        return new GroupLlmSettings(
                JsonFields.stringOrDefault(json, "baseUrl", ""),
                JsonFields.string(json, "model"),
                JsonFields.stringOrDefault(json, "apiKey", ""),
                JsonFields.stringOrDefault(json, "systemPrompt", ""));
    }

    String systemPrompt() {
        return systemPrompt;
    }

    void save(Path localRoot, String groupId) throws IOException {
        Path target = file(localRoot, groupId);
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(FILE_NAME + ".tmp");
        Files.writeString(temporary, toJson() + "\n", StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    LLM.Config toConfig() {
        LLM.Config defaults = LlmOpenAI.fromEnvironment();
        URI uri = URI.create(baseUrl);
        return new LLM.Config(
                uri,
                model,
                defaults.timeout(),
                resolveApiKey(uri, apiKey, System.getenv("OPENAI_API_KEY")));
    }

    String toJson() {
        return Json.object(Json.fields(
                "baseUrl", baseUrl,
                "model", model,
                "apiKey", apiKey,
                "systemPrompt", systemPrompt));
    }

    String toSettingsJson(GroupLlmSettings defaults) {
        return Json.object(Json.fields(
                "baseUrl", baseUrl,
                "model", model,
                "apiKey", apiKey,
                "systemPrompt", systemPrompt,
                "defaults", Json.raw(defaults.toJson())));
    }

    private static Path file(Path localRoot, String groupId) {
        return localRoot.resolve(groupId).resolve(FILE_NAME);
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }

    private static String required(String value, String name) {
        String result = text(value);
        if (result.isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return result;
    }

    /** OpenAI 公式 API だけは、空欄の設定値をサーバー環境変数で補完します。 */
    static String resolveApiKey(URI baseUri, String configuredApiKey, String environmentApiKey) {
        String configured = text(configuredApiKey);
        if (!configured.isEmpty()) {
            return configured;
        }
        String host = baseUri.getHost();
        if (host == null || !(host.equalsIgnoreCase("openai.com") || host.toLowerCase().endsWith(".openai.com"))) {
            return "";
        }
        String environment = text(environmentApiKey);
        if (environment.isEmpty()) {
            throw new IllegalArgumentException("OPENAI_API_KEY must be set when API KEY is empty for an openai.com Base URL");
        }
        return environment;
    }

    private static String defaultIfBlank(String value, String defaultValue) {
        String result = text(value);
        return result.isEmpty() ? defaultValue : result;
    }
}
