package tts;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class StreamingTextChunkerTest {
    @Test
    void splitsByPunctuationAndWhitespace() {
        StreamingTextChunker chunker = new StreamingTextChunker(80);

        assertEquals(List.of("こんにちは。"), chunker.append("こんにちは。次"));
        assertEquals(List.of("次です"), chunker.append("です "));
        assertEquals(List.of(), chunker.append("終わり"));
        assertEquals(List.of("終わり"), chunker.finish());
    }

    @Test
    void splitsByMaxChars() {
        StreamingTextChunker chunker = new StreamingTextChunker(4);

        assertEquals(List.of("abcd"), chunker.append("abcde"));
        assertEquals(List.of("e"), chunker.finish());
    }
}
