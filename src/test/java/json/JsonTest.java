package json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void escapesJsonStringCharacters() {
        assertEquals("\\\\\\\"\\n\\r\\t\\b\\f\\u0001", Json.escape("\\\"\n\r\t\b\f\u0001"));
    }

    @Test
    void unescapesJsonStringCharacters() {
        assertEquals("\\\"\n\r\t\b\f/A", Json.unescape("\\\\\\\"\\n\\r\\t\\b\\f\\/\\u0041"));
    }

    @Test
    void keepsInvalidUnicodeEscapeAsText() {
        assertEquals("before\\u12X4after", Json.unescape("before\\u12X4after"));
        assertEquals("before\\u12", Json.unescape("before\\u12"));
    }

    @Test
    void wrapsEscapedStringWithQuotes() {
        assertEquals("\"a\\\"b\"", Json.string("a\"b"));
    }

    @Test
    void writesObjectValuesInInsertionOrder() {
        assertEquals(
                "{\"text\":\"a\\nb\",\"count\":3,\"enabled\":true,\"empty\":null,\"raw\":[1,2]}",
                Json.object(Json.fields(
                        "text", "a\nb",
                        "count", 3,
                        "enabled", true,
                        "empty", null,
                        "raw", Json.raw("[1,2]"))));
    }

    @Test
    void rejectsOddFieldPairs() {
        assertThrows(IllegalArgumentException.class, () -> Json.fields("key"));
    }
}
