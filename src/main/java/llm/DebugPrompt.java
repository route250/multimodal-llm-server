package llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseFunctionToolCall;

import json.Json;
import json.JsonFields;
import llm.OpenAiResponsesLanguageModel.Config;
import llm.tools.WeatherTool;
import server.ChatClient;

public class DebugPrompt {

    static final String SYSTEM_PROMPT = """
                あなたは会話AIの「りり」です。目の前の相手に、親しみのある丁寧な日本語で話しかけます。

                system ロールの「人物認識通知」は、相手が来たことを知らせる内部通知です。相手の発言ではありません。
                通知の「相手の名前」は、りり自身ではなく、目の前にいる相手の名前です。

                人物認識通知を受けた場合だけ、次の形式の発話を一度出力します。
                - 認識結果が「名前不明」: 「こんにちは、りりです。よろしければ、お名前を教えてください。」
                - 認識結果が「登録済み」: 通知の「相手の名前:」の後にある人名を呼び、「さん、こんにちは。今日は何をお話ししましょうか？」と続ける。

                例: 通知に「認識結果: 登録済み」「相手の名前: 山田」とあれば、発話は「山田さん、こんにちは。今日は何をお話ししましょうか？」です。
                登録済みの場合は相手の名前から発話を始め、「こんにちは」は一度だけ使用します。人名の前に挨拶を追加しません。
                「相手の名前」や「＋」という文字は発話しません。引用符も出力しません。
                カメラ、人物認識、顔ID、通知、応答規則には言及しません。
                通知にない外見、表情、感情、行動、過去の関係は想像しません。
                りりが実際に話す言葉以外は出力しません。

                名前不明の人物認識通知の後で相手が自分の名前を名乗った場合は、assign_face_name ツールを必ず呼び出します。
                ツール引数の trackId には直前の人物認識通知にある trackId を、name には相手が名乗った人名だけを指定します。
                ツールの実行結果を受け取るまでは、名前を登録したとは発話しません。

                あなたは会話AIです。AIの発言だけを出力して下さい。
                """;
        static final String SYSTEM_PROMPT2 = """
                あなたは会話AIの「りり」です。目の前の相手と親しみのある会話をします。AIの発言だけを出力して下さい。
                あなたのセリフのみ出力して下さい。
                """;
    public static void main(String[] args) throws Exception {
        test2();
    }
    public static void test2() {

        List<LLM.Tool> tools = List.of( new WeatherTool(), new PersonTool() );
        Config baseConfig = OpenAiResponsesLanguageModel.fromEnvironment();

        String inputRole = "system";
        List<List<LLM.Message>> test_case_list = List.of(
                List.of(
                    new LLM.Message(inputRole, "だれか他の人が居ます(trackId:track-00001)。挨拶をしてお名前を聞いてみましょう。名前がわかったらツールをコール"),
                    new LLM.Message("user", "はい、わたしの名前はかおりです。")
                ),
                List.of(new LLM.Message(inputRole, "ユーザ名 かおり(trackId:track-00001)と出会いました。まずは挨拶から"))
        );
        for( List<LLM.Message> test_case : test_case_list ) {

            System.out.println("-----------");

            LLM llm = new LlmOpenAI( baseConfig.baseUri().toString(), "p", baseConfig.model(), false );
            List<LLM.Message> messages = new ArrayList<>();
            messages.add( new LLM.Message("system",SYSTEM_PROMPT2));

            for( LLM.Message input_message : test_case ) {
                System.out.println("[CALL]"+input_message);
                messages.add(input_message);
                List<LLM.Message> output = llm.call( messages, tools );
                for( LLM.Message output_message : output ) {
                    System.out.println( "[OUTPUT]"+output_message );
                    messages.add(output_message);
                }
            }
        }
    }
    public static class PersonTool extends LLM.Tool {

        public PersonTool() {
            super(
                "persons",
                "ユーザから聞いた名前を覚える。"
            );
        }

        @Override
        public FunctionTool.Parameters parameters() {
            return FunctionTool.Parameters.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                        "trackId", Map.of(
                                "type", "string",
                                "description", "人物映像の追跡ID track-999999"),
                        "name", Map.of(
                            "type", "string",
                            "description", "覚える名前。禁止:不明,unknown"
                        )
                )))
                .putAdditionalProperty("required", JsonValue.from(List.of("trackId", "name")))
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build();
        }

        @Override
        public String exec(ResponseFunctionToolCall functionCall, String arguments ) {
            System.out.println("###CALLED### "+this.name);
            String trackId = JsonFields.stringOrDefault(arguments, "trackId", "").trim();
            String name = JsonFields.stringOrDefault(arguments, "name", "").trim();
            if (trackId.isBlank() || name.isBlank() || "unknown".equalsIgnoreCase(name) || "不明".equals(name)) {
                return Json.object(Json.fields(
                        "status", "failed",
                        "error", "ユーザに名前を聞いてね。",
                        "trackId", trackId,
                        "name", name));
            }
            System.out.printf("register person: trackId=%s, name=%s%n", trackId, name);
            return Json.object(Json.fields(
                    "status", "ok",
                    "trackId", trackId,
                    "name", name,
                    "message", "覚えました。’"+name+"'さんとの会話を進めてください。"
            ));
        }

    }

    public static void test1() {

        Config baseConfig = OpenAiResponsesLanguageModel.fromEnvironment();
        Config conf = new Config(
                baseConfig.baseUri(),
                baseConfig.model(), // LFM2.5-1.2B-JP-202606 を想定
                SYSTEM_PROMPT,
                baseConfig.timeout(),
                baseConfig.apiKey()
        );

        OpenAiResponsesLanguageModel model = new OpenAiResponsesLanguageModel(conf);

        String inputRole = "system";
        List<List<ChatMessage>> faceInputs = List.of(
                List.of(
                    new ChatMessage(inputRole, "人物認識通知\n認識結果: 名前不明\n相手の名前: 不明\ntrackId: faceId-00001"),
                    new ChatMessage("user", "はい、わたしの名前はかおりです。")
                ),
                List.of(new ChatMessage(inputRole, "人物認識通知\n認識結果: 登録済み\n相手の名前: かおり\ntrackId: faceId-00001"))
        );

        for (List<ChatMessage> inputs : faceInputs) {
            System.out.println("--------------");
            List<ChatMessage> messages = new ArrayList<>();
            for (ChatMessage input : inputs) {
                messages.add(input);
                System.out.println("<INPUT>");
                for (ChatMessage message : messages) {
                    System.out.println("'" + message.role() + "':" + message.text());
                }
                System.out.println("</INPUT>");

                LanguageModelResponse response = model.respond(messages, ChatClient.LLM_TOOLS);
                System.out.println("<OUTPUT>");
                System.out.println(response.text());
                System.out.println("</OUTPUT>");
                for (ToolCall toolCall : response.toolCalls()) {
                    System.out.println("<TOOL_CALL>");
                    System.out.println("id: " + toolCall.id());
                    System.out.println("callId: " + toolCall.callId());
                    System.out.println("name: " + toolCall.name());
                    System.out.println("arguments: " + toolCall.arguments());
                    System.out.println("</TOOL_CALL>");
                }
                if (!response.text().isBlank()) {
                    messages.add(new ChatMessage("assistant", response.text()));
                }
            }
        }
    }
}
