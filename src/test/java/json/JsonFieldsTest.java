package json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class JsonFieldsTest {
    @Test
    void readsStringNumbersBooleansAndNulls() {
        String json = """
                {"text":"a\\nb","count":123,"ratio":1.5,"enabled":true,"missingValue":null}
                """;

        assertEquals("a\nb", JsonFields.string(json, "text"));
        assertEquals(123, JsonFields.longValue(json, "count"));
        assertEquals(123, JsonFields.longOrDefault(json, "count", 0));
        assertEquals(1.5, JsonFields.doubleOrDefault(json, "ratio", 0), 0.000001);
        assertTrue(JsonFields.booleanOrDefault(json, "enabled", false));
        assertNull(JsonFields.stringOrNull(json, "missingValue"));
        assertNull(JsonFields.longOrNull(json, "missingValue"));
    }

    @Test
    void returnsDefaultsForMissingOrInvalidValues() {
        String json = """
                {"blank":"","number":"abc","flag":"true"}
                """;

        assertEquals("fallback", JsonFields.stringOrDefault(json, "blank", "fallback"));
        assertEquals("fallback", JsonFields.stringOrDefault(json, "missing", "fallback"));
        assertEquals(7, JsonFields.longOrDefault(json, "missing", 7));
        assertEquals(2.5, JsonFields.doubleOrDefault(json, "number", 2.5), 0.000001);
        assertFalse(JsonFields.booleanOrDefault(json, "flag", false));
    }

    @Test
    void throwsWhenRequiredFieldIsMissingOrWrongType() {
        String json = """
                {"text":123,"number":"abc"}
                """;

        assertThrows(IllegalArgumentException.class, () -> JsonFields.string(json, "missing"));
        assertThrows(IllegalArgumentException.class, () -> JsonFields.string(json, "text"));
        assertThrows(IllegalArgumentException.class, () -> JsonFields.longValue(json, "missing"));
        assertThrows(IllegalArgumentException.class, () -> JsonFields.longValue(json, "number"));
    }

    @Test
    void readsEscapedStringValues() {
        String json = """
                {"text":"quote: \\" slash: \\\\ line: \\n unicode: \\u3042"}
                """;

        assertEquals("quote: \" slash: \\ line: \n unicode: あ", JsonFields.string(json, "text"));
    }

    @Test
    void readsFieldsAfterStartIndex() {
        String json = """
                {"audio":{"data":"first","sample_rate":16000},"audio":{"data":"second","sample_rate":24000}}
                """;
        int secondAudio = json.indexOf("\"audio\"", json.indexOf("\"audio\"") + 1);

        assertEquals("second", JsonFields.string(json, "data", secondAudio).orElseThrow());
        assertEquals(24_000, JsonFields.longOrNull(json, "sample_rate", secondAudio));
    }

    @Test
    void returnsEmptyOptionalForMissingOrNonStringFieldAfterStartIndex() {
        String json = """
                {"audio":{"data":123}}
                """;

        assertTrue(JsonFields.string(json, "data", 0).isEmpty());
        assertTrue(JsonFields.string(json, "missing", 0).isEmpty());
    }
}
