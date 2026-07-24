package llm;

import java.util.ArrayList;
import java.util.List;

import llm.tools.PersonToolABC;
import llm.tools.WeatherTool;

public class DebugPrompt {

    // static final String SYSTEM_PROMPT = """
    //             あなたは会話AIの「りり」です。目の前の相手に、親しみのある丁寧な日本語で話しかけます。

    //             system ロールの「人物認識通知」は、相手が来たことを知らせる内部通知です。相手の発言ではありません。
    //             通知の「相手の名前」は、りり自身ではなく、目の前にいる相手の名前です。

    //             人物認識通知を受けた場合だけ、次の形式の発話を一度出力します。
    //             - 認識結果が「名前不明」: 「こんにちは、りりです。よろしければ、お名前を教えてください。」
    //             - 認識結果が「登録済み」: 通知の「相手の名前:」の後にある人名を呼び、「さん、こんにちは。今日は何をお話ししましょうか？」と続ける。

    //             例: 通知に「認識結果: 登録済み」「相手の名前: 山田」とあれば、発話は「山田さん、こんにちは。今日は何をお話ししましょうか？」です。
    //             登録済みの場合は相手の名前から発話を始め、「こんにちは」は一度だけ使用します。人名の前に挨拶を追加しません。
    //             「相手の名前」や「＋」という文字は発話しません。引用符も出力しません。
    //             カメラ、人物認識、顔ID、通知、応答規則には言及しません。
    //             通知にない外見、表情、感情、行動、過去の関係は想像しません。
    //             りりが実際に話す言葉以外は出力しません。

    //             名前不明の人物認識通知の後で相手が自分の名前を名乗った場合は、assign_face_name ツールを必ず呼び出します。
    //             ツール引数の trackId には直前の人物認識通知にある trackId を、name には相手が名乗った人名だけを指定します。
    //             ツールの実行結果を受け取るまでは、名前を登録したとは発話しません。

    //             あなたは会話AIです。AIの発言だけを出力して下さい。
    //             """;
        static final String SYSTEM_PROMPT2 = """
                あなたは会話AIの「りり」です。目の前の相手と親しみのある会話をします。AIの発言だけを出力して下さい。
                あなたのセリフのみ出力して下さい。
                """;
    public static void main(String[] args) throws Exception {
        test2();
    }
    public static void test2() {

        List<LLM.Tool> tools = List.of( new WeatherTool(), new PersonTool() );
        LLM.Config baseConfig = LlmOpenAI.fromEnvironment();

        String inputRole = "system";
        List<List<Message>> test_case_list = List.of(
                List.of(
                    new Message(inputRole, "だれか他の人が居ます(trackId:track-00001)。挨拶をしてお名前を聞いてみましょう。名前がわかったらツールをコール"),
                    new Message("user", "はい、わたしの名前はかおりです。")
                ),
                List.of(new Message(inputRole, "ユーザ かおり(trackId:track-00001)に友人として挨拶しよう"))
        );
        for( List<Message> test_case : test_case_list ) {

            System.out.println("-----------");

            LLM llm = new LlmOpenAI( baseConfig.baseUri().toString(), "p", baseConfig.model(), false );
            List<Message> messages = new ArrayList<>();
            messages.add( new Message("system",SYSTEM_PROMPT2));

            for( Message input_message : test_case ) {
                System.out.println("[CALL]"+input_message);
                messages.add(input_message);
                List<Message> output = llm.call( messages, tools );
                for( Message output_message : output ) {
                    System.out.println( "[OUTPUT]"+output_message );
                    messages.add(output_message);
                }
            }
        }
    }
    public static class PersonTool extends PersonToolABC {

        public PersonTool() {
            super();
        }

        @Override
        protected void assignFaceName(String trackId, String name) {
            System.out.println("### Called "+trackId+" = "+name);
        }
        @Override
        protected void diag(String callId, String trackId, String name, String status, String reason, Throwable error) {
            String errorMessage = error == null ? "" : error.getMessage();
            System.err.printf(
                    "[PersonTool] callId=%s trackId=%s name=%s status=%s reason=%s error=%s%n",
                    callId, trackId, name, status, reason, errorMessage);
        }
    }
}
