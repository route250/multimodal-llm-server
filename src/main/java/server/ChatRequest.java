package server;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public record ChatRequest(String type, String responseText, String contentType, Map<String, String> contentTypeParameters,
                          byte[] body) {
    public static ChatRequest from(String contentType, byte[] body) {
        ParsedContentType parsedContentType = parseContentType(contentType);
        String normalizedType = parsedContentType.mediaType();
        if (normalizedType.startsWith("text/") || "application/json".equals(normalizedType)) {
            String text = new String(body, StandardCharsets.UTF_8);
            return new ChatRequest("text", "received text: " + text, normalizedType, parsedContentType.parameters(), body);
        }
        if (normalizedType.startsWith("audio/")) {
            return new ChatRequest("audio", "received audio chunk: " + body.length + " bytes (" + normalizedType + ")",
                    normalizedType, parsedContentType.parameters(), body);
        }
        return new ChatRequest("binary", "received binary chunk: " + body.length + " bytes (" + normalizedType + ")",
                normalizedType, parsedContentType.parameters(), body);
    }

    public ServerEvent toEvent() {
        return ServerEvent.message(responseText);
    }

    public boolean isPcm16LeAudio() {
        if (!"audio/pcm".equals(contentType)) {
            return false;
        }
        return "16000".equals(contentTypeParameters.get("rate"))
                && "1".equals(contentTypeParameters.get("channels"))
                && "s16le".equals(contentTypeParameters.get("format"));
    }

    private static ParsedContentType parseContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return new ParsedContentType("application/octet-stream", Map.of());
        }
        String[] parts = contentType.split(";");
        String mediaType = parts[0].trim().toLowerCase();
        Map<String, String> parameters = new ConcurrentHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            String parameter = parts[i].trim();
            int separator = parameter.indexOf('=');
            if (separator > 0) {
                String name = parameter.substring(0, separator).trim().toLowerCase();
                String value = parameter.substring(separator + 1).trim().toLowerCase();
                parameters.put(name, unquote(value));
            }
        }
        return new ParsedContentType(mediaType, parameters);
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private record ParsedContentType(String mediaType, Map<String, String> parameters) {
    }
}
