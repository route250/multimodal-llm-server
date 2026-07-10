package server;

import json.Json;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import audio.tts.AudioDelta;

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
        String json = Json.object(Json.fields("state", state));
        return new ServerEvent("assistant-state", json.strip(), Instant.now());
    }

    public static ServerEvent transcriptPartial(long speechSequenceId, String text) {
        String json = Json.object(Json.fields(
                "speechSequenceId", speechSequenceId,
                "text", text));
        return new ServerEvent("transcript-partial", json.strip(), Instant.now());
    }

    public static ServerEvent audioDelta(String data, String format, int sampleRate) {
        return audioDelta(data, format, sampleRate, 0);
    }

    public static ServerEvent audioDelta(String data, String format, int sampleRate, long assistantTurnId) {
        String json = Json.object(Json.fields(
                "data", data,
                "format", format,
                "sampleRate", sampleRate,
                "assistantTurnId", assistantTurnId));
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
            audio.append(Json.object(Json.fields(
                    "data", delta.data(),
                    "format", delta.format(),
                    "sampleRate", delta.sampleRate())));
        }
        audio.append("]");
        String json = Json.object(Json.fields(
                "assistantTurnId", assistantTurnId,
                "chunkId", chunkId,
                "text", text,
                "audioDeltas", Json.raw(audio.toString()),
                "audioDurationSeconds", Json.raw(String.format(Locale.ROOT, "%.6f", audioDurationSeconds))));
        return new ServerEvent("assistant-audio-chunk", json.strip(), Instant.now());
    }

    public static ServerEvent audioControl(
            String action,
            long assistantTurnId,
            long interruptionId,
            long speechSequenceId,
            String reason) {
        String json = Json.object(Json.fields(
                "action", action,
                "assistantTurnId", assistantTurnId,
                "interruptionId", interruptionId,
                "speechSequenceId", speechSequenceId,
                "reason", reason));
        return new ServerEvent("audio-control", json.strip(), Instant.now());
    }

    public static ServerEvent speechState(
            String previousState,
            String currentState,
            long speechSequenceId,
            long sampleIndex) {
        String json = Json.object(Json.fields(
                "previousState", previousState,
                "currentState", currentState,
                "speechSequenceId", speechSequenceId,
                "sampleIndex", sampleIndex));
        return new ServerEvent("speech-state", json.strip(), Instant.now());
    }

    public static ServerEvent facePresence(FaceEventResult result) {
        String json = Json.object(Json.fields(
                "state", result.presenceState(),
                "personId", result.personId(),
                "personName", result.personName(),
                "distance", result.distance(),
                "known", result.known()));
        return new ServerEvent("face-presence", json.strip(), Instant.now());
    }

    public static ServerEvent system(String message) {
        return new ServerEvent("system", message, Instant.now());
    }
}
