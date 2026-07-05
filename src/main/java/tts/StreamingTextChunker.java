package tts;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM の逐次テキストを、TTS に渡せる単位へ分割します。
 */
public class StreamingTextChunker {
    private static final int DEFAULT_MAX_CHARS = 80;
    private static final int MIN_CHARS_BEFORE_SEPARATOR_SPLIT = 3;

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
            if (isSeparator(c)) {
                addPending(chunks, false);
            } else if (pending.length() >= maxChars) {
                addPending(chunks, true);
            }
        }
        return chunks;
    }

    public List<String> finish() {
        List<String> chunks = new ArrayList<>();
        addPending(chunks, true);
        return chunks;
    }

    private void addPending(List<String> chunks, boolean force) {
        String chunk = pending.toString().trim();
        if (chunk.isBlank()) {
            pending.setLength(0);
            return;
        }
        if (!force && isMarkdownSymbolsOnly(chunk)) {
            return;
        }
        if (!force && speechCharCount(chunk) < MIN_CHARS_BEFORE_SEPARATOR_SPLIT) {
            return;
        }
        pending.setLength(0);
        chunks.add(chunk);
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

    public static boolean hasSpeechText(String chunk) {
        return chunk != null && !chunk.isBlank() && !isMarkdownSymbolsOnly(chunk);
    }

    private static int speechCharCount(String chunk) {
        int count = 0;
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            if (!Character.isWhitespace(c) && !isSeparator(c)) {
                count++;
            }
        }
        return count;
    }

    private static boolean isMarkdownSymbolsOnly(String chunk) {
        for (int i = 0; i < chunk.length(); i++) {
            char c = chunk.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                return false;
            }
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HIRAGANA
                    || Character.UnicodeScript.of(c) == Character.UnicodeScript.KATAKANA
                    || Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                return false;
            }
        }
        return true;
    }
}
