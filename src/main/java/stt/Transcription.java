package stt;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Whisper の文字起こし結果。全体テキストと WEBVTT のセグメント時刻を保持する。
 *
 * @param text セグメント文字列を改行で結合した全体テキスト
 * @param segments セグメント単位の開始時刻、終了時刻、文字列
 */
public record Transcription(String text, List<TranscriptSegment> segments) {
    public Transcription {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(segments, "segments");
        segments = List.copyOf(segments);
    }

    public static Transcription empty() {
        return new Transcription("", List.of());
    }

    public static Transcription singleSegment(String text, Duration end) {
        if (text == null || text.isBlank()) {
            return empty();
        }
        return new Transcription(text, List.of(new TranscriptSegment(Duration.ZERO, end, text)));
    }

    public static Transcription singleSegment(String text, long sampleLength, int sampleRate) {
        long samples = Math.max(0, sampleLength);
        Duration end = Duration.ofNanos(samples * 1_000_000_000L / sampleRate);
        return singleSegment(text, end);
    }
}
