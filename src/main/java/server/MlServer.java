package server;

import audio.AudioDiagnostics;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import facedb.FaceDB;
import facedb.FacePossibility;
import json.Json;
import json.JsonFields;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class MlServer implements AutoCloseable {
    private static final Path STATIC_ROOT = Paths.get("src/main/resources/html").toAbsolutePath().normalize();
    private static final String DEFAULT_GROUP_ID = "group-1";

    private final HttpServer httpServer;
    private final ExecutorService executor;
    private final FaceDB faceDB;
    private final Map<String, ChatGroup> chatGroups = new ConcurrentHashMap<>();
    private final AtomicInteger counter = new AtomicInteger();

    public MlServer(int port) throws IOException {
        this(port, new FaceDB(Path.of(".local", "facedb")));
    }

    MlServer(int port, FaceDB faceDB) throws IOException {
        this.faceDB = faceDB;
        this.faceDB.load();
        createDefaultChatGroups();
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/chat/request", this::handleChatRequest);
        httpServer.createContext("/chat/playback", this::handleChatPlayback);
        httpServer.createContext("/chat/client-log", this::handleChatClientLog);
        httpServer.createContext("/chat/connect", this::handleChatConnect);
        httpServer.createContext("/face/event", this::handleFaceEvent);
        httpServer.createContext("/", this::handleStaticFile);
        executor = Executors.newVirtualThreadPerTaskExecutor();
        httpServer.setExecutor(executor);
    }

    public void start() {
        httpServer.start();
    }

    public void stop() {
        httpServer.stop(0);
        executor.shutdown();
    }

    public int port() {
        return httpServer.getAddress().getPort();
    }

    @Override
    public void close() {
        stop();
    }

    public int nextId() {
        return this.counter.incrementAndGet();
    }
    public void execute(Runnable r) {
        this.executor.execute(r);
    }
    public Future<?> submit(Runnable task) {
        return this.executor.submit(task);
    }
    public <T> Future<T> submit(Runnable task, T result ) {
        return this.executor.submit(task,result);
    }
    public <T> Future<T> submit(Callable<T> task) {
        return this.executor.submit(task);
    }

    void assignFaceName(String faceId, String name) {
        faceDB.assign(faceId, name);
    }

    private void handleStaticFile(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod()) && !"HEAD".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET, HEAD");
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        StaticResource resource = resolveStaticResource(exchange.getRequestURI().getPath());
        if (resource == null) {
            sendText(exchange, 404, "text/plain; charset=utf-8", "Not Found");
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", resource.contentType());
        exchange.sendResponseHeaders(200, "HEAD".equals(exchange.getRequestMethod()) ? -1 : resource.body().length);
        if ("GET".equals(exchange.getRequestMethod())) {
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(resource.body());
            }
        } else {
            exchange.close();
        }
    }

    private void handleChatRequest(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String sessionId = sessionId(exchange);
        ChatGroup chatGroup = chatGroup(exchange);
        if (chatGroup == null) {
            sendText(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"chat group not found\"}\n");
            return;
        }
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        byte[] body = exchange.getRequestBody().readAllBytes();
        ChatClient client = chatGroup.client(sessionId);
        if (client == null) {
            sendText(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"chat client not connected\"}\n");
            return;
        }
        ChatRequest request;
        try {
            request = ChatRequest.from(contentType, body);
            if ("audio".equals(request.type())) {
                long clientStartSampleIndex = longHeader(exchange, "X-Client-Mic-Start-Sample");
                long clientEndSampleIndexExclusive = longHeader(exchange, "X-Client-Mic-End-Sample");
                AudioDiagnostics.log("http-audio-received", AudioDiagnostics.context(chatGroup.id(), sessionId),
                        Json.fields(
                                "startSampleIndex", clientStartSampleIndex == Long.MIN_VALUE ? null : clientStartSampleIndex,
                                "endSampleIndexExclusive", clientEndSampleIndexExclusive == Long.MIN_VALUE
                                        ? null
                                        : clientEndSampleIndexExclusive,
                                "pcmBytes", request.body().length,
                                "contentType", request.contentType(),
                                "requestBytes", body.length));
                client.handleAudio(
                        request,
                        clientStartSampleIndex,
                        clientEndSampleIndexExclusive);
            } else {
                client.handle(request);
            }
        } catch (HttpRequestException e) {
            sendText(exchange, e.status(), "application/json; charset=utf-8",
                    errorJson(e.getMessage()));
            return;
        }

        String json = Json.object(Json.fields(
                "status", "accepted",
                "groupId", chatGroup.id(),
                "sessionId", sessionId,
                "type", request.type(),
                "bytes", body.length)) + "\n";
        sendText(exchange, 202, "application/json; charset=utf-8", json);
    }

    private void handleChatPlayback(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String sessionId = sessionId(exchange);
        ChatGroup chatGroup = chatGroup(exchange);
        if (chatGroup == null) {
            sendText(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"chat group not found\"}\n");
            return;
        }
        ChatClient client = chatGroup.client(sessionId);
        if (client == null) {
            sendText(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"chat client not connected\"}\n");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        try {
            client.handlePlayback(new ChatClient.PlaybackEvent(
                    JsonFields.longValue(body, "assistantTurnId"),
                    JsonFields.longOrDefault(body, "chunkId", 0),
                    JsonFields.string(body, "state"),
                    JsonFields.booleanOrDefault(body, "recognized", false),
                    JsonFields.doubleOrDefault(body, "playedSeconds", 0),
                    JsonFields.doubleOrDefault(body, "durationSeconds", 0),
                    JsonFields.longValue(body, "clientMicSampleIndex")));
        } catch (IllegalArgumentException e) {
            sendText(exchange, 400, "application/json; charset=utf-8",
                    errorJson(e.getMessage()));
            return;
        }

        sendText(exchange, 202, "application/json; charset=utf-8", "{\"status\":\"accepted\"}\n");
    }

    private void handleChatClientLog(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String sessionId = sessionId(exchange);
        ChatGroup chatGroup = chatGroup(exchange);
        if (chatGroup == null) {
            sendText(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"chat group not found\"}\n");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String event = JsonFields.stringOrDefault(body, "event", "unknown");
        AudioDiagnostics.log("browser-" + event, AudioDiagnostics.context(chatGroup.id(), sessionId),
                Json.fields(
                        "assistantTurnId", JsonFields.longOrNull(body, "assistantTurnId"),
                        "chunkId", JsonFields.longOrNull(body, "chunkId"),
                        "activeAssistantTurnId", JsonFields.longOrNull(body, "activeAssistantTurnId"),
                        "clientMicSampleIndex", JsonFields.longOrNull(body, "clientMicSampleIndex"),
                        "queuedAudioDeltas", JsonFields.longOrNull(body, "queuedAudioDeltas"),
                        "currentPlayback", JsonFields.booleanOrNull(body, "currentPlayback"),
                        "pausedPlayback", JsonFields.booleanOrNull(body, "pausedPlayback"),
                        "localVadPlaybackPaused", JsonFields.booleanOrNull(body, "localVadPlaybackPaused"),
                        "serverSttPlaybackPaused", JsonFields.booleanOrNull(body, "serverSttPlaybackPaused"),
                        "playbackReady", JsonFields.booleanOrNull(body, "playbackReady"),
                        "audioContextState", JsonFields.stringOrNull(body, "audioContextState"),
                        "detail", JsonFields.stringOrNull(body, "detail"),
                        "error", JsonFields.stringOrNull(body, "error")));

        sendText(exchange, 202, "application/json; charset=utf-8", "{\"status\":\"accepted\"}\n");
    }

    private void handleChatConnect(HttpExchange exchange) throws IOException {
        if (!"GET".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "GET");
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String sessionId = sessionId(exchange);
        ChatGroup chatGroup = chatGroup(exchange);
        if (chatGroup == null) {
            sendText(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"chat group not found\"}\n");
            return;
        }
        ChatClient client = chatGroup.join(sessionId);
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-cache");
        exchange.getResponseHeaders().set("Connection", "keep-alive");
        exchange.sendResponseHeaders(200, 0);

        try (OutputStream responseBody = exchange.getResponseBody()) {
            writeEvent(responseBody, ServerEvent.system("connected: " + chatGroup.id() + "/" + sessionId));
            responseBody.flush();
            while (!Thread.currentThread().isInterrupted()) {
                ServerEvent event = client.events().poll(15, TimeUnit.SECONDS);
                if (event == null) {
                    responseBody.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                } else {
                    logSseEvent(chatGroup.id(), sessionId, event);
                    writeEvent(responseBody, event);
                }
                responseBody.flush();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            chatGroup.leave(client);
        }
    }

    private void handleFaceEvent(HttpExchange exchange) throws IOException {
        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            exchange.sendResponseHeaders(405, -1);
            return;
        }

        String sessionId = sessionId(exchange);
        ChatGroup chatGroup = chatGroup(exchange);
        if (chatGroup == null) {
            sendText(exchange, 404, "application/json; charset=utf-8", "{\"error\":\"chat group not found\"}\n");
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        FaceEventResult result;
        try {
            FaceEventRequest request = FaceEventRequest.fromJson(body);
            result = faceEventResult(request);
        } catch (IllegalArgumentException e) {
            sendText(exchange, 400, "application/json; charset=utf-8", errorJson(e.getMessage()));
            return;
        }

        ChatClient client = chatGroup.client(sessionId);
        if (client != null) {
            client.handleFacePresence(result);
        }
        sendText(exchange, 202, "application/json; charset=utf-8", result.toJson());
    }

    private FaceEventResult faceEventResult(FaceEventRequest request) {
        if ("person-left".equals(request.eventType())) {
            return FaceEventResult.left();
        }

        FacePossibility registered = faceDB.register(request.descriptor(), request.imageDataUrl());
        FacePossibility.PersonPossibility nearest = nearest(registered.personPossibilities);
        if (nearest == null) {
            return new FaceEventResult(
                    "accepted",
                    "unknown",
                    "unknown",
                    null,
                    false,
                    registered.faceId,
                    registered.jsonPath,
                    registered.imagePath,
                    request.presenceState());
        }
        return new FaceEventResult(
                "accepted",
                nearest.personId,
                nearest.name,
                (double) nearest.distance,
                true,
                registered.faceId,
                registered.jsonPath,
                registered.imagePath,
                request.presenceState());
    }

    private static FacePossibility.PersonPossibility nearest(FacePossibility.PersonPossibility[] possibilities) {
        if (possibilities == null || possibilities.length == 0) {
            return null;
        }
        return possibilities[0];
    }

    private void createDefaultChatGroups() {
        chatGroups.put("group-1", new ChatGroup("group-1", this));
        chatGroups.put("group-2", new ChatGroup("group-2", this));
        chatGroups.put("group-3", new ChatGroup("group-3", this));
    }

    private static void sendText(HttpExchange exchange, int status, String contentType, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream responseBody = exchange.getResponseBody()) {
            responseBody.write(body);
        }
    }

    private static StaticResource resolveStaticResource(String requestPath) throws IOException {
        String normalizedRequestPath = normalizeRequestPath(requestPath);
        Path filePath = STATIC_ROOT.resolve(normalizedRequestPath).normalize();
        if (!filePath.startsWith(STATIC_ROOT)) {
            return null;
        }
        if (Files.isDirectory(filePath)) {
            filePath = filePath.resolve("index.html").normalize();
        }
        if (Files.isRegularFile(filePath)) {
            return new StaticResource(Files.readAllBytes(filePath), contentType(filePath));
        }

        String resourcePath = classpathResourcePath(normalizedRequestPath);
        try (var stream = MlServer.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                String indexResourcePath = classpathResourcePath(normalizedRequestPath + "/index.html");
                try (var indexStream = MlServer.class.getResourceAsStream(indexResourcePath)) {
                    if (indexStream == null) {
                        return null;
                    }
                    return new StaticResource(indexStream.readAllBytes(), contentType(Path.of(indexResourcePath)));
                }
            }
            return new StaticResource(stream.readAllBytes(), contentType(Path.of(resourcePath)));
        }
    }

    private static String normalizeRequestPath(String requestPath) {
        String path = urlDecode(requestPath);
        if (path.equals("/") || path.isBlank()) {
            return "index.html";
        }
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private static String classpathResourcePath(String requestPath) {
        String resourcePath = "/html/" + requestPath;
        if (resourcePath.endsWith("/")) {
            return resourcePath + "index.html";
        }
        return resourcePath;
    }

    private static String contentType(Path path) throws IOException {
        String detectedType = Files.probeContentType(path);
        if (detectedType != null) {
            return withCharset(detectedType);
        }

        String fileName = path.getFileName().toString();
        int dotIndex = fileName.lastIndexOf('.');
        String extension = dotIndex < 0 ? "" : fileName.substring(dotIndex + 1).toLowerCase();
        return switch (extension) {
            case "html" -> "text/html; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "js" -> "text/javascript; charset=utf-8";
            case "json" -> "application/json; charset=utf-8";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "wasm" -> "application/wasm";
            case "wav" -> "audio/wav";
            case "mp3" -> "audio/mpeg";
            default -> "application/octet-stream";
        };
    }

    private static String withCharset(String contentType) {
        if (contentType.startsWith("text/") || "application/javascript".equals(contentType)) {
            return contentType + "; charset=utf-8";
        }
        return contentType;
    }

    private static void writeEvent(OutputStream responseBody, ServerEvent event) throws IOException {
        String payload = """
                event: %s
                data: %s

                """.formatted(event.type(), Json.object(Json.fields(
                "message", event.message(),
                "timestamp", event.timestamp().toString())));
        responseBody.write(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static String sessionId(HttpExchange exchange) {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        return query.getOrDefault("sessionId", "default");
    }

    private ChatGroup chatGroup(HttpExchange exchange) {
        Map<String, String> query = parseQuery(exchange.getRequestURI().getRawQuery());
        return chatGroups.get(query.getOrDefault("group", DEFAULT_GROUP_ID));
    }

    private static Map<String, String> parseQuery(String rawQuery) {
        Map<String, String> values = new ConcurrentHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }
        for (String pair : rawQuery.split("&")) {
            int separator = pair.indexOf('=');
            if (separator < 0) {
                values.put(urlDecode(pair), "");
            } else {
                values.put(urlDecode(pair.substring(0, separator)), urlDecode(pair.substring(separator + 1)));
            }
        }
        return values;
    }

    private static String urlDecode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static long longHeader(HttpExchange exchange, String name) {
        String value = exchange.getRequestHeaders().getFirst(name);
        if (value == null || value.isBlank()) {
            return Long.MIN_VALUE;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return Long.MIN_VALUE;
        }
    }

    private static void logSseEvent(String groupId, String sessionId, ServerEvent event) {
        if (!"audio-delta".equals(event.type())
                && !"assistant-audio-chunk".equals(event.type())
                && !"audio-control".equals(event.type())
                && !"message-done".equals(event.type())) {
            return;
        }
        AudioDiagnostics.log("sse-send-" + event.type(), AudioDiagnostics.context(groupId, sessionId),
                Json.fields(
                        "messageChars", event.message().length(),
                        "assistantTurnId", JsonFields.longOrNull(event.message(), "assistantTurnId"),
                        "chunkId", JsonFields.longOrNull(event.message(), "chunkId"),
                        "action", JsonFields.stringOrNull(event.message(), "action"),
                        "sampleRate", JsonFields.longOrNull(event.message(), "sampleRate")));
    }

    private static String errorJson(String message) {
        return Json.object(Json.fields("error", message)) + "\n";
    }

    private record StaticResource(byte[] body, String contentType) {
    }

}
