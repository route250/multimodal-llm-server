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
                new byte[] {0, 0},
                new byte[] {0, 42});

        assertEquals(0, buffer.rmsValue(0));
        assertEquals(42, buffer.rmsValue(256));
    }

    @Test
    void appendWithOffsetStoresPcmVadAndRmsValues() {
        AudioBuffer buffer = new AudioBuffer(1024, 256);
        short[] samples = new short[1024];
        samples[256] = 123;
        samples[767] = 456;

        buffer.append(
                samples,
                new byte[] {25, 75},
                new byte[] {10, 20},
                256,
                512,
                0);

        assertEquals(123, buffer.sampleAt(0));
        assertEquals(456, buffer.sampleAt(511));
        assertEquals(25, buffer.vadValue(0));
        assertEquals(75, buffer.vadValue(256));
        assertEquals(10, buffer.rmsValue(0));
        assertEquals(20, buffer.rmsValue(256));
    }

    @Test
    void appendRangeFromCopiesRmsValues() {
        AudioBuffer source = new AudioBuffer(1024, 256);
        source.append(
                new short[512],
                0,
                new byte[] {75, 0},
                new byte[] {25, 0});

        AudioBuffer target = new AudioBuffer(1024, 256);
        target.appendRangeFrom(source, 0, 512);

        assertEquals(75, target.vadValue(0));
        assertEquals(25, target.rmsValue(0));
    }
}
