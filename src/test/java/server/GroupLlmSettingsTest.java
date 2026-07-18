package server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import json.JsonFields;
import org.junit.jupiter.api.Test;

class GroupLlmSettingsTest {
    @Test
    void savesAndLoadsSettingsInGroupDirectory() throws Exception {
        Path localRoot = Path.of("tmp", "group-settings-test-" + System.nanoTime());
        try {
            GroupLlmSettings settings = new GroupLlmSettings(
                    "http://example.test/v1", "test-model", "secret", "メインプロンプト");

            settings.save(localRoot, "group-2");
            GroupLlmSettings loaded = GroupLlmSettings.load(localRoot, "group-2");

            assertEquals(settings.toJson(), loaded.toJson());
            assertEquals("http://example.test/v1", loaded.toConfig().baseUri().toString());
        } finally {
            Files.deleteIfExists(localRoot.resolve("group-2").resolve("llm.json"));
            Files.deleteIfExists(localRoot.resolve("group-2"));
            Files.deleteIfExists(localRoot);
        }
    }

    @Test
    void usesSpecifiedDefaultsWhenNoFileExists() throws Exception {
        Path localRoot = Path.of("tmp", "group-settings-default-test-" + System.nanoTime());
        try {
            GroupLlmSettings defaults = GroupLlmSettings.load(localRoot, "group-1");

            assertEquals(GroupLlmSettings.DEFAULT_BASE_URL, defaults.toConfig().baseUri().toString());
            assertEquals(GroupLlmSettings.DEFAULT_LFM25_MODEL, defaults.toConfig().model());
            assertEquals(GroupLlmSettings.DEFAULT_LFM25_PROMPT.strip(), defaults.systemPrompt());
        } finally {
            Files.deleteIfExists(localRoot);
        }
    }

    @Test
    void usesGemmaDefaultsForGroup2WhenNoFileExists() throws Exception {
        Path localRoot = Path.of("tmp", "group-settings-gemma-default-test-" + System.nanoTime());
        try {
            GroupLlmSettings defaults = GroupLlmSettings.load(localRoot, "group-2");

            assertEquals(GroupLlmSettings.DEFAULT_BASE_URL, JsonFields.string(defaults.toJson(), "baseUrl"));
            assertEquals(GroupLlmSettings.DEFAULT_GEMMA4_MODEL, JsonFields.string(defaults.toJson(), "model"));
            assertEquals(GroupLlmSettings.DEFAULT_GEMMA4_PROMPT.strip(), JsonFields.string(defaults.toJson(), "systemPrompt"));
            assertEquals("", JsonFields.string(defaults.toJson(), "apiKey"));
        } finally {
            Files.deleteIfExists(localRoot);
        }
    }

    @Test
    void usesFormerGroup2OpenAiDefaultsForGroup3() {
        GroupLlmSettings defaults = GroupLlmSettings.defaults("group-3");

        assertEquals(GroupLlmSettings.DEFAULT_OPENAI_BASE_URL, JsonFields.string(defaults.toJson(), "baseUrl"));
        assertEquals(GroupLlmSettings.DEFAULT_OPENAI_MODEL, JsonFields.string(defaults.toJson(), "model"));
        assertEquals(GroupLlmSettings.DEFAULT_OPENAI_PROMPT.strip(), JsonFields.string(defaults.toJson(), "systemPrompt"));
    }

    @Test
    void loadsDefaultsForBlankFieldsAndStoresOnlyChanges() throws Exception {
        Path localRoot = Path.of("tmp", "group-settings-blank-test-" + System.nanoTime());
        try {
            Files.createDirectories(localRoot.resolve("group-2"));
            Files.writeString(localRoot.resolve("group-2").resolve("llm.json"),
                    "{\"baseUrl\":\"\",\"model\":\"\",\"apiKey\":\"\",\"systemPrompt\":\"\"}");

            GroupLlmSettings loaded = GroupLlmSettings.load(localRoot, "group-2");

            assertEquals(GroupLlmSettings.DEFAULT_BASE_URL, loaded.toConfig().baseUri().toString());
            assertEquals(GroupLlmSettings.DEFAULT_GEMMA4_MODEL, loaded.toConfig().model());
            assertEquals(GroupLlmSettings.defaults("group-2").toJson(), loaded.toJson());

            loaded.save(localRoot, "group-2");
            String saved = Files.readString(localRoot.resolve("group-2").resolve("llm.json"));
            assertEquals("", JsonFields.string(saved, "baseUrl"));
            assertEquals("", JsonFields.string(saved, "model"));
            assertEquals("", JsonFields.string(saved, "systemPrompt"));
        } finally {
            Files.deleteIfExists(localRoot.resolve("group-2").resolve("llm.json"));
            Files.deleteIfExists(localRoot.resolve("group-2"));
            Files.deleteIfExists(localRoot);
        }
    }

    @Test
    void usesEnvironmentApiKeyForOpenAiHostWhenSettingIsEmpty() {
        assertEquals("environment-key", GroupLlmSettings.resolveApiKey(
                URI.create("https://api.openai.com/v1"), "", "environment-key"));
        assertThrows(IllegalArgumentException.class, () -> GroupLlmSettings.resolveApiKey(
                URI.create("https://api.openai.com/v1"), "", ""));
        assertEquals("", GroupLlmSettings.resolveApiKey(
                URI.create("http://localhost:8767/v1"), "", "environment-key"));
    }

    @Test
    void rejectsBlankBaseUrlWhenSaving() {
        assertThrows(IllegalArgumentException.class, () -> new GroupLlmSettings("", "model", "", ""));
    }
}
