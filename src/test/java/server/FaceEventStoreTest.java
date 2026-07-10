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
import org.junit.jupiter.api.Test;

class FaceEventStoreTest {
    @Test
    void faceEventEndpointSavesJsonAndJpeg() throws Exception {
        try (MlServer server = new MlServer(0)) {
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
    void knownFaceDistanceUnderThresholdIsMatched() throws Exception {
        Path knownFaces = Path.of("tmp", "face-events", "known-faces.json");
        Files.createDirectories(knownFaces.getParent());
        Files.writeString(knownFaces, """
                [{"personId":"person-1","personName":"山田","descriptor":[%s]}]
                """.formatted(csv(descriptor(0.2))), StandardCharsets.UTF_8);

        FaceEventResult result = new FaceEventStore().match(descriptor(0.2));

        assertTrue(result.known());
        assertEquals("person-1", result.personId());
        assertEquals("山田", result.personName());
        assertEquals(0.0, result.distance(), 0.000001);
    }

    @Test
    void knownFaceDistanceOverThresholdIsUnknown() throws Exception {
        Path knownFaces = Path.of("tmp", "face-events", "known-faces.json");
        Files.createDirectories(knownFaces.getParent());
        Files.writeString(knownFaces, """
                [{"personId":"person-1","personName":"山田","descriptor":[%s]}]
                """.formatted(csv(descriptor(1.0))), StandardCharsets.UTF_8);

        FaceEventResult result = new FaceEventStore().match(descriptor(0.0));

        assertFalse(result.known());
        assertEquals("unknown", result.personId());
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
