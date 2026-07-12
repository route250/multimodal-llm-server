package llm;

/**
 * LLM のストリーミング応答を、発話テキストとツール呼び出しに分けて受け取ります。
 */
public interface StreamingResponseHandler {
    void onTextDelta(String delta);

    default void onToolCall(ToolCall toolCall) {
    }
}
