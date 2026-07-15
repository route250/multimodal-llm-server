package llm;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.core.http.StreamResponse;
import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;
import com.openai.models.models.Model;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseCreateParams.Builder;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseStreamEvent;
import com.openai.services.blocking.ModelService;
import com.openai.models.responses.ResponseInputItem.Message.Role;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import llm.LLM;

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
                                    .output(executeTool(functionCall))
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
        LLM.Tool weatherToolAdapter = new LLM.Tool() {
            @Override
            public FunctionTool definiton() {
                return weatherTool();
            }

            @Override
            public String exec(ResponseFunctionToolCall functionCall) {
                return executeTool(functionCall);
            }
        };
        List<LLM.Message> responses = llm.call(
                List.of(new LLM.Message("user", "東京の現在の天気を教えてください。必ず get_weather ツールを使用してください。")),
                List.of(weatherToolAdapter),
                System.out::print);
        System.out.printf("%nAssistant message count: %d%n", responses.size());
    }

    /** 関数ツールを指定した Responses API リクエストを作成します。 */
    private static ResponseCreateParams toolCallRequest(List<ResponseInputItem> messages) {
        return ResponseCreateParams.builder()
                .inputOfResponse(messages)
                .model("LFM2.5-1.2B-JP-202606-GGUF")
                .reasoning(Reasoning.builder().effort(ReasoningEffort.NONE).build())
                .addTool(weatherTool())
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
                                        .output(executeTool(functionCall))
                                        .build()));
                    }
                }
            });
        }
        System.out.println();
    }

    /** 現在地の天気を取得する関数ツールの JSON Schema を作成します。 */
    private static FunctionTool weatherTool() {
        FunctionTool.Parameters parameters = FunctionTool.Parameters.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                        "location", Map.of(
                                "type", "string",
                                "description", "天気を取得する都市名"))))
                .putAdditionalProperty("required", JsonValue.from(List.of("location")))
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build();
        return FunctionTool.builder()
                .name("get_weather")
                .description("指定された都市の現在の天気を取得します。")
                .parameters(parameters)
                .strict(true)
                .build();
    }

    /**
     * モデルが要求した関数を実行します。
     * 実運用では引数を JSON として検証し、天気 API などの処理結果を返します。
     */
    private static String executeTool(ResponseFunctionToolCall functionCall) {
        if (!functionCall.name().equals("get_weather")) {
            throw new IllegalArgumentException("未登録のツールです: " + functionCall.name());
        }
        return "{\"condition\":\"晴れ\",\"temperatureC\":25}";
    }





    // public static interface Llm {

    // }
    // public static class LlmOpenAI implements Llm {

    //     public final String baseUrl;
    //     private String apikey;
    //     private String model;
    //     private boolean reasoning;

    //     public LlmOpenAI( String baseUrl, String apikey, String model, boolean reasoning ) {
    //         this.baseUrl = baseUrl;
    //         this.apikey = apikey;
    //         this.model = model;
    //         this.reasoning = reasoning;
    //     }

    //     public List<String> models() {
    //                 // 接続先が公開しているモデル ID を取得して表示します。
    //         OpenAIClient client = OpenAIOkHttpClient.builder()
    //             .baseUrl(this.baseUrl)
    //             .apiKey(this.apikey)
    //             .build();
    //         ModelService modelService = client.models();
    //         List<String> result = new ArrayList<>();
    //         System.out.println("Available models:");
    //         for (Model model : modelService.list().items()) {
    //             result.add(model.id());
    //         }
    //         return result;
    //     }

    //     public List<Message> call( List<Message> messages, List<Tool> tools, Consumer<String> callback ) {
    //         List<Message> resps = new ArrayList<>();
    //         List<ResponseInputItem> input_messages = new ArrayList<>();
    //         for( Message m : messages ) {
    //             com.openai.models.responses.ResponseInputItem.Message.Builder mb = ResponseInputItem.Message.builder();
    //             if ("assistant".equals(m.role)) {
    //                 mb.role(Role.of("assistant"));
    //             } else if ("system".equals(m.role)) {
    //                 mb.role(Role.SYSTEM);
    //             } else if ("developer".equals(m.role)) {
    //                 mb.role(Role.DEVELOPER);
    //             } else if ("user".equals(m.role)) {
    //                 mb.role(Role.USER);
    //             } else {
    //                 throw new IllegalArgumentException("未対応の role です: " + m.role);
    //             }
    //             mb.addInputTextContent(m.message);
    //             input_messages.add( ResponseInputItem.ofMessage( mb.build() ) );
    //         }

    //         OpenAIClient client = OpenAIOkHttpClient.builder()
    //                 .baseUrl(this.baseUrl)
    //                 .apiKey(this.apikey)
    //                 .build();

    //         boolean called = true;
    //         while(called) {
    //             called = false;

    //             Builder builder = ResponseCreateParams.builder()
    //                 .inputOfResponse(input_messages)
    //                 .model(this.model);
    //             if( reasoning ) {
    //                 builder.reasoning(Reasoning.builder().effort(ReasoningEffort.MEDIUM).build());
    //             } else {
    //                 builder.reasoning(Reasoning.builder().effort(ReasoningEffort.NONE).build());
    //             }
    //             if( tools != null && tools.size()>0 ) {
    //                 for( Tool t: tools ) {
    //                     builder.addTool(t.definiton());
    //                 }
    //             }
    //             ResponseCreateParams request = builder.build();
    //             StringBuilder output_content = new StringBuilder();
    //             try (StreamResponse<ResponseStreamEvent> stream = client.responses().createStreaming(request)) {
    //                 for( Iterator<ResponseStreamEvent> it = stream.stream().iterator(); it.hasNext(); ) {
    //                     ResponseStreamEvent event = it.next();
    //                     if (event.isOutputTextDelta()) {
    //                         String delta = event.asOutputTextDelta().delta();
    //                         output_content.append(delta);
    //                         if( callback != null ) {
    //                             callback.accept(delta);
    //                         }
    //                     }
    //                     if (event.isOutputItemDone()) {
    //                         var output = event.asOutputItemDone().item();
    //                         if (output.isFunctionCall()) {
    //                             ResponseFunctionToolCall functionCall = output.asFunctionCall();
    //                             System.out.printf("Tool call: %s(%s)%n", functionCall.name(), functionCall.arguments());
    //                             String tool_output = null;
    //                             for( Tool t : tools ) {
    //                                 if( t.definiton().name().equals(functionCall.name())) {
    //                                     tool_output = t.exec(functionCall);
    //                                 }
    //                             }
    //                             input_messages.add(ResponseInputItem.ofFunctionCall(functionCall));
    //                             input_messages.add(ResponseInputItem.ofFunctionCallOutput(
    //                                     ResponseInputItem.FunctionCallOutput.builder()
    //                                             .callId(functionCall.callId())
    //                                             .output(tool_output)
    //                                             .build()));
    //                             called = true;
    //                         }
    //                     }
    //                 }
    //             } finally {
    //                 if( output_content.length()>0 ) {
    //                     resps.add( new Message("assistant",output_content.toString()));
    //                 }
    //             }
    //         }
    //         return resps;
    //     }
    // }
}
