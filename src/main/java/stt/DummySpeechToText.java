package stt;

import audio.AudioBuffer;

public class DummySpeechToText implements SpeechToText {
    @Override
    public String transcribe(AudioBuffer audioBuffer, long startSampleIndex, long endSampleIndexExclusive) {
        return "dummy stt result";
    }

    @Override
    public Transcription transcribeWithSegments(AudioBuffer audioBuffer, long startSampleIndex, long endSampleIndexExclusive) {
        return Transcription.singleSegment(
                transcribe(audioBuffer, startSampleIndex, endSampleIndexExclusive),
                endSampleIndexExclusive - startSampleIndex,
                16_000);
    }
}
