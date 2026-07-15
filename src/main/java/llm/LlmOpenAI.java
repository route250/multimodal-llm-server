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
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.regex.Matcher;

public class LlmOpenAI implements LLM {

        public final String baseUrl;
        private String apikey;
        private String model;
        private String selected_model;
        private boolean reasoning;

        public LlmOpenAI( String baseUrl, String apikey, String model, boolean reasoning ) {
            this.baseUrl = baseUrl;
            this.apikey = apikey;
            this.model = model;
            this.reasoning = reasoning;
        }

        public List<String> models() {
                    // 接続先が公開しているモデル ID を取得して表示します。
            OpenAIClient client = OpenAIOkHttpClient.builder()
                .baseUrl(this.baseUrl)
                .apiKey(this.apikey)
                .build();
            ModelService modelService = client.models();
            List<String> result = new ArrayList<>();
            System.out.println("Available models:");
            for (Model model : modelService.list().items()) {
                result.add(model.id());
            }
            return result;
        }

        public String model() {
            if( this.selected_model==null ) {
                Pattern p = Pattern.compile(this.model);
                for( String modelName : this.models() ) {
                    Matcher m = p.matcher(modelName);
                    if( m.find() ) {
                        this.selected_model = modelName;
                        break;
                    }
                }
            }
            return this.selected_model;
        }

        public List<Message> call( List<Message> messages, List<Tool> tools, Consumer<String> callback ) {
            List<Message> resps = new ArrayList<>();
            String model = this.model();

            List<ResponseInputItem> input_messages = new ArrayList<>();
            for( Message m : messages ) {
                com.openai.models.responses.ResponseInputItem.Message.Builder mb = ResponseInputItem.Message.builder();
                if ("assistant".equals(m.role)) {
                    mb.role(Role.of("assistant"));
                } else if ("system".equals(m.role)) {
                    mb.role(Role.SYSTEM);
                } else if ("developer".equals(m.role)) {
                    mb.role(Role.DEVELOPER);
                } else if ("user".equals(m.role)) {
                    mb.role(Role.USER);
                } else {
                    throw new IllegalArgumentException("未対応の role です: " + m.role);
                }
                mb.addInputTextContent(m.message);
                input_messages.add( ResponseInputItem.ofMessage( mb.build() ) );
            }

            OpenAIClient client = OpenAIOkHttpClient.builder()
                    .baseUrl(this.baseUrl)
                    .apiKey(this.apikey)
                    .build();

            boolean called = true;
            while(called) {
                called = false;

                Builder builder = ResponseCreateParams.builder()
                    .inputOfResponse(input_messages)
                    .model(model);
                if( reasoning ) {
                    builder.reasoning(Reasoning.builder().effort(ReasoningEffort.MEDIUM).build());
                } else {
                    builder.reasoning(Reasoning.builder().effort(ReasoningEffort.NONE).build());
                }
                if( tools != null && tools.size()>0 ) {
                    for( Tool t: tools ) {
                        builder.addTool(t.definiton());
                    }
                }
                ResponseCreateParams request = builder.build();
                StringBuilder output_content = new StringBuilder();
                try (StreamResponse<ResponseStreamEvent> stream = client.responses().createStreaming(request)) {
                    for( Iterator<ResponseStreamEvent> it = stream.stream().iterator(); it.hasNext(); ) {
                        ResponseStreamEvent event = it.next();
                        if (event.isOutputTextDelta()) {
                            String delta = event.asOutputTextDelta().delta();
                            output_content.append(delta);
                            if( callback != null ) {
                                callback.accept(delta);
                            }
                        }
                        if (event.isOutputItemDone()) {
                            var output = event.asOutputItemDone().item();
                            if (output.isFunctionCall()) {
                                ResponseFunctionToolCall functionCall = output.asFunctionCall();
                                System.out.printf("Tool call: %s(%s)%n", functionCall.name(), functionCall.arguments());
                                String tool_output = null;
                                for( Tool t : tools ) {
                                    if( t.definiton().name().equals(functionCall.name())) {
                                        tool_output = t.exec(functionCall);
                                    }
                                }
                                input_messages.add(ResponseInputItem.ofFunctionCall(functionCall));
                                input_messages.add(ResponseInputItem.ofFunctionCallOutput(
                                        ResponseInputItem.FunctionCallOutput.builder()
                                                .callId(functionCall.callId())
                                                .output(tool_output)
                                                .build()));
                                called = true;
                            }
                        }
                    }
                } finally {
                    if( output_content.length()>0 ) {
                        resps.add( new Message("assistant",output_content.toString()));
                    }
                }
            }
            return resps;
        }
}
