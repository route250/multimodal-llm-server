package server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class Server implements AutoCloseable {
    private static final Path STATIC_ROOT = Paths.get("src/main/resources/html").toAbsolutePath().normalize();
    private static final String DEFAULT_GROUP_ID = "group-1";

    private final HttpServer httpServer;
    private final ExecutorService executor;
    private final Map<String, ChatGroup> chatGroups = new ConcurrentHashMap<>();

    public Server(int port) throws IOException {
        createDefaultChatGroups();
        httpServer = HttpServer.create(new InetSocketAddress(port), 0);
        httpServer.createContext("/chat/request", this::handleChatRequest);
        httpServer.createContext("/chat/connect", this::handleChatConnect);
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
        ChatRequest request = ChatRequest.from(contentType, body);
        client.handle(request);

        String json = """
                {"status":"accepted","groupId":"%s","sessionId":"%s","type":"%s","bytes":%d}
                """.formatted(jsonEscape(chatGroup.id()), jsonEscape(sessionId), jsonEscape(request.type()), body.length);
        sendText(exchange, 202, "application/json; charset=utf-8", json);
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

    private void createDefaultChatGroups() {
        chatGroups.put("group-1", new ChatGroup("group-1"));
        chatGroups.put("group-2", new ChatGroup("group-2"));
        chatGroups.put("group-3", new ChatGroup("group-3"));
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
        try (var stream = Server.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                String indexResourcePath = classpathResourcePath(normalizedRequestPath + "/index.html");
                try (var indexStream = Server.class.getResourceAsStream(indexResourcePath)) {
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
                data: {"message":"%s","timestamp":"%s"}

                """.formatted(event.type(), jsonEscape(event.message()), event.timestamp());
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

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private record StaticResource(byte[] body, String contentType) {
    }

}
