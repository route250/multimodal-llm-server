package server;

import json.Json;
import json.JsonFields;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ブラウザで検出した顔イベントを保存し、既知特徴量との距離で人物を推定します。
 */
public class FaceEventStore {
    static final double MATCH_DISTANCE_THRESHOLD = 0.45;
    private static final Path ROOT = Path.of("tmp", "face-events");
    private static final Path KNOWN_FACES = ROOT.resolve("known-faces.json");
    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS");
    private static final Pattern OBJECT_PATTERN = Pattern.compile("\\{[^{}]*}", Pattern.DOTALL);
    private static final Pattern DESCRIPTOR_PATTERN = Pattern.compile("\"descriptor\"\\s*:\\s*\\[([^\\]]*)]", Pattern.DOTALL);

    public FaceEventResult handle(String groupId, String sessionId, String body) throws IOException {
        String eventType = JsonFields.stringOrDefault(body, "eventType", "person-detected");
        String eventId = JsonFields.stringOrDefault(body, "eventId", "event-" + System.nanoTime());
        String clientTimestamp = JsonFields.stringOrDefault(body, "clientTimestamp", OffsetDateTime.now().toString());
        if ("person-left".equals(eventType)) {
            FaceEventResult result = FaceEventResult.left();
            saveJson(groupId, sessionId, eventId, Json.object(Json.fields(
                    "eventType", eventType,
                    "eventId", eventId,
                    "clientTimestamp", clientTimestamp,
                    "serverTimestamp", OffsetDateTime.now().toString(),
                    "groupId", groupId,
                    "sessionId", sessionId,
                    "known", false)));
            return result;
        }

        double[] descriptor = descriptor(body);
        String imageDataUrl = JsonFields.string(body, "imageDataUrl");
        FaceEventResult result = match(descriptor).withPresenceState(presenceState(eventType));
        SavedFiles files = saveImageAndJson(groupId, sessionId, eventId, body, imageDataUrl, result);
        return result.withFiles(files.jsonPath(), files.imagePath());
    }

    public FaceEventResult match(double[] descriptor) throws IOException {
        FaceEventResult nearest = FaceEventResult.unknown();
        for (KnownFace knownFace : knownFaces()) {
            double distance = distance(descriptor, knownFace.descriptor());
            if (nearest.distance() == null || distance < nearest.distance()) {
                nearest = new FaceEventResult(
                        "accepted",
                        knownFace.personId(),
                        knownFace.personName(),
                        distance,
                        distance <= MATCH_DISTANCE_THRESHOLD,
                        null,
                        null,
                        "person-entered");
            }
        }
        if (!nearest.known()) {
            return FaceEventResult.unknown(nearest.distance());
        }
        return nearest;
    }

    private SavedFiles saveImageAndJson(
            String groupId,
            String sessionId,
            String eventId,
            String body,
            String imageDataUrl,
            FaceEventResult result) throws IOException {
        Path dir = ROOT.resolve(LocalDateTime.now().format(DAY_FORMAT));
        Files.createDirectories(dir);
        String baseName = "%s-%s-%s-%s".formatted(
                LocalDateTime.now().format(FILE_TIMESTAMP),
                sanitize(groupId),
                sanitize(sessionId),
                sanitize(eventId));
        Path imagePath = dir.resolve(baseName + ".jpg");
        Files.write(imagePath, decodeImageDataUrl(imageDataUrl));
        Path jsonPath = dir.resolve(baseName + ".json");
        String json = Json.object(Json.fields(
                "serverTimestamp", OffsetDateTime.now().toString(),
                "groupId", groupId,
                "sessionId", sessionId,
                "request", Json.raw(body),
                "personId", result.personId(),
                "personName", result.personName(),
                "distance", result.distance(),
                "known", result.known(),
                "imagePath", imagePath.toAbsolutePath().normalize().toString()));
        Files.writeString(jsonPath, json + System.lineSeparator(), StandardCharsets.UTF_8);
        return new SavedFiles(
                jsonPath.toAbsolutePath().normalize().toString(),
                imagePath.toAbsolutePath().normalize().toString());
    }

    private void saveJson(String groupId, String sessionId, String eventId, String json) throws IOException {
        Path dir = ROOT.resolve(LocalDateTime.now().format(DAY_FORMAT));
        Files.createDirectories(dir);
        String baseName = "%s-%s-%s-%s".formatted(
                LocalDateTime.now().format(FILE_TIMESTAMP),
                sanitize(groupId),
                sanitize(sessionId),
                sanitize(eventId));
        Files.writeString(dir.resolve(baseName + ".json"), json + System.lineSeparator(), StandardCharsets.UTF_8);
    }

    private List<KnownFace> knownFaces() throws IOException {
        if (!Files.isRegularFile(KNOWN_FACES)) {
            return List.of();
        }
        String json = Files.readString(KNOWN_FACES, StandardCharsets.UTF_8);
        List<KnownFace> faces = new ArrayList<>();
        Matcher matcher = OBJECT_PATTERN.matcher(json);
        while (matcher.find()) {
            String object = matcher.group();
            String personId = JsonFields.stringOrDefault(object, "personId", "unknown");
            String personName = JsonFields.stringOrDefault(object, "personName", personId);
            Matcher descriptorMatcher = DESCRIPTOR_PATTERN.matcher(object);
            if (descriptorMatcher.find()) {
                faces.add(new KnownFace(personId, personName, numberArray(descriptorMatcher.group(1))));
            }
        }
        return faces;
    }

    private static double[] descriptor(String body) {
        Matcher matcher = DESCRIPTOR_PATTERN.matcher(body);
        if (!matcher.find()) {
            throw new IllegalArgumentException("descriptor is required");
        }
        double[] descriptor = numberArray(matcher.group(1));
        if (descriptor.length != 128) {
            throw new IllegalArgumentException("descriptor must contain 128 numbers");
        }
        return descriptor;
    }

    private static double[] numberArray(String csv) {
        String[] parts = csv.split(",");
        List<Double> numbers = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            numbers.add(Double.parseDouble(trimmed));
        }
        double[] values = new double[numbers.size()];
        for (int i = 0; i < numbers.size(); i++) {
            values[i] = numbers.get(i);
        }
        return values;
    }

    private static byte[] decodeImageDataUrl(String imageDataUrl) {
        String prefix = "data:image/jpeg;base64,";
        if (!imageDataUrl.startsWith(prefix)) {
            throw new IllegalArgumentException("imageDataUrl must be a JPEG data URL");
        }
        return Base64.getDecoder().decode(imageDataUrl.substring(prefix.length()));
    }

    private static String presenceState(String eventType) {
        if ("person-updated".equals(eventType)) {
            return "person-updated";
        }
        return "person-entered";
    }

    private static double distance(double[] left, double[] right) {
        if (left.length != right.length) {
            return Double.MAX_VALUE;
        }
        double total = 0;
        for (int i = 0; i < left.length; i++) {
            double diff = left[i] - right[i];
            total += diff * diff;
        }
        return Math.sqrt(total);
    }

    private static String sanitize(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        StringBuilder sanitized = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-') {
                sanitized.append(c);
            } else {
                sanitized.append('_');
            }
        }
        return sanitized.toString().toLowerCase(Locale.ROOT);
    }

    private record KnownFace(String personId, String personName, double[] descriptor) {
    }

    private record SavedFiles(String jsonPath, String imagePath) {
    }
}
