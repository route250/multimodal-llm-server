package server;

import java.time.Instant;

public record ServerEvent(String type, String message, Instant timestamp) {
    public static ServerEvent message(String message) {
        return new ServerEvent("message", message, Instant.now());
    }

    public static ServerEvent system(String message) {
        return new ServerEvent("system", message, Instant.now());
    }
}
