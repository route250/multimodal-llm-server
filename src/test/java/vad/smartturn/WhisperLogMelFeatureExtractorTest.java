package vad.smartturn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WhisperLogMelFeatureExtractorTest {
    @Test
    void extractsWhisperInputFeatureShape() {
        WhisperLogMelFeatureExtractor extractor = new WhisperLogMelFeatureExtractor();

        float[][] features = extractor.extract(new float[WhisperLogMelFeatureExtractor.SAMPLE_RATE]);

        assertEquals(WhisperLogMelFeatureExtractor.FEATURE_SIZE, features.length);
        assertEquals(WhisperLogMelFeatureExtractor.FRAME_COUNT, features[0].length);
    }

    @Test
    void extractsFiniteValuesForSpeechLengthAudio() {
        WhisperLogMelFeatureExtractor extractor = new WhisperLogMelFeatureExtractor();
        float[] samples = new float[WhisperLogMelFeatureExtractor.CHUNK_SAMPLES + 1000];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (float) (0.2 * Math.sin(2.0 * Math.PI * 440.0 * i / WhisperLogMelFeatureExtractor.SAMPLE_RATE));
        }

        float[][] features = extractor.extract(samples);

        for (float[] mel : features) {
            for (float value : mel) {
                assertTrue(Float.isFinite(value));
            }
        }
    }
}
