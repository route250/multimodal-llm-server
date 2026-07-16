package llm.tools;

import java.util.List;
import java.util.Map;

import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseFunctionToolCall;

import llm.LLM;

public class WeatherTool extends LLM.Tool {

    public WeatherTool() {
        super(
            "get_weather",
            "指定された都市の現在の天気を取得します。かならず天気を確認して回答すること。"
        );
    }
    @Override
    public FunctionTool.Parameters parameters() {
        return FunctionTool.Parameters.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("properties", JsonValue.from(Map.of(
                    "location", Map.of(
                            "type", "string",
                            "description", "天気を取得する都市名"))))
            .putAdditionalProperty("required", JsonValue.from(List.of("location")))
            .putAdditionalProperty("additionalProperties", JsonValue.from(false))
            .build();
    }

    @Override
    public String exec(ResponseFunctionToolCall functionCall, String arguments ) {
        System.out.println("###CALLED###");
        return "{\"condition\":\"晴れ\",\"temperatureC\":25}";
    }

}