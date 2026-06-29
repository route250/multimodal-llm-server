package server;

import java.nio.charset.StandardCharsets;

public record ChatRequest(String type, String responseText) {
    public static ChatRequest from(String contentType, byte[] body) {
        String normalizedType = contentType == null ? "application/octet-stream" : contentType.split(";", 2)[0];
        if (normalizedType.startsWith("text/") || "application/json".equals(normalizedType)) {
            String text = new String(body, StandardCharsets.UTF_8);
            return new ChatRequest("text", "received text: " + text);
        }
        if (normalizedType.startsWith("audio/")) {
            return new ChatRequest("audio", "received audio chunk: " + body.length + " bytes (" + normalizedType + ")");
        }
        return new ChatRequest("binary", "received binary chunk: " + body.length + " bytes (" + normalizedType + ")");
    }

    public ServerEvent toEvent() {
        return ServerEvent.message(responseText);
    }
}
