package json;

import java.util.Optional;

/**
 * 既知の JSON フィールドだけを読むための軽量ユーティリティです。
 */
public final class JsonFields {
    private JsonFields() {
    }

    public static String string(String json, String name) {
        String value = field(json, name);
        if (isStringValue(value)) {
            return Json.unescape(value.substring(1, value.length() - 1));
        }
        throw new IllegalArgumentException(name + " must be a string");
    }

    public static Optional<String> string(String json, String name, int startIndex) {
        String value = fieldOrNull(json, name, startIndex);
        if (value == null || "null".equals(value) || !isStringValue(value)) {
            return Optional.empty();
        }
        return Optional.of(Json.unescape(value.substring(1, value.length() - 1)));
    }

    public static String stringOrNull(String json, String name) {
        return stringOrNull(json, name, 0);
    }

    public static String stringOrNull(String json, String name, int startIndex) {
        return string(json, name, startIndex).orElse(null);
    }

    public static String stringOrDefault(String json, String name, String defaultValue) {
        String value = stringOrNull(json, name);
        return value == null || value.isBlank() ? defaultValue : value;
    }

    public static long longValue(String json, String name) {
        String value = field(json, name);
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a number");
        }
    }

    public static Long longOrNull(String json, String name) {
        return longOrNull(json, name, 0);
    }

    public static Long longOrNull(String json, String name, int startIndex) {
        String value = fieldOrNull(json, name, startIndex);
        if (value == null || "null".equals(value)) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static long longOrDefault(String json, String name, long defaultValue) {
        Long value = longOrNull(json, name);
        return value == null ? defaultValue : value;
    }

    public static double doubleOrDefault(String json, String name, double defaultValue) {
        String value = fieldOrNull(json, name);
        if (value == null || "null".equals(value)) {
            return defaultValue;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static Boolean booleanOrNull(String json, String name) {
        String value = fieldOrNull(json, name);
        if ("true".equals(value)) {
            return Boolean.TRUE;
        }
        if ("false".equals(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    public static boolean booleanOrDefault(String json, String name, boolean defaultValue) {
        Boolean value = booleanOrNull(json, name);
        return value == null ? defaultValue : value;
    }

    private static String field(String json, String name) {
        String value = fieldOrNull(json, name);
        if (value == null) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }

    private static String fieldOrNull(String json, String name) {
        return fieldOrNull(json, name, 0);
    }

    private static String fieldOrNull(String json, String name, int startIndex) {
        String key = Json.string(name);
        int keyStart = json.indexOf(key, Math.max(0, startIndex));
        if (keyStart < 0) {
            return null;
        }
        int colon = json.indexOf(':', keyStart + key.length());
        if (colon < 0) {
            return null;
        }
        int valueStart = nextNonWhitespace(json, colon + 1);
        if (valueStart < 0) {
            return null;
        }
        if (json.charAt(valueStart) == '"') {
            int valueEnd = valueStart + 1;
            boolean escaped = false;
            while (valueEnd < json.length()) {
                char c = json.charAt(valueEnd);
                if (c == '"' && !escaped) {
                    return json.substring(valueStart, valueEnd + 1);
                }
                escaped = c == '\\' && !escaped;
                if (c != '\\') {
                    escaped = false;
                }
                valueEnd++;
            }
            return null;
        }
        int valueEnd = valueStart;
        while (valueEnd < json.length()) {
            char c = json.charAt(valueEnd);
            if (c == ',' || c == '}') {
                break;
            }
            valueEnd++;
        }
        return json.substring(valueStart, valueEnd).trim();
    }

    private static boolean isStringValue(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"");
    }

    private static int nextNonWhitespace(String value, int startIndex) {
        for (int i = startIndex; i < value.length(); i++) {
            if (!Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
