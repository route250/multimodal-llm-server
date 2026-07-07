package audio.feature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LogMelFeatureExtractorTest {
    @Test
    void extractsWhisperInputFeatureShape() {
        LogMelFeatureExtractor extractor = new LogMelFeatureExtractor();

        float[][] features = extractor.extract(new float[LogMelFeatureExtractor.SAMPLE_RATE]);

        assertEquals(LogMelFeatureExtractor.FEATURE_SIZE, features.length);
        assertEquals(LogMelFeatureExtractor.FRAME_COUNT, features[0].length);
    }

    @Test
    void extractsFiniteValuesForSpeechLengthAudio() {
        LogMelFeatureExtractor extractor = new LogMelFeatureExtractor();
        float[] samples = new float[LogMelFeatureExtractor.CHUNK_SAMPLES + 1000];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) (0.2 * Math.sin(2.0 * Math.PI * 440.0 * i / LogMelFeatureExtractor.SAMPLE_RATE));
        }

        float[][] features = extractor.extract(samples);

        for (float[] mel : features) {
            for (float value : mel) {
                assertTrue(Float.isFinite(value));
            }
        }
    }
}
