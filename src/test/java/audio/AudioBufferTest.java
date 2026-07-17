package audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AudioBufferTest {
    @Test
    void storesRmsValueByFrame() {
        AudioBuffer buffer = new AudioBuffer(1024, 256);
        buffer.append(
                new short[512],
                0,
                new float[] {0.0f, 0.0f},
                new float[] {0.0f, 0.42f});

        assertEquals(0.0f, buffer.rmsValue(0));
        assertEquals(0.42f, buffer.rmsValue(256));
    }

    @Test
    void appendWithOffsetStoresPcmVadAndRmsValues() {
        AudioBuffer buffer = new AudioBuffer(1024, 256);
        short[] samples = new short[1024];
        samples[256] = 123;
        samples[767] = 456;

        buffer.append(
                samples,
                new float[] {0.25f, 0.75f},
                new float[] {0.10f, 0.20f},
                256,
                512,
                0);

        assertEquals(123, buffer.sampleAt(0));
        assertEquals(456, buffer.sampleAt(511));
        assertEquals(0.25f, buffer.vadValue(0));
        assertEquals(0.75f, buffer.vadValue(256));
        assertEquals(0.10f, buffer.rmsValue(0));
        assertEquals(0.20f, buffer.rmsValue(256));
    }

    @Test
    void appendRangeFromCopiesRmsValues() {
        AudioBuffer source = new AudioBuffer(1024, 256);
        source.append(
                new short[512],
                0,
                new float[] {0.75f, 0.0f},
                new float[] {0.25f, 0.0f});

        AudioBuffer target = new AudioBuffer(1024, 256);
        target.appendRangeFrom(source, 0, 512);

        assertEquals(0.75f, target.vadValue(0));
        assertEquals(0.25f, target.rmsValue(0));
    }
}
