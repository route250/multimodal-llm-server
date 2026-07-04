package server;

import java.time.Instant;

public record ServerEvent(String type, String message, Instant timestamp) {
    public static ServerEvent message(String message) {
        return new ServerEvent("message", message, Instant.now());
    }

    public static ServerEvent userMessage(String message) {
        return new ServerEvent("user-message", message, Instant.now());
    }

    public static ServerEvent messageDelta(String message) {
        return new ServerEvent("message-delta", message, Instant.now());
    }

    public static ServerEvent messageDone() {
        return new ServerEvent("message-done", "", Instant.now());
    }

    public static ServerEvent audioDelta(String data, String format, int sampleRate) {
        return audioDelta(data, format, sampleRate, 0);
    }

    public static ServerEvent audioDelta(String data, String format, int sampleRate, long assistantTurnId) {
        String json = """
                {"data":"%s","format":"%s","sampleRate":%d,"assistantTurnId":%d}
                """.formatted(jsonEscape(data), jsonEscape(format), sampleRate, assistantTurnId);
        return new ServerEvent("audio-delta", json.strip(), Instant.now());
    }

    public static ServerEvent audioControl(
            String action,
            long assistantTurnId,
            long interruptionId,
            long speechSequenceId,
            String reason) {
        String json = """
                {"action":"%s","assistantTurnId":%d,"interruptionId":%d,"speechSequenceId":%d,"reason":"%s"}
                """.formatted(
                jsonEscape(action),
                assistantTurnId,
                interruptionId,
                speechSequenceId,
                jsonEscape(reason));
        return new ServerEvent("audio-control", json.strip(), Instant.now());
    }

    public static ServerEvent system(String message) {
        return new ServerEvent("system", message, Instant.now());
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
