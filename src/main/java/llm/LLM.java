package llm;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseFunctionToolCall;

public abstract class LLM {
        public static String env(String name, String defaultValue) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                return defaultValue;
            }
            return value;
        }
        public record Config(URI baseUri, String model, Duration timeout, String apiKey, boolean reasoning) {
            public Config(URI baseUri, String model, Duration timeout, String apiKey) {
                this(baseUri, model, timeout, apiKey, false);
            }
            public Config(URI baseUri, String model, Duration timeout) {
                this(baseUri, model, timeout, "", false);
            }
            public Config( URI baseUri, String model, String systemPrompt, Duration timeout, String apiKey) {
                this( baseUri, model, timeout, apiKey, false );
            }
            public Config(URI baseUri, String model, String systemPrompt, Duration timeout) {
                this(baseUri, model, timeout, "");
            }
        }
        public static class Message {
            public final String role;
            public final String message;
            public Message( String role, String message ) {
                this.role=role;
                this.message=message;
            }
            public String toString() {
                return "{\""+this.role+"\":\""+this.message+"\"}";
            }
        }
        public static abstract class Tool {
            public final String name;
            public final String description;
            public Tool( String name, String description ) {
                this.name=name;
                this.description = description;
            }
            public final FunctionTool definiton() {
                return FunctionTool.builder()
                        .name(this.name)
                        .description(this.description)
                        .parameters(this.parameters())
                        .strict(true)
                        .build();
            }
            public abstract FunctionTool.Parameters parameters();

            public final String exec( ResponseFunctionToolCall functionCall ) {
                System.out.println("###CALLED###");
                if (!functionCall.name().equals(this.name)) {
                    throw new IllegalArgumentException( this.name+" 未登録のツールです: " + functionCall.name());
                }
                String arguments = functionCall.arguments();
                return this.exec(functionCall,arguments);
            }

            public abstract String exec( ResponseFunctionToolCall functionCall, String arguments );
        }

        public final String baseUrl;
        protected String apikey;
        protected String model;
        protected String selected_model;
        protected boolean reasoning;

        public LLM( String baseUrl, String apikey, String model, boolean reasoning ) {
            this.baseUrl = baseUrl;
            this.apikey = apikey==null||apikey.isBlank()?"empty":apikey;
            this.model = model;
            this.reasoning = reasoning;
        }
        public LLM( Config config ) {
            this(
                config.baseUri.toString(),
                config.apiKey(),
                config.model(),
                config.reasoning()
            );
        }
        public abstract List<String> models();
        public abstract String model();
        public abstract List<Message> call( List<Message> messages, List<Tool> tools, Consumer<String> callback );
        public List<Message> call( List<Message> messages ) {
            return call(messages, null, null );
        }
        public List<Message> call( List<Message> messages, List<Tool> tools ) {
            return call(messages, tools, null );
        }
        public List<Message> call( List<Message> messages, Consumer<String> callback  ) {
            return call(messages, null, callback );
        }
        public String call( String message  ) {
            List<Message> messages = call(List.of(new Message("user",message)));
            StringBuilder sb = new StringBuilder();
            for( Message m : messages ) {
                sb.append(m.message);
            }
            return sb.toString();
        }
        /** 保存前検証で特定したモデルと応答です。 */
        public record Verification(String model, String response) {
        }
        /**
         * 設定画面の保存前に、モデル解決と実際の応答を確認します。
         */
        public Verification verify() {
            System.out.println("baseUrl:"+this.baseUrl+" apikey:"+this.apikey);
            String model = this.model();
            String response = this.call("設定確認です。OK とだけ答えてください。");
            if (response.isBlank()) {
                throw new LanguageModelException("LLM response did not contain text");
            }
            return new Verification(model, response);
        }
}
