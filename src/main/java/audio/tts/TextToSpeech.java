package audio.tts;

import java.util.function.Consumer;

/**
 * テキストを音声へ変換する最小インターフェースです。
 */
public interface TextToSpeech {
    void synthesizeStreaming(String text, Consumer<AudioDelta> onDelta);

    static TextToSpeech disabled() {
        return (text, onDelta) -> {
        };
    }
}
