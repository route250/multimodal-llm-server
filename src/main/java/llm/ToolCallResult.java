package llm;

/**
 * LLM が要求したツール呼び出しに対する実行結果です。
 */
public record ToolCallResult(ToolCall toolCall, String output) {
    public ToolCallResult {
        if (toolCall == null) {
            throw new IllegalArgumentException("toolCall must not be null");
        }
        if (output == null || output.isBlank()) {
            output = "{}";
        }
    }
}
