package audio.tts;

import java.util.Objects;

/**
 * TTSサーバから届く音声差分です。
 *
 * @param data base64 エンコード済みの音声データ
 * @param format 音声フォーマット
 * @param sampleRate サンプルレート
 */
public record AudioDelta(String data, String format, int sampleRate) {
    public AudioDelta {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(format, "format");
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
    }
}
