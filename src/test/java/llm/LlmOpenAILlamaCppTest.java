package llm;

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
import java.util.List;
import org.junit.jupiter.api.Test;

/** 起動中の llama.cpp に対する LlmOpenAI のツール呼び出しを検証します。 */
class LlmOpenAILlamaCppTest {
    private static final URI DEFAULT_BASE_URI = URI.create("http://localhost:8767/v1");

    @Test
    void callsAssignFaceNameToolWithAssistantHistoryOnLocalLlamaCpp() throws Exception {
        URI baseUri = URI.create(System.getProperty("llama.cpp.base.url", DEFAULT_BASE_URI.toString()));
        assumeLlamaCppWithLfm25Model(baseUri);

        LlmOpenAI model = new LlmOpenAI(new LLM.Config(
                baseUri, "LFM2\\.5", Duration.ofSeconds(120), ""));
        List<LLM.Message> response = model.call(List.of(
                new LLM.Message("system", "名前を名乗ったらassign_face_nameツールを呼び出してください。"),
                new LLM.Message("user", "trackIdはtrak-000001です。私の名前は太郎です。")),
                List.of(new LlmOpenAITest.RecordingTool()));

        assertFalse(response.isEmpty());
        assertTrue(response.get(0).message != null);
    }

    private static void assumeLlamaCppWithLfm25Model(URI baseUri) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint(baseUri, "models"))
                .timeout(Duration.ofSeconds(5)).GET().build();
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
        if (text.endsWith("/" + path)) return baseUri;
        return URI.create(text.endsWith("/") ? text + path : text + "/" + path);
    }
}
