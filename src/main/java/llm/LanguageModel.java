package llm;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * テキスト入力からチャット応答を生成する LLM の最小インターフェースです。
 */
public interface LanguageModel {
    String respond(String userText);

    default String respond(List<Message> messages) {
        return respond(lastUserText(messages));
    }

    default LanguageModelResponse respond(
            List<Message> messages,
            List<ToolDefinition> tools) {
        return respond(messages, tools, List.of());
    }

    /**
     * ツール定義と実行済みツールの結果を含めて、非ストリーミングで応答を生成します。
     */
    default LanguageModelResponse respond(
            List<Message> messages,
            List<ToolDefinition> tools,
            List<ToolCallResult> toolResults) {
        StringBuilder text = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        respondStreamingEvents(messages, tools, toolResults, new StreamingResponseHandler() {
            @Override
            public void onTextDelta(String delta) {
                text.append(delta);
            }

            @Override
            public void onToolCall(ToolCall toolCall) {
                toolCalls.add(toolCall);
            }
        });
        return new LanguageModelResponse(text.toString(), toolCalls);
    }

    default void respondStreaming(String userText, Consumer<String> onDelta) {
        onDelta.accept(respond(userText));
    }

    default void respondStreaming(List<Message> messages, Consumer<String> onDelta) {
        respondStreamingEvents(messages, new StreamingResponseHandler() {
            @Override
            public void onTextDelta(String delta) {
                onDelta.accept(delta);
            }
        });
    }

    default void respondStreamingEvents(List<Message> messages, StreamingResponseHandler handler) {
        respondStreaming(lastUserText(messages), handler::onTextDelta);
    }

    default void respondStreamingEvents(
            List<Message> messages,
            List<ToolDefinition> tools,
            StreamingResponseHandler handler) {
        respondStreamingEvents(messages, handler);
    }

    default void respondStreamingEvents(
            List<Message> messages,
            List<ToolDefinition> tools,
            List<ToolCallResult> toolResults,
            StreamingResponseHandler handler) {
        respondStreamingEvents(messages, tools, handler);
    }

    private static String lastUserText(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new IllegalArgumentException("messages must not be empty");
        }
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message message = messages.get(i);
            if (Message.Role.User==message.role()) {
                return message.message();
            }
        }
        throw new IllegalArgumentException("messages must contain a user message");
    }
}
