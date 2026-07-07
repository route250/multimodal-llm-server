package audio.stt;

import java.time.Duration;
import java.util.Objects;

/**
 * Whisper の WEBVTT cue から取り出した 1 セグメント分の文字起こし。
 *
 * @param start セグメント開始時刻
 * @param end セグメント終了時刻
 * @param text セグメント内の文字列
 */
public record TranscriptSegment(Duration start, Duration end, String text) {
    public TranscriptSegment {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
        Objects.requireNonNull(text, "text");
        if (end.compareTo(start) < 0) {
            throw new IllegalArgumentException("segment end must be greater than or equal to start");
        }
    }
}
