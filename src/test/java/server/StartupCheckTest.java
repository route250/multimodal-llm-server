package server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import audio.stt.Transcription;
import audio.tts.AudioDelta;
import java.util.List;
import java.util.function.Consumer;
import llm.LLM;
import llm.Message;
import llm.LanguageModelException;
import org.junit.jupiter.api.Test;

class StartupCheckTest {
    @Test
    void succeedsWhenAllServicesReturnRequiredResponses() {
        StartupCheck check = new StartupCheck(
                (buffer, start, end, prompt) -> Transcription.empty(),
                (text, onDelta) -> onDelta.accept(new AudioDelta("AA==", "pcm", 24_000)),
                new StubLlm("応答"),
                samples -> true);

        assertDoesNotThrow(check::verify);
    }

    @Test
    void failsWhenTtsReturnsNoAudio() {
        StartupCheck check = new StartupCheck(
                (buffer, start, end, prompt) -> Transcription.empty(),
                (text, onDelta) -> { },
                new StubLlm("応答"),
                samples -> true);

        assertThrows(IllegalStateException.class, check::verify);
    }

    @Test
    void failsWhenLlmReturnsBlankText() {
        StartupCheck check = new StartupCheck(
                (buffer, start, end, prompt) -> Transcription.empty(),
                (text, onDelta) -> onDelta.accept(new AudioDelta("AA==", "pcm", 24_000)),
                new StubLlm(" "),
                samples -> true);

        assertThrows(LanguageModelException.class, check::verify);
    }

    @Test
    void propagatesSttFailure() {
        StartupCheck check = new StartupCheck(
                (buffer, start, end, prompt) -> { throw new RuntimeException("STT failed"); },
                (text, onDelta) -> onDelta.accept(new AudioDelta("AA==", "pcm", 24_000)),
                new StubLlm("応答"),
                samples -> true);

        assertThrows(RuntimeException.class, check::verify);
    }

    @Test
    void propagatesSmartTurnFailure() {
        StartupCheck check = new StartupCheck(
                (buffer, start, end, prompt) -> Transcription.empty(),
                (text, onDelta) -> onDelta.accept(new AudioDelta("AA==", "pcm", 24_000)),
                new StubLlm("応答"),
                samples -> { throw new RuntimeException("SmartTurn failed"); });

        assertThrows(RuntimeException.class, check::verify);
    }

    /** 外部サービスへ接続せず、LLM の共通検証処理を実行するテスト用実装です。 */
    private static final class StubLlm extends LLM {
        private final String response;

        private StubLlm(String response) {
            super("http://localhost", "", "test-model", false);
            this.response = response;
        }

        @Override
        public List<String> models() {
            return List.of("test-model");
        }

        @Override
        public String model() {
            return "test-model";
        }

        @Override
        public List<Message> call(List<Message> messages, List<Tool> tools, Consumer<String> callback) {
            if (callback != null) {
                callback.accept(response);
            }
            return List.of(new Message("assistant", response));
        }
    }
}
