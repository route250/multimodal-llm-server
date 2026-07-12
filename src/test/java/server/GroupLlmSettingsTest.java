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
            assertEquals(GroupLlmSettings.DEFAULT_MODEL, defaults.toConfig().model());
            assertEquals(GroupLlmSettings.DEFAULT_SYSTEM_PROMPT, defaults.toConfig().systemPrompt());
        } finally {
            Files.deleteIfExists(localRoot);
        }
    }

    @Test
    void usesOpenAiDefaultsForGroup2WhenNoFileExists() throws Exception {
        Path localRoot = Path.of("tmp", "group-settings-openai-default-test-" + System.nanoTime());
        try {
            GroupLlmSettings defaults = GroupLlmSettings.load(localRoot, "group-2");

            assertEquals("https://api.openai.com/v1", JsonFields.string(defaults.toJson(), "baseUrl"));
            assertEquals("gpt-4.1-nano", JsonFields.string(defaults.toJson(), "model"));
            assertEquals("", JsonFields.string(defaults.toJson(), "apiKey"));
        } finally {
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
