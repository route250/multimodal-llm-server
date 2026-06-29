package audio;

import java.util.Arrays;

public class AudioBuffer {
    private final short[] pcm;
    private final float[] vadValues;
    private final int samplesPerVadValue;
    private long startSampleIndex;
    private long endSampleIndexExclusive;
    private long vadStartIndex;
    private long vadEndIndexExclusive;

    public AudioBuffer(int capacitySamples, int samplesPerVadValue) {
        if (capacitySamples <= 0) {
            throw new IllegalArgumentException("capacitySamples must be positive");
        }
        if (samplesPerVadValue <= 0) {
            throw new IllegalArgumentException("samplesPerVadValue must be positive");
        }
        this.pcm = new short[capacitySamples];
        this.samplesPerVadValue = samplesPerVadValue;
        this.vadValues = new float[(capacitySamples + samplesPerVadValue - 1) / samplesPerVadValue];
        Arrays.fill(vadValues, Float.NaN);
    }

    public int capacitySamples() {
        return pcm.length;
    }

    public long startSampleIndex() {
        return startSampleIndex;
    }

    public long endSampleIndexExclusive() {
        return endSampleIndexExclusive;
    }

    public int lengthSamples() {
        return Math.toIntExact(endSampleIndexExclusive - startSampleIndex);
    }

    public boolean contains(long startInclusive, long endExclusive) {
        return startInclusive >= startSampleIndex && endExclusive <= endSampleIndexExclusive;
    }

    public void append(short[] samples, long sourceStartSampleIndex) {
        append(samples, 0, samples.length, sourceStartSampleIndex);
    }

    public void append(short[] samples, int offset, int length, long sourceStartSampleIndex) {
        if (length <= 0) {
            return;
        }
        if (offset < 0 || length < 0 || offset + length > samples.length) {
            throw new IndexOutOfBoundsException("invalid sample range");
        }

        long sourceEndSampleIndex = sourceStartSampleIndex + length;
        long writeStartSampleIndex = Math.max(sourceStartSampleIndex, endSampleIndexExclusive);
        if (sourceEndSampleIndex <= writeStartSampleIndex) {
            return;
        }

        if (writeStartSampleIndex > endSampleIndexExclusive) {
            endSampleIndexExclusive = writeStartSampleIndex;
            trimToCapacity();
        }

        int writeOffset = offset + Math.toIntExact(writeStartSampleIndex - sourceStartSampleIndex);
        int writeLength = Math.toIntExact(sourceEndSampleIndex - writeStartSampleIndex);
        for (int i = 0; i < writeLength; i++) {
            pcm[physicalIndex(writeStartSampleIndex + i)] = samples[writeOffset + i];
        }
        endSampleIndexExclusive = sourceEndSampleIndex;
        trimToCapacity();
    }

    public void appendRangeFrom(AudioBuffer source, long startInclusive, long endExclusive) {
        if (endExclusive <= startInclusive) {
            return;
        }
        if (!source.contains(startInclusive, endExclusive)) {
            throw new IllegalArgumentException("source range is no longer available");
        }
        long writeStart = Math.max(startInclusive, endSampleIndexExclusive);
        if (endExclusive <= writeStart) {
            return;
        }
        if (writeStart > endSampleIndexExclusive) {
            endSampleIndexExclusive = writeStart;
            trimToCapacity();
        }
        for (long sampleIndex = writeStart; sampleIndex < endExclusive; sampleIndex++) {
            pcm[physicalIndex(sampleIndex)] = source.sampleAt(sampleIndex);
        }
        endSampleIndexExclusive = endExclusive;
        trimToCapacity();
    }

    public short sampleAt(long sampleIndex) {
        if (sampleIndex < startSampleIndex || sampleIndex >= endSampleIndexExclusive) {
            throw new IndexOutOfBoundsException("sample index is outside buffer range");
        }
        return pcm[physicalIndex(sampleIndex)];
    }

    public float[] floats(long startInclusive, int length) {
        long endExclusive = startInclusive + length;
        if (!contains(startInclusive, endExclusive)) {
            throw new IllegalArgumentException("requested range is outside buffer range");
        }
        float[] values = new float[length];
        for (int i = 0; i < length; i++) {
            values[i] = sampleAt(startInclusive + i) / 32768.0f;
        }
        return values;
    }

    public void putVadValue(long sampleIndex, float value) {
        long vadIndex = sampleIndex / samplesPerVadValue;
        vadValues[Math.floorMod(vadIndex, vadValues.length)] = value;
        if (vadEndIndexExclusive == 0 && vadStartIndex == 0) {
            vadStartIndex = vadIndex;
        }
        vadEndIndexExclusive = Math.max(vadEndIndexExclusive, vadIndex + 1);
        trimVadToPcmRange();
    }

    public float vadValue(long sampleIndex) {
        long vadIndex = sampleIndex / samplesPerVadValue;
        if (vadIndex < vadStartIndex || vadIndex >= vadEndIndexExclusive) {
            return Float.NaN;
        }
        return vadValues[Math.floorMod(vadIndex, vadValues.length)];
    }

    private int physicalIndex(long sampleIndex) {
        return Math.floorMod(sampleIndex, pcm.length);
    }

    private void trimToCapacity() {
        long minimumStart = Math.max(0, endSampleIndexExclusive - pcm.length);
        if (minimumStart > startSampleIndex) {
            startSampleIndex = minimumStart;
            trimVadToPcmRange();
        }
    }

    private void trimVadToPcmRange() {
        long minimumVadIndex = startSampleIndex / samplesPerVadValue;
        if (minimumVadIndex > vadStartIndex) {
            for (long vadIndex = vadStartIndex; vadIndex < minimumVadIndex && vadIndex < vadEndIndexExclusive; vadIndex++) {
                vadValues[Math.floorMod(vadIndex, vadValues.length)] = Float.NaN;
            }
            vadStartIndex = Math.min(minimumVadIndex, vadEndIndexExclusive);
        }
    }
}
