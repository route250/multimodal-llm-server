package audio.stt;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class WhisperServerSpeechToTextTest {
    @Test
    void parsesWebVttSegments() {
        String webVtt = """
                WEBVTT

                00:00:03.000 --> 00:00:05.250
                はい、すいません。
                はい、どちらまで?

                00:00:05.250 --> 00:00:07.000
                あの、何番グランドかけつまで。
                """;

        Transcription transcription = WhisperServerSpeechToText.parseWebVtt(webVtt);

        assertEquals("はい、すいません。\nはい、どちらまで?\nあの、何番グランドかけつまで。", transcription.text());
        assertEquals(2, transcription.segments().size());
        assertEquals(Duration.ofSeconds(3), transcription.segments().get(0).start());
        assertEquals(Duration.ofMillis(5_250), transcription.segments().get(0).end());
        assertEquals("はい、すいません。\nはい、どちらまで?", transcription.segments().get(0).text());
        assertEquals(Duration.ofMillis(5_250), transcription.segments().get(1).start());
        assertEquals(Duration.ofSeconds(7), transcription.segments().get(1).end());
        assertEquals("あの、何番グランドかけつまで。", transcription.segments().get(1).text());
    }

}
