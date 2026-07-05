package server;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import tts.AudioDelta;

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

    public static ServerEvent assistantState(String state) {
        String json = """
                {"state":"%s"}
                """.formatted(jsonEscape(state));
        return new ServerEvent("assistant-state", json.strip(), Instant.now());
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

    public static ServerEvent assistantAudioChunk(
            long assistantTurnId,
            long chunkId,
            String text,
            List<AudioDelta> audioDeltas,
            double audioDurationSeconds) {
        StringBuilder audio = new StringBuilder();
        audio.append("[");
        for (int i = 0; i < audioDeltas.size(); i++) {
            AudioDelta delta = audioDeltas.get(i);
            if (i > 0) {
                audio.append(",");
            }
            audio.append("""
                    {"data":"%s","format":"%s","sampleRate":%d}
                    """.formatted(jsonEscape(delta.data()), jsonEscape(delta.format()), delta.sampleRate()).strip());
        }
        audio.append("]");
        String json = String.format(Locale.ROOT, """
                {"assistantTurnId":%d,"chunkId":%d,"text":"%s","audioDeltas":%s,"audioDurationSeconds":%.6f}
                """,
                assistantTurnId,
                chunkId,
                jsonEscape(text),
                audio,
                audioDurationSeconds);
        return new ServerEvent("assistant-audio-chunk", json.strip(), Instant.now());
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

    public static ServerEvent speechState(
            String previousState,
            String currentState,
            long speechSequenceId,
            long sampleIndex) {
        String json = """
                {"previousState":"%s","currentState":"%s","speechSequenceId":%d,"sampleIndex":%d}
                """.formatted(
                jsonEscape(previousState),
                jsonEscape(currentState),
                speechSequenceId,
                sampleIndex);
        return new ServerEvent("speech-state", json.strip(), Instant.now());
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
