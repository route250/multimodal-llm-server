package server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.openai.core.JsonValue;
import com.openai.models.responses.ResponseFunctionToolCall;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import llm.Message;
import llm.Message.Role;
import llm.tools.PersonToolABC;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import facedb.FaceDB;
import json.JsonFields;
import llm.LLM;

class FaceEventTest {
    @Test
    void faceEventEndpointSavesJsonAndJpeg(@TempDir Path tempDir) throws Exception {
        try (MlServer server = new MlServer(0, new FaceDB(tempDir))) {
            server.start();
            HttpClient client = localHttpsClient();
            CompletableFuture<HttpResponse<Void>> connection = openChatConnection(client, server, "test-face");
            String body = faceEventBody(descriptor(0.1));

            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("https://localhost:" + server.port()
                                    + "/face/event?group=group-1&sessionId=test-face"))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            connection.cancel(true);

            assertEquals(202, response.statusCode());
            assertTrue(response.body().contains("\"known\":false"));
            assertEquals("legacy", jsonString(response.body(), "trackId"));
            assertEquals("sample-000000", jsonString(response.body(), "sampleId"));
            assertTrue(Files.isRegularFile(tempDir.resolve("trak-000000").resolve("sample-000000.json")));
            Path imagePath = tempDir.resolve("trak-000000").resolve("sample-000000.jpg");
            assertTrue(Files.isRegularFile(imagePath));
            assertTrue(Files.size(imagePath) > 0);
        }
    }

    @Test
    void faceEventEndpointMatchesNamedFaceFromFaceDB(@TempDir Path tempDir) throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String knownTrackId = db.createTrackId();
        db.register(knownTrackId, descriptor(0.2), jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9}));
        db.assign(knownTrackId, "山田");

        try (MlServer server = new MlServer(0, db)) {
            server.start();
            HttpClient client = localHttpsClient();
            CompletableFuture<HttpResponse<Void>> connection = openChatConnection(client, server, "test-face");
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("https://localhost:" + server.port()
                                    + "/face/event?group=group-1&sessionId=test-face"))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString(faceEventBody(descriptor(0.2)), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            connection.cancel(true);

            assertEquals(202, response.statusCode());
            assertTrue(response.body().contains("\"known\":true"));
            assertEquals("sample-000000", jsonString(response.body(), "sampleId"));
            assertEquals("person0000", jsonString(response.body(), "personId"));
            assertEquals("山田", jsonString(response.body(), "personName"));
            assertTrue(Files.isRegularFile(tempDir.resolve("trak-000001").resolve("sample-000000.json")));
            assertTrue(Files.isRegularFile(tempDir.resolve("trak-000001").resolve("sample-000000.jpg")));
        }
    }

    @Test
    void personLeftDoesNotRegisterFaceDBFile(@TempDir Path tempDir) throws Exception {
        try (MlServer server = new MlServer(0, new FaceDB(tempDir))) {
            server.start();
            HttpClient client = localHttpsClient();
            CompletableFuture<HttpResponse<Void>> connection = openChatConnection(client, server, "test-face");
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create("https://localhost:" + server.port()
                                    + "/face/event?group=group-1&sessionId=test-face"))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString("""
                                    {"eventType":"person-left","eventId":"left-1","clientTimestamp":"2026-07-09T00:00:00Z"}
                                    """, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            connection.cancel(true);

            assertEquals(202, response.statusCode());
            assertEquals("person-left", jsonString(response.body(), "presenceState"));
            assertEquals("none", jsonString(response.body(), "sampleId"));
            assertFalse(Files.exists(tempDir.resolve("trak-000000")));
        }
    }

    @Test
    void facePresenceEnteredStartsLlmFromConversationHistory(@TempDir Path tempDir) throws Exception {
        FaceDB db = new FaceDB(tempDir);
        try (MlServer server = new MlServer(0, db)) {
            ChatGroup group = new ChatGroup("group-test", server);
            RecordingLanguageModel languageModel = new RecordingLanguageModel("確認します。");
            ChatClient client = new ChatClient(
                    "client-1",
                    group,
                    new TranscriptAudioProcessor("unused"),
                    languageModel);

            client.handleFacePresence(db, new FaceEventRequest(
                    "person-detected",
                    "browser-track-1",
                    descriptor(0.1),
                    jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9})));
            assertTrue(languageModel.awaitCalls(1));
            client.handleFacePresence(db, new FaceEventRequest(
                    "person-left",
                    "browser-track-1",
                    null,
                    null));

            assertEquals(1, languageModel.calls().size());
            PromptTemplates templates = GroupLlmSettings.defaults("group-1").promptTemplates();
            assertEquals(List.of(
                    "system:" + templates.expandedSystemPrompt() + "\n" + GroupLlmSettings.LFM25_FIRST_MEETING_PROMPT,
                    "system:" + templates.faceMessage(false, "", "trak-000000")),
                    languageModel.calls().get(0));
            var history = client.conversationHistoryForTest();
            assertEquals(2, history.size());
            assertEquals(Role.System, history.get(0).role());
            assertEquals(templates.faceMessage(false, "", "trak-000000"), history.get(0).message());
            assertEquals(Role.System, history.get(1).role());
            assertEquals("人物認識通知\n認識結果: 不在\n相手の名前: 不在\ntrackId: 不在", history.get(1).message());
        }
    }

    @Test
    void facePresenceHistoryTextUsesUnknownPersonGreeting() {
            assertEquals(GroupLlmSettings.defaults("group-1").promptTemplates()
                            .faceMessage(false, "", "legacy"),
                    ChatClient.facePresenceHistoryText(FaceEventResult.unknownFace("sample-000000")));
    }

    @Test
    void facePresencePassesFaceDbTrackIdToLlm(@TempDir Path tempDir) throws Exception {
        FaceDB db = new FaceDB(tempDir);
        try (MlServer server = new MlServer(0, db)) {
            ChatGroup group = new ChatGroup("group-test", server);
            RecordingLanguageModel languageModel = new RecordingLanguageModel("確認します。");
            ChatClient client = new ChatClient(
                    "client-1",
                    group,
                    new TranscriptAudioProcessor("unused"),
                    languageModel);
            FaceEventRequest request = new FaceEventRequest(
                    "person-detected",
                    "browser-track-42",
                    descriptor(0.1),
                    jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9}));

            FaceEventResult result = client.handleFacePresence(db, request);

            assertTrue(languageModel.awaitCalls(1));
            assertEquals("browser-track-42", result.trackId());
            PromptTemplates templates = GroupLlmSettings.defaults("group-1").promptTemplates();
            assertEquals(List.of(
                    "system:" + templates.expandedSystemPrompt() + "\n" + GroupLlmSettings.LFM25_FIRST_MEETING_PROMPT,
                    "system:" + templates.faceMessage(false, "", "trak-000000")),
                    languageModel.calls().get(0));
        }
    }

    @Test
    void knownFaceAddsKnownPersonPromptAndNotification(@TempDir Path tempDir) throws Exception {
        FaceDB db = new FaceDB(tempDir);
        String registeredTrackId = db.createTrackId();
        db.register(registeredTrackId, descriptor(0.2), jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9}));
        db.assign(registeredTrackId, "花子");
        try (MlServer server = new MlServer(0, db)) {
            ChatGroup group = new ChatGroup("group-test", server);
            RecordingLanguageModel languageModel = new RecordingLanguageModel("確認します。");
            ChatClient client = new ChatClient("client-1", group, new TranscriptAudioProcessor("unused"), languageModel);

            FaceEventResult result = client.handleFacePresence(db, new FaceEventRequest(
                    "person-detected", "browser-track-known", descriptor(0.2),
                    jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9})));

            assertTrue(result.known());
            assertTrue(languageModel.awaitCalls(1));
            PromptTemplates templates = GroupLlmSettings.defaults("group-1").promptTemplates();
            List<String> messages = languageModel.calls().get(0);
            assertEquals("system:" + templates.expandedSystemPrompt()
                    + "\n" + GroupLlmSettings.LFM25_KNOWN_PERSON_PROMPT, messages.get(0));
            assertTrue(messages.get(1).matches(
                    "system:<!-- 花子 さんとして登録しました。assign_user_name の内部データ: trak-[0-9]{6} -->"));
        }
    }

    @Test
    void facePresenceUpdateIsPublishedWithoutConversationHistory(@TempDir Path tempDir) throws Exception {
        FaceDB db = new FaceDB(tempDir);
        try (MlServer server = new MlServer(0, db)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("listener");
            CountingLanguageModel languageModel = new CountingLanguageModel();
            ChatClient client = new ChatClient(
                    "client-1",
                    group,
                    new TranscriptAudioProcessor("unused"),
                    languageModel);
            drainJoinEvents(listener);

            client.handleFacePresence(db, new FaceEventRequest(
                    "person-updated",
                    "browser-track-1",
                    descriptor(0.1),
                    jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9})));

            assertTrue(client.conversationHistoryForTest().isEmpty());
            ServerEvent event = listener.events().poll(1, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(event);
            assertEquals("face-presence", event.type());
            assertTrue(event.message().contains("\"state\":\"person-updated\""));
            assertEquals(0, languageModel.calls);
        }
    }

    @Test
    void assignFaceNameToolCallUpdatesFaceDB(@TempDir Path tempDir) throws Exception {
        FaceDB db = new FaceDB(tempDir);
        try (MlServer server = new MlServer(0, db)) {
            String trackId = db.createTrackId();
            db.register(trackId, descriptor(0.1), jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9}));
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("listener");
            drainJoinEvents(listener);
            ToolCallingLanguageModel languageModel = new ToolCallingLanguageModel(
                    "fc_1",
                    "call_1",
                    PersonToolABC.NAME,
                    "{\""+PersonToolABC.PARAM_TRACK_ID+"\":\"trak-000000\",\""+PersonToolABC.PARAM_NAME+"\":\"太郎\"}");
            ChatClient client = new ChatClient(
                    "client-1",
                    group,
                    new TranscriptAudioProcessor("unused"),
                    languageModel);

            client.handle(ChatRequest.from(
                    "text/plain; charset=utf-8",
                    "私の名前は、太郎です".getBytes(StandardCharsets.UTF_8)));

            assertTrue(languageModel.awaitCalls(1));
            assertTrue(languageModel.tools().stream().anyMatch(tool -> PersonToolABC.NAME.equals(tool.name)));
            ServerEvent confirmation = pollUntil(listener, event -> "assistant-audio-chunk".equals(event.type()));
            assertNotNull(confirmation);
            assertTrue(confirmation.message().contains("登録しました。"));
            assertTrue(client.conversationHistoryForTest().contains(new Message(
                    Role.System,
                    GroupLlmSettings.defaults("group-test").promptTemplates()
                            .assignedPersonMessage("太郎", trackId))));
            String trackJson = Files.readString(tempDir.resolve("trak-000000").resolve("trak-000000.json"), StandardCharsets.UTF_8);
            assertEquals("person0000", JsonFields.string(trackJson, "personId"));
            String personsJson = Files.readString(tempDir.resolve("persons.json"), StandardCharsets.UTF_8);
            assertTrue(personsJson.contains("\"name\":\"太郎\""));
        }
    }

    private static String faceEventBody(double[] descriptor) {
        String jpg = Base64.getEncoder().encodeToString(new byte[] {1, 2, 3, 4});
        return """
                {"eventType":"person-detected","eventId":"event-1","clientTimestamp":"2026-07-09T00:00:00Z","faceWidthRatio":0.2,"faceAreaRatio":0.05,"detectionScore":0.9,"descriptor":[%s],"imageDataUrl":"data:image/jpeg;base64,%s"}
                """.formatted(csv(descriptor), jpg);
    }

    private static double[] descriptor(double value) {
        double[] descriptor = new double[128];
        for (int i = 0; i < descriptor.length; i++) {
            descriptor[i] = value;
        }
        return descriptor;
    }

    private static String csv(double[] values) {
        StringBuilder csv = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                csv.append(",");
            }
            csv.append(values[i]);
        }
        return csv.toString();
    }

    private static String jpegBase64(byte[] jpeg) {
        return Base64.getEncoder().encodeToString(jpeg);
    }

    private static String jsonString(String json, String name) {
        String key = "\"" + name + "\":\"";
        int start = json.indexOf(key);
        if (start < 0) {
            return null;
        }
        int valueStart = start + key.length();
        int valueEnd = json.indexOf('"', valueStart);
        return json.substring(valueStart, valueEnd).replace("\\/", "/");
    }

    private static HttpClient localHttpsClient() throws Exception {
        TrustManager[] trustManagers = {
                new X509TrustManager() {
                    @Override
                    public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                        return new java.security.cert.X509Certificate[0];
                    }

                    @Override
                    public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    }

                    @Override
                    public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) {
                    }
                }
        };
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagers, null);
        return HttpClient.newBuilder().sslContext(sslContext).build();
    }

    private static CompletableFuture<HttpResponse<Void>> openChatConnection(
            HttpClient client,
            MlServer server,
            String sessionId) throws Exception {
        CompletableFuture<HttpResponse<Void>> connection = client.sendAsync(
                HttpRequest.newBuilder(URI.create("https://localhost:" + server.port()
                                + "/chat/connect?group=group-1&sessionId=" + sessionId))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        Thread.sleep(500);
        return connection;
    }

    private static void drainJoinEvents(ChatClient client) {
        client.events().clear();
    }

    private static ServerEvent pollUntil(ChatClient client, java.util.function.Predicate<ServerEvent> predicate)
            throws InterruptedException {
        long timeoutAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < timeoutAt) {
            ServerEvent event = client.events().poll(10, TimeUnit.MILLISECONDS);
            if (event != null && predicate.test(event)) {
                return event;
            }
        }
        return null;
    }

    private abstract static class TestLlm extends LLM {
        TestLlm() {
            super("http://localhost", "", "test-model", false);
        }

        @Override
        public List<String> models() {
            return List.of("test-model");
        }

        @Override
        public String model() {
            return "test-model";
        }
    }

    private static class CountingLanguageModel extends TestLlm {
        int calls;

        @Override
        public List<Message> call(List<Message> messages, List<Tool> tools, Consumer<String> callback) {
            calls++;
            return List.of();
        }
    }

    private static class RecordingLanguageModel extends TestLlm {
        private final String response;
        private final CountDownLatch callsLatch = new CountDownLatch(1);
        private final List<List<String>> calls = new ArrayList<>();

        RecordingLanguageModel(String response) {
            this.response = response;
        }

        @Override
        public List<Message> call(List<Message> messages, List<Tool> tools, Consumer<String> callback) {
            synchronized (calls) {
                calls.add(messages.stream()
                        .map(message -> message.role() + ":" + message.message())
                        .toList());
            }
            callsLatch.countDown();
            callback.accept(response);
            return List.of(new Message("assistant", response));
        }

        boolean awaitCalls(int count) throws InterruptedException {
            long timeoutAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (System.nanoTime() < timeoutAt) {
                synchronized (calls) {
                    if (calls.size() >= count) {
                        return true;
                    }
                }
                callsLatch.await(10, TimeUnit.MILLISECONDS);
            }
            synchronized (calls) {
                return calls.size() >= count;
            }
        }

        List<List<String>> calls() {
            synchronized (calls) {
                return List.copyOf(calls);
            }
        }
    }

    private static class TranscriptAudioProcessor extends audio.AudioProcessor {
        TranscriptAudioProcessor(String transcript) {
            super(
                    samples -> false,
                    (buffer, start, end, prompt) -> audio.stt.Transcription.singleSegment(transcript, 0, audio.AudioProcessor.SAMPLE_RATE),
                    Runnable::run);
        }
    }

    private static class ToolCallingLanguageModel extends TestLlm {
        private final ResponseFunctionToolCall toolCall;
        private final CountDownLatch callsLatch = new CountDownLatch(1);
        private final List<Tool> tools = new ArrayList<>();

        ToolCallingLanguageModel(String id, String callId, String name, String arguments) {
            this.toolCall = ResponseFunctionToolCall.builder()
                    .id(id)
                    .callId(callId)
                    .name(name)
                    .arguments(arguments)
                    .type(JsonValue.from("function_call"))
                    .build();
        }

        @Override
        public List<Message> call(List<Message> messages, List<Tool> tools, Consumer<String> callback) {
            synchronized (this.tools) {
                this.tools.addAll(tools);
            }
            Tool tool = tools.stream()
                    .filter(candidate -> toolCall.name().equals(candidate.name))
                    .findFirst()
                    .orElseThrow();
            tool.exec(toolCall);
            callback.accept("登録しました。");
            callsLatch.countDown();
            return List.of(new Message("assistant", "登録しました。"));
        }

        boolean awaitCalls(int count) throws InterruptedException {
            return callsLatch.await(1, TimeUnit.SECONDS);
        }

        List<Tool> tools() {
            synchronized (tools) {
                return List.copyOf(tools);
            }
        }
    }
}
