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

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import facedb.FaceDB;
import json.JsonFields;
import llm.ChatMessage;
import llm.StreamingResponseHandler;
import llm.ToolCall;
import llm.ToolCallResult;
import llm.ToolDefinition;

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
    void facePresenceEnteredStartsLlmFromConversationHistory() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            RecordingLanguageModel languageModel = new RecordingLanguageModel("確認します。");
            ChatClient client = new ChatClient(
                    "client-1",
                    group,
                    new TranscriptAudioProcessor("unused"),
                    languageModel);

            client.handleFacePresence(FaceEventResult.unknownFace("sample-000000"));
            assertTrue(languageModel.awaitCalls(1));
            client.handleFacePresence(FaceEventResult.left());

            assertEquals(1, languageModel.calls().size());
            assertEquals(List.of(
                    "system:[カメラ情報] { \"name\": \"unknown\", \"trackId\": \"legacy\", \"comment\": \"人物を認識しました\" }"),
                    languageModel.calls().get(0));
            var history = client.conversationHistoryForTest();
            assertEquals(2, history.size());
            assertEquals("system", history.get(0).role());
            assertEquals("[カメラ情報] { \"name\": \"unknown\", \"trackId\": \"legacy\", \"comment\": \"人物を認識しました\" }",
                    history.get(0).text());
            assertEquals("system", history.get(1).role());
            assertEquals("[カメラ情報] { \"comment\": \"だれもいなくなりました\"}", history.get(1).text());
        }
    }

    @Test
    void facePresenceUpdateIsPublishedWithoutConversationHistory() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            ChatClient listener = group.join("listener");
            ChatClient client = new ChatClient(
                    "client-1",
                    group,
                    new TranscriptAudioProcessor("unused"),
                    new CountingLanguageModel());
            drainJoinEvents(listener);

            client.handleFacePresence(FaceEventResult.unknown().withPresenceState("person-updated"));

            assertTrue(client.conversationHistoryForTest().isEmpty());
            ServerEvent event = listener.events().poll(1, java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(event);
            assertEquals("face-presence", event.type());
            assertTrue(event.message().contains("\"state\":\"person-updated\""));
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
            ToolCallingLanguageModel languageModel = new ToolCallingLanguageModel(new ToolCall(
                    "fc_1",
                    "call_1",
                    "assign_face_name",
                    "{\"trackId\":\"trak-000000\",\"name\":\"太郎\"}"));
            ChatClient client = new ChatClient(
                    "client-1",
                    group,
                    new TranscriptAudioProcessor("unused"),
                    languageModel);

            client.handle(ChatRequest.from(
                    "text/plain; charset=utf-8",
                    "私の名前は、太郎です".getBytes(StandardCharsets.UTF_8)));

            assertTrue(languageModel.awaitCalls(1));
            assertTrue(languageModel.tools().stream().anyMatch(tool -> "assign_face_name".equals(tool.name())));
            ServerEvent confirmation = pollUntil(listener, event -> "assistant-audio-chunk".equals(event.type()));
            assertNotNull(confirmation);
            assertTrue(confirmation.message().contains("登録しました。"));
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

    private static class CountingLanguageModel implements llm.LanguageModel {
        int calls;

        @Override
        public String respond(String userText) {
            calls++;
            return "unused";
        }
    }

    private static class RecordingLanguageModel implements llm.LanguageModel {
        private final String response;
        private final CountDownLatch callsLatch = new CountDownLatch(1);
        private final List<List<String>> calls = new ArrayList<>();

        RecordingLanguageModel(String response) {
            this.response = response;
        }

        @Override
        public String respond(String userText) {
            return response;
        }

        @Override
        public void respondStreaming(List<ChatMessage> messages, java.util.function.Consumer<String> onDelta) {
            synchronized (calls) {
                calls.add(messages.stream()
                        .map(message -> message.role() + ":" + message.text())
                        .toList());
            }
            callsLatch.countDown();
            onDelta.accept(response);
        }

        @Override
        public void respondStreamingEvents(
                List<ChatMessage> messages,
                List<ToolDefinition> tools,
                StreamingResponseHandler handler) {
            respondStreaming(messages, handler::onTextDelta);
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

    private static class ToolCallingLanguageModel implements llm.LanguageModel {
        private final ToolCall toolCall;
        private final CountDownLatch callsLatch = new CountDownLatch(1);
        private final List<ToolDefinition> tools = new ArrayList<>();

        ToolCallingLanguageModel(ToolCall toolCall) {
            this.toolCall = toolCall;
        }

        @Override
        public String respond(String userText) {
            return "unused";
        }

        @Override
        public void respondStreamingEvents(
                List<ChatMessage> messages,
                List<ToolDefinition> tools,
                StreamingResponseHandler handler) {
            synchronized (this.tools) {
                this.tools.addAll(tools);
            }
            handler.onToolCall(toolCall);
            callsLatch.countDown();
        }

        @Override
        public void respondStreamingEvents(
                List<ChatMessage> messages,
                List<ToolDefinition> tools,
                List<ToolCallResult> toolResults,
                StreamingResponseHandler handler) {
            if (toolResults.isEmpty()) {
                respondStreamingEvents(messages, tools, handler);
                return;
            }
            handler.onTextDelta("登録しました。");
        }

        boolean awaitCalls(int count) throws InterruptedException {
            return callsLatch.await(1, TimeUnit.SECONDS);
        }

        List<ToolDefinition> tools() {
            synchronized (tools) {
                return List.copyOf(tools);
            }
        }
    }
}
