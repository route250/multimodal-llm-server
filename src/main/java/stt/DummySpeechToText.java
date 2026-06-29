package stt;

import audio.AudioBuffer;

public class DummySpeechToText implements SpeechToText {
    @Override
    public String transcribe(AudioBuffer audioBuffer, long startSampleIndex, long endSampleIndexExclusive) {
        return "dummy stt result";
    }
}
