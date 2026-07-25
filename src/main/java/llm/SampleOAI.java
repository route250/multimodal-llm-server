package llm;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.http.StreamResponse;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.models.Model;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.services.blocking.ModelService;
import com.openai.models.responses.ResponseInputItem.Message.Role;
import java.util.ArrayList;
import java.util.List;

import llm.tools.WeatherTool;;

/** OpenAI 互換 API へリクエストする動作確認用プログラムです。 */
public class SampleOAI {


    public static void main( String[] args ) {
        // non_streaming_sample();
        // tool_call_sample();
        sample_impl_sample();
    }

    public static void non_streaming_sample() {
        System.out.println("aaa");
        OpenAIClient client = OpenAIOkHttpClient.builder().baseUrl("http://localhost:8767").apiKey("p").build();

        // 接続先が公開しているモデル ID を取得して表示します。
        ModelService modelService = client.models();
        System.out.println("Available models:");
        for (Model model : modelService.list().items()) {
            System.out.println(model.id());
        }

        ResponseCreateParams params = ResponseCreateParams.builder()
                .input("おはようといえば、なんと返事する？")
                .model("LFM2.5-1.2B-JP-202606-GGUF")
                .reasoning(Reasoning.builder().effort(ReasoningEffort.NONE).build())
                .build();

        Response response = client.responses().create(params);

        System.out.println(response.output());
    }

    /** 関数ツールの呼び出しと、実行結果の送信を確認します。 */
    public static void tool_call_sample() {
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .baseUrl("http://localhost:8767")
                .apiKey("p")
                .build();
        List<ResponseInputItem> messages = new ArrayList<>();
        messages.add(ResponseInputItem.ofMessage(
                ResponseInputItem.Message.builder()
                        .role(Role.USER)
                        .addInputTextContent("東京の現在の天気を教えてください。必ず get_weather ツールを使用してください。")
                        .build()));
        int messageCountBeforeToolCall = messages.size();
        Response toolCallResponse = client.responses().create(toolCallRequest(messages));

        toolCallResponse.output().stream()
                .filter(output -> output.isFunctionCall())
                .map(output -> output.asFunctionCall())
                .forEach(functionCall -> {
                    System.out.printf("Tool call: %s(%s)%n", functionCall.name(), functionCall.arguments());
                    messages.add(ResponseInputItem.ofFunctionCall(functionCall));
                    messages.add(ResponseInputItem.ofFunctionCallOutput(
                            ResponseInputItem.FunctionCallOutput.builder()
                                    .callId(functionCall.callId())
                                    .output(new WeatherTool().exec(functionCall))
                                    .build()));
                });

        Response response = messages.size() == messageCountBeforeToolCall
                ? toolCallResponse
                : client.responses().create(toolCallRequest(messages));
        System.out.println(response.output());
    }

    /** 関数ツールの呼び出しと最終応答をストリームで受信します。 */
    public static void tool_call_streaming_sample() {
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .baseUrl("http://localhost:8767")
                .apiKey("p")
                .build();
        List<ResponseInputItem> messages = new ArrayList<>();
        messages.add(ResponseInputItem.ofMessage(
                ResponseInputItem.Message.builder()
                        .role(Role.USER)
                        .addInputTextContent("東京の現在の天気を教えてください。必ず get_weather ツールを使用してください。")
                        .build()));

        int messageCountBeforeToolCall = messages.size();
        streamResponse(client, toolCallRequest(messages), messages);
        if (messages.size() > messageCountBeforeToolCall) {
            streamResponse(client, toolCallRequest(messages), messages);
        }
    }

    /** SampleImpl を使い、ストリームと関数ツールをまとめて実行します。 */
    public static void sample_impl_sample() {
        LlmOpenAI llm = new LlmOpenAI(
                "http://localhost:8767", "p", "LFM2.5-1.2B-JP-202606-GGUF", false);
        List<Message> responses = llm.call(
                List.of(new Message(Message.Role.User, "東京の現在の天気を教えてください。必ず get_weather ツールを使用してください。")),
                List.of(new WeatherTool()),
                System.out::print);
        System.out.printf("%nAssistant message count: %d%n", responses.size());
    }

    /** 関数ツールを指定した Responses API リクエストを作成します。 */
    private static ResponseCreateParams toolCallRequest(List<ResponseInputItem> messages) {
        return ResponseCreateParams.builder()
                .inputOfResponse(messages)
                .model("LFM2.5-1.2B-JP-202606-GGUF")
                .reasoning(Reasoning.builder().effort(ReasoningEffort.NONE).build())
                .addTool( new WeatherTool().definiton() )
                .build();
    }

    /** ストリームからツール呼び出しとテキスト断片を受け取り、メッセージ配列へ追加します。 */
    private static void streamResponse(
            OpenAIClient client, ResponseCreateParams request, List<ResponseInputItem> messages) {
        try (StreamResponse<ResponseStreamEvent> stream = client.responses().createStreaming(request)) {
            stream.stream().forEach(event -> {
                if (event.isOutputTextDelta()) {
                    System.out.print(event.asOutputTextDelta().delta());
                    System.out.flush();
                }
                if (event.isOutputItemDone()) {
                    var output = event.asOutputItemDone().item();
                    if (output.isFunctionCall()) {
                        ResponseFunctionToolCall functionCall = output.asFunctionCall();
                        System.out.printf("Tool call: %s(%s)%n", functionCall.name(), functionCall.arguments());
                        messages.add(ResponseInputItem.ofFunctionCall(functionCall));
                        messages.add(ResponseInputItem.ofFunctionCallOutput(
                                ResponseInputItem.FunctionCallOutput.builder()
                                        .callId(functionCall.callId())
                                        .output(new WeatherTool().exec(functionCall))
                                        .build()));
                    }
                }
            });
        }
        System.out.println();
    }

}
