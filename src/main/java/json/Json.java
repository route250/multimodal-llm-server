package json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JDK 標準機能だけで使う、このプロジェクト用の小さな JSON 生成ユーティリティです。
 */
public final class Json {
    private Json() {
    }

    public static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                default -> {
                    if (c < 0x20) {
                        escaped.append("\\u%04x".formatted((int) c));
                    } else {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }

    public static String unescape(String value) {
        StringBuilder unescaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != '\\' || i + 1 >= value.length()) {
                unescaped.append(c);
                continue;
            }
            char escaped = value.charAt(++i);
            switch (escaped) {
                case '\\' -> unescaped.append('\\');
                case '"' -> unescaped.append('"');
                case '/' -> unescaped.append('/');
                case 'b' -> unescaped.append('\b');
                case 'f' -> unescaped.append('\f');
                case 'n' -> unescaped.append('\n');
                case 'r' -> unescaped.append('\r');
                case 't' -> unescaped.append('\t');
                case 'u' -> {
                    if (i + 4 >= value.length()) {
                        unescaped.append("\\u");
                        continue;
                    }
                    String hex = value.substring(i + 1, i + 5);
                    try {
                        unescaped.append((char) Integer.parseInt(hex, 16));
                        i += 4;
                    } catch (NumberFormatException e) {
                        unescaped.append("\\u").append(hex);
                        i += 4;
                    }
                }
                default -> unescaped.append(escaped);
            }
        }
        return unescaped.toString();
    }

    public static String string(String value) {
        return "\"" + escape(value) + "\"";
    }

    public static Object raw(String value) {
        return new Raw(value);
    }

    public static Map<String, Object> fields(Object... pairs) {
        if (pairs.length % 2 != 0) {
            throw new IllegalArgumentException("field pairs must be even");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            fields.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return fields;
    }

    public static String object(Map<String, ?> fields) {
        StringBuilder json = new StringBuilder();
        json.append('{');
        boolean first = true;
        for (Map.Entry<String, ?> entry : fields.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append(string(entry.getKey())).append(':');
            appendValue(json, entry.getValue());
        }
        json.append('}');
        return json.toString();
    }

    private static void appendValue(StringBuilder json, Object value) {
        if (value == null) {
            json.append("null");
        } else if (value instanceof Raw raw) {
            json.append(raw.value());
        } else if (value instanceof Number || value instanceof Boolean) {
            json.append(value);
        } else {
            json.append(string(String.valueOf(value)));
        }
    }

    private record Raw(String value) {
    }
}
