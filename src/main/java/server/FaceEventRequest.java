package server;

import json.JsonFields;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ブラウザから届いた顔イベント JSON を、サーバ内部で扱う値に変換します。
 */
record FaceEventRequest(
        String eventType,
        double[] descriptor,
        String imageDataUrl) {
    private static final Pattern DESCRIPTOR_PATTERN = Pattern.compile("\"descriptor\"\\s*:\\s*\\[([^\\]]*)]", Pattern.DOTALL);

    static FaceEventRequest fromJson(String body) {
        String eventType = JsonFields.stringOrDefault(body, "eventType", "person-detected");
        if ("person-left".equals(eventType)) {
            return new FaceEventRequest(eventType, null, null);
        }
        return new FaceEventRequest(
                eventType,
                descriptor(body),
                JsonFields.string(body, "imageDataUrl"));
    }

    String presenceState() {
        if ("person-updated".equals(eventType)) {
            return "person-updated";
        }
        if ("person-left".equals(eventType)) {
            return "person-left";
        }
        return "person-entered";
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
}
