package stt;

import audio.AudioBuffer;

public interface SpeechToText {

    String transcribe(AudioBuffer audioBuffer, long startSampleIndex, long endSampleIndexExclusive);

    default String transcribe(
            AudioBuffer audioBuffer,
            long startSampleIndex,
            long endSampleIndexExclusive,
            String prompt) {
        return transcribe(audioBuffer, startSampleIndex, endSampleIndexExclusive);
    }

    Transcription transcribeWithSegments(AudioBuffer audioBuffer, long startSampleIndex, long endSampleIndexExclusive);

    default Transcription transcribeWithSegments(
            AudioBuffer audioBuffer,
            long startSampleIndex,
            long endSampleIndexExclusive,
            String prompt) {
        return transcribeWithSegments(audioBuffer, startSampleIndex, endSampleIndexExclusive);
    }

}
