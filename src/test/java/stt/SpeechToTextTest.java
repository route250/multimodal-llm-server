package stt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import audio.AudioBuffer;
import java.lang.reflect.Method;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SpeechToTextTest {
    @Test
    void segmentTranscriptionCanWrapTextWithInputDuration() {
        SpeechToText speechToText = new SpeechToText() {
            @Override
            public String transcribe(AudioBuffer audioBuffer, long startSampleIndex, long endSampleIndexExclusive) {
                return "hello";
            }

            @Override
            public Transcription transcribeWithSegments(
                    AudioBuffer audioBuffer,
                    long startSampleIndex,
                    long endSampleIndexExclusive) {
                return Transcription.singleSegment(
                        transcribe(audioBuffer, startSampleIndex, endSampleIndexExclusive),
                        endSampleIndexExclusive - startSampleIndex,
                        16_000);
            }
        };
        AudioBuffer audioBuffer = new AudioBuffer(16_000, 512);

        Transcription transcription = speechToText.transcribeWithSegments(
                audioBuffer,
                1_000,
                1_000 + 16_000);

        assertEquals("hello", transcription.text());
        assertEquals(1, transcription.segments().size());
        assertEquals(Duration.ZERO, transcription.segments().getFirst().start());
        assertEquals(Duration.ofSeconds(1), transcription.segments().getFirst().end());
        assertEquals("hello", transcription.segments().getFirst().text());
    }

    @Test
    void exposesSegmentTranscriptionMethod() throws Exception {
        Method method = SpeechToText.class.getMethod(
                "transcribeWithSegments",
                AudioBuffer.class,
                long.class,
                long.class);

        assertEquals(Transcription.class, method.getReturnType());
    }
}
