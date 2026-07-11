package server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import facedb.FaceDB;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FaceEventTest {
    @Test
    void faceEventEndpointSavesJsonAndJpeg(@TempDir Path tempDir) throws Exception {
        try (MlServer server = new MlServer(0, new FaceDB(tempDir))) {
            server.start();
            String body = faceEventBody(descriptor(0.1));

            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + server.port()
                                    + "/face/event?group=group-1&sessionId=test-face"))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertEquals(202, response.statusCode());
            assertTrue(response.body().contains("\"known\":false"));
            assertEquals("face000000", jsonString(response.body(), "faceId"));
            String jsonPath = jsonString(response.body(), "jsonPath");
            String imagePath = jsonString(response.body(), "imagePath");
            assertNotNull(jsonPath);
            assertNotNull(imagePath);
            assertTrue(Files.isRegularFile(Path.of(jsonPath)));
            assertTrue(Files.isRegularFile(Path.of(imagePath)));
            assertTrue(Files.size(Path.of(imagePath)) > 0);
        }
    }

    @Test
    void faceEventEndpointMatchesNamedFaceFromFaceDB(@TempDir Path tempDir) throws Exception {
        FaceDB db = new FaceDB(tempDir);
        db.register(descriptor(0.2), jpegBase64(new byte[] {(byte) 0xff, (byte) 0xd8, 1, (byte) 0xff, (byte) 0xd9}));
        db.assign("face000000", "山田");

        try (MlServer server = new MlServer(0, db)) {
            server.start();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + server.port()
                                    + "/face/event?group=group-1&sessionId=test-face"))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString(faceEventBody(descriptor(0.2)), StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertEquals(202, response.statusCode());
            assertTrue(response.body().contains("\"known\":true"));
            assertEquals("face000001", jsonString(response.body(), "faceId"));
            assertEquals("person0000", jsonString(response.body(), "personId"));
            assertEquals("山田", jsonString(response.body(), "personName"));
            assertTrue(Files.isRegularFile(tempDir.resolve("face000001.json")));
            assertTrue(Files.isRegularFile(tempDir.resolve("face000001.jpg")));
        }
    }

    @Test
    void personLeftDoesNotRegisterFaceDBFile(@TempDir Path tempDir) throws Exception {
        try (MlServer server = new MlServer(0, new FaceDB(tempDir))) {
            server.start();
            HttpResponse<String> response = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create("http://localhost:" + server.port()
                                    + "/face/event?group=group-1&sessionId=test-face"))
                            .header("Content-Type", "application/json; charset=utf-8")
                            .POST(HttpRequest.BodyPublishers.ofString("""
                                    {"eventType":"person-left","eventId":"left-1","clientTimestamp":"2026-07-09T00:00:00Z"}
                                    """, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            assertEquals(202, response.statusCode());
            assertEquals("person-left", jsonString(response.body(), "presenceState"));
            assertEquals("none", jsonString(response.body(), "faceId"));
            assertFalse(Files.exists(tempDir.resolve("face000000.json")));
            assertFalse(Files.exists(tempDir.resolve("face000000.jpg")));
        }
    }

    @Test
    void facePresenceEventIsAddedToConversationHistoryWithoutStartingLlm() throws Exception {
        try (MlServer server = new MlServer(0)) {
            ChatGroup group = new ChatGroup("group-test", server);
            CountingLanguageModel languageModel = new CountingLanguageModel();
            ChatClient client = new ChatClient(
                    "client-1",
                    group,
                    new TranscriptAudioProcessor("unused"),
                    languageModel);

            client.handleFacePresence(FaceEventResult.unknown());
            client.handleFacePresence(FaceEventResult.left());

            assertEquals(0, languageModel.calls);
            assertEquals(2, client.conversationHistoryForTest().size());
            assertEquals("[環境イベント] unknownさんがきました", client.conversationHistoryForTest().get(0).text());
            assertEquals("[環境イベント] だれもいなくなりました", client.conversationHistoryForTest().get(1).text());
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

    private static void drainJoinEvents(ChatClient client) {
        client.events().clear();
    }

    private static class CountingLanguageModel implements llm.LanguageModel {
        int calls;

        @Override
        public String respond(String userText) {
            calls++;
            return "unused";
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
}
