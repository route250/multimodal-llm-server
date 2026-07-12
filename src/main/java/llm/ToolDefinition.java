package llm;

import json.Json;

/**
 * Responses API に渡す function tool 定義です。
 */
public record ToolDefinition(String name, String description, String parametersJson) {
    public ToolDefinition {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        if (parametersJson == null || parametersJson.isBlank()) {
            throw new IllegalArgumentException("parametersJson must not be blank");
        }
    }

    String toJson() {
        return """
                {"type":"function","name":"%s","description":"%s","parameters":%s}\
                """.formatted(Json.escape(name), Json.escape(description), parametersJson);
    }
}
