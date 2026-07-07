package audio.stt;

import audio.AudioBuffer;

public interface SpeechToText {

    Transcription transcribe(
            AudioBuffer audioBuffer,
            long startSampleIndex,
            long endSampleIndexExclusive,
            String prompt);

}
