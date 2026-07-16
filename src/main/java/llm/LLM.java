package llm;

import java.util.List;
import java.util.function.Consumer;

import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseFunctionToolCall;

public interface LLM {
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
        public List<String> models();
        public String model();
        public List<Message> call( List<Message> messages, List<Tool> tools, Consumer<String> callback );
        default List<Message> call( List<Message> messages ) {
            return call(messages, null, null );
        }
        default List<Message> call( List<Message> messages, List<Tool> tools ) {
            return call(messages, tools, null );
        }
        default List<Message> call( List<Message> messages, Consumer<String> callback  ) {
            return call(messages, null, callback );
        }
}
