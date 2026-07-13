package llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAiResponsesLanguageModelLlamaCppTest {
    private static final URI DEFAULT_BASE_URI = URI.create("http://localhost:8767/v1");

    @Test
    void callsAssignFaceNameToolWithAssistantHistoryOnLocalLlamaCpp() throws Exception {
        URI baseUri = URI.create(System.getProperty("llama.cpp.base.url", DEFAULT_BASE_URI.toString()));
        assumeLlamaCppWithLfm25Model(baseUri);

        OpenAiResponsesLanguageModel model = new OpenAiResponsesLanguageModel(new OpenAiResponsesLanguageModel.Config(
                baseUri,
                "LFM2\\.5",
                """
                        あなたは顔認証つき受付アシスタントです。\
                        会話履歴にTrackIdがあり、来訪者が自分の名前を名乗ったら、必ずassign_face_nameツールを呼び出してください。\
                        ツール引数のtrackIdには会話履歴のTrackIdをそのまま入れ、nameには来訪者が名乗った名前だけを入れてください。\
                        """,
                Duration.ofSeconds(120)));
        List<ToolCall> toolCalls = new ArrayList<>();
        StringBuilder text = new StringBuilder();

        model.respondStreamingEvents(
                List.of(
                        new ChatMessage("user", "[カメラ情報] { \"name\": \"unknown\", \"trackId\": \"trak-000001\", \"faceId\": \"face-000001\", \"comment\": \"人物を認識しました\" }"),
                        new ChatMessage("assistant", "あなたのお名前をおしえてください。"),
                        new ChatMessage("user", "私の名前は、太郎です")),
                List.of(OpenAiResponsesLanguageModelTest.assignFaceNameTool()),
                new StreamingResponseHandler() {
                    @Override
                    public void onTextDelta(String delta) {
                        text.append(delta);
                    }

                    @Override
                    public void onToolCall(ToolCall toolCall) {
                        toolCalls.add(toolCall);
                    }
                });

        assertFalse(toolCalls.isEmpty(), () -> "tool call was not returned. text response=" + text);
        ToolCall toolCall = toolCalls.get(0);
        assertEquals("assign_face_name", toolCall.name());
        assertTrue(toolCall.arguments().contains("\"trackId\":\"trak-000001\""),
                () -> "unexpected tool arguments: " + toolCall.arguments());
        assertTrue(toolCall.arguments().contains("\"name\":\"太郎\""),
                () -> "unexpected tool arguments: " + toolCall.arguments());
    }

    private static void assumeLlamaCppWithLfm25Model(URI baseUri) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint(baseUri, "models"))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        try {
            HttpResponse<String> response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assumeTrue(response.statusCode() / 100 == 2,
                    () -> "llama.cpp models endpoint returned HTTP " + response.statusCode());
            assumeTrue(response.body().contains("LFM2.5"), "LFM2.5 model is not available at " + baseUri);
        } catch (ConnectException e) {
            assumeTrue(false, "llama.cpp is not running at " + baseUri);
        } catch (IOException e) {
            throw new AssertionError("failed to request llama.cpp at " + baseUri, e);
        }
    }

    private static URI endpoint(URI baseUri, String path) {
        String text = baseUri.toString();
        if (text.endsWith("/" + path)) {
            return baseUri;
        }
        if (text.endsWith("/")) {
            return URI.create(text + path);
        }
        return URI.create(text + "/" + path);
    }
}
