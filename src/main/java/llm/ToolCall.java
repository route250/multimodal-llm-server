package llm;

/**
 * LLM が要求したツール呼び出しです。
 */
public record ToolCall(String id, String callId, String name, String arguments) {
    public ToolCall {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be blank");
        }
        if (callId == null || callId.isBlank()) {
            throw new IllegalArgumentException("callId must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (arguments == null || arguments.isBlank()) {
            arguments = "{}";
        }
    }
}
