package llm;

import java.util.List;

/**
 * LLM の非ストリーミング応答です。
 */
public record LanguageModelResponse(String text, List<ToolCall> toolCalls) {
    public LanguageModelResponse {
        text = text == null ? "" : text;
        toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
    }
}
