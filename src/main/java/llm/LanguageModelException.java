package llm;

/**
 * LLM 呼び出しに失敗したことを表す例外です。
 */
public class LanguageModelException extends RuntimeException {
    public LanguageModelException(String message) {
        super(message);
    }

    public LanguageModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
