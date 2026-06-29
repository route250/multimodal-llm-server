package stt;

import audio.AudioBuffer;

public interface SpeechToText {
    String transcribe(AudioBuffer audioBuffer, long startSampleIndex, long endSampleIndexExclusive);
}
