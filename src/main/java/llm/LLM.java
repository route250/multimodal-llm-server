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
        public static interface Tool {
            public FunctionTool definiton();
            public String exec( ResponseFunctionToolCall functionCall );
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
