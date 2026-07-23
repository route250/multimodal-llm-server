package llm;

/**
 * LLM に渡す会話履歴の 1 メッセージです。
 */
public record ChatMessage(Role role, String text) {
    public static enum Role {
        User, Assistant, System, Developer
    }
    public ChatMessage {
        if (role == null) {
            throw new IllegalArgumentException("role must be user or assistant: null");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }
}
