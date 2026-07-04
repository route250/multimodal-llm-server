package tts;

/**
 * TTS 呼び出しに失敗したことを表す例外です。
 */
public class TextToSpeechException extends RuntimeException {
    public TextToSpeechException(String message) {
        super(message);
    }

    public TextToSpeechException(String message, Throwable cause) {
        super(message, cause);
    }
}
