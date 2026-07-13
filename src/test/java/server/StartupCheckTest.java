package server;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import audio.stt.Transcription;
import audio.tts.AudioDelta;
import org.junit.jupiter.api.Test;

class StartupCheckTest {
    @Test
    void succeedsWhenAllServicesReturnRequiredResponses() {
        StartupCheck check = new StartupCheck(
                (buffer, start, end, prompt) -> Transcription.empty(),
                (text, onDelta) -> onDelta.accept(new AudioDelta("AA==", "pcm", 24_000)),
                text -> "応答",
                samples -> true);

        assertDoesNotThrow(check::verify);
    }

    @Test
    void failsWhenTtsReturnsNoAudio() {
        StartupCheck check = new StartupCheck(
                (buffer, start, end, prompt) -> Transcription.empty(),
                (text, onDelta) -> { },
                text -> "応答",
                samples -> true);

        assertThrows(IllegalStateException.class, check::verify);
    }

    @Test
    void failsWhenLlmReturnsBlankText() {
        StartupCheck check = new StartupCheck(
                (buffer, start, end, prompt) -> Transcription.empty(),
                (text, onDelta) -> onDelta.accept(new AudioDelta("AA==", "pcm", 24_000)),
                text -> " ",
                samples -> true);

        assertThrows(IllegalStateException.class, check::verify);
    }

    @Test
    void propagatesSttFailure() {
        StartupCheck check = new StartupCheck(
                (buffer, start, end, prompt) -> { throw new RuntimeException("STT failed"); },
                (text, onDelta) -> onDelta.accept(new AudioDelta("AA==", "pcm", 24_000)),
                text -> "応答",
                samples -> true);

        assertThrows(RuntimeException.class, check::verify);
    }

    @Test
    void propagatesSmartTurnFailure() {
        StartupCheck check = new StartupCheck(
                (buffer, start, end, prompt) -> Transcription.empty(),
                (text, onDelta) -> onDelta.accept(new AudioDelta("AA==", "pcm", 24_000)),
                text -> "応答",
                samples -> { throw new RuntimeException("SmartTurn failed"); });

        assertThrows(RuntimeException.class, check::verify);
    }
}
