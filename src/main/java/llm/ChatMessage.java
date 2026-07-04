package llm;

/**
 * LLM に渡す会話履歴の 1 メッセージです。
 */
public record ChatMessage(String role, String text) {
    public ChatMessage {
        if (!"user".equals(role) && !"assistant".equals(role)) {
            throw new IllegalArgumentException("role must be user or assistant: " + role);
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("text must not be blank");
        }
    }
}
