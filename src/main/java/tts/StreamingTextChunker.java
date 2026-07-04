package tts;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM の逐次テキストを、TTS に渡せる単位へ分割します。
 */
public class StreamingTextChunker {
    private static final int DEFAULT_MAX_CHARS = 80;

    private final StringBuilder pending = new StringBuilder();
    private final int maxChars;

    public StreamingTextChunker() {
        this(DEFAULT_MAX_CHARS);
    }

    StreamingTextChunker(int maxChars) {
        if (maxChars <= 0) {
            throw new IllegalArgumentException("maxChars must be positive");
        }
        this.maxChars = maxChars;
    }

    public List<String> append(String delta) {
        List<String> chunks = new ArrayList<>();
        if (delta == null || delta.isEmpty()) {
            return chunks;
        }

        for (int i = 0; i < delta.length(); i++) {
            char c = delta.charAt(i);
            pending.append(c);
            if (isSeparator(c) || pending.length() >= maxChars) {
                addPending(chunks);
            }
        }
        return chunks;
    }

    public List<String> finish() {
        List<String> chunks = new ArrayList<>();
        addPending(chunks);
        return chunks;
    }

    private void addPending(List<String> chunks) {
        String chunk = pending.toString().trim();
        pending.setLength(0);
        if (!chunk.isBlank()) {
            chunks.add(chunk);
        }
    }

    private static boolean isSeparator(char c) {
        return Character.isWhitespace(c)
                || c == '。'
                || c == '、'
                || c == '，'
                || c == ','
                || c == '.'
                || c == '！'
                || c == '!'
                || c == '？'
                || c == '?'
                || c == '；'
                || c == ';'
                || c == '：'
                || c == ':';
    }
}
