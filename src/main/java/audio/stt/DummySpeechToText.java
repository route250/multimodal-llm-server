package audio.stt;

import audio.AudioBuffer;

public class DummySpeechToText implements SpeechToText {
    @Override
    public Transcription transcribe(
            AudioBuffer audioBuffer,
            long startSampleIndex,
            long endSampleIndexExclusive,
            String prompt) {
        return Transcription.singleSegment(
                "dummy stt result",
                endSampleIndexExclusive - startSampleIndex,
                16_000);
    }
}
