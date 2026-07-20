package llm.tools;

import java.util.List;
import java.util.Map;

import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.ResponseFunctionToolCall;

import audio.AudioDiagnostics;
import json.Json;
import json.JsonFields;
import llm.LLM;

public abstract class PersonToolABC extends LLM.Tool {

    /** 人物名を追跡 ID に関連付ける Function Tool の識別子です。 */
    public static final String NAME = "assign_face_name";

    public PersonToolABC() {
        super(
            NAME,
            "trackIdに人物名を登録します。ユーザーが自分の名前を名乗ったときに呼び出します。"
        );
    }

    @Override
    public final FunctionTool.Parameters parameters() {
        return FunctionTool.Parameters.builder()
            .putAdditionalProperty("type", JsonValue.from("object"))
            .putAdditionalProperty("properties", JsonValue.from(Map.of(
                    "trackId", Map.of(
                            "type", "string",
                            "description", "FaceDB が採番した追跡ID。形式: trak-000000"),
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
    public final String exec(ResponseFunctionToolCall toolCall, String arguments ) {
        String callId = toolCall.callId();
        String trackId = JsonFields.stringOrDefault(arguments, "trackId", "").trim();
        String name = JsonFields.stringOrDefault(arguments, "name", "").trim();
        if (!isAssistantTurnActive()) {
            this.diag( callId, trackId, name, "ignored", "assistant-turn-inactive", null);
            return Json.object(Json.fields(
                    "status", "failed",
                    "error", "assistant turn is no longer active"));
        }

        if (trackId.isBlank() || name.isBlank() || "unknown".equalsIgnoreCase(name) || "不明".equals(name)) {
            this.diag(callId,trackId,name,"ignored","invalid-argument",null);
            return Json.object(Json.fields(
                    "status", "failed",
                    "error", "ユーザに名前を聞いてね。",
                    "trackId", trackId,
                    "name", name));
        }
        try {
            this.assignFaceName(trackId, name);
        } catch (IllegalArgumentException | IllegalStateException e) {
            this.diag(callId, trackId, name, "failed", "assign-failed", e);
            return Json.object(Json.fields(
                    "status", "failed",
                    "error", e.getMessage(),
                    "trackId", trackId,
                    "name", name));
        }
        this.diag(callId,trackId,name,"assigned","",null);
        return Json.object(Json.fields(
                "status", "ok",
                "trackId", trackId,
                "name", name,
                "message", "覚えました。’"+name+"'さんとの会話を進めてください。"
        ));
    }
    /**
     * 呼び出し元の会話ターンが現在も有効かを判定します。
     * 実装は接続クライアント側に委譲します。
     */
    protected boolean isAssistantTurnActive() {
        return true;
    }

    /**
     * 追跡 ID へ人物名を登録します。
     * 実装は接続クライアント側に委譲します。
     */
    protected abstract void assignFaceName(String trackId, String name);
    /**
     * デバッグ用のログを実装できます。
     */
    protected void diag(String callId, String trackId, String name, String status, String reason, Throwable error) {

    }
}
