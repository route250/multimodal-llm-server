package audio;

import java.util.Arrays;

public class AudioBuffer {
    /** PCM サンプルを保持する循環バッファ。要素数はサンプル数単位の容量。 */
    private final short[] pcm;
    /** VAD 値を保持する循環バッファ。1 要素は samplesPerVadValue 個の PCM サンプルに対応する。 */
    private final float[] vadValues;
    /** VAD 値 1 個に対応する PCM サンプル数。 */
    private final int samplesPerVadValue;
    /** 現在保持している PCM 範囲の先頭サンプル番号。 */
    private long startSampleIndex;
    /** 現在保持している PCM 範囲の終端サンプル番号。この番号のサンプルは含まない。 */
    private long endSampleIndexExclusive;
    /** 現在保持している VAD 範囲の先頭 VAD 番号。 */
    private long vadStartIndex;
    /** 現在保持している VAD 範囲の終端 VAD 番号。この番号の VAD 値は含まない。 */
    private long vadEndIndexExclusive;

    /**
     * 指定した PCM 容量と VAD 解像度でバッファを作成する。
     *
     * @param capacitySamples 保持できる PCM サンプル数
     * @param samplesPerVadValue VAD 値 1 個に対応する PCM サンプル数
     */
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

    /**
     * PCM サンプル数単位の容量を返す。
     *
     * @return 保持できる PCM サンプル数
     */
    public int capacitySamples() {
        return pcm.length;
    }

    /**
     * 現在保持している PCM 範囲の先頭サンプル番号を返す。
     *
     * @return 先頭サンプル番号
     */
    public long startSampleIndex() {
        return startSampleIndex;
    }

    /**
     * 現在保持している PCM 範囲の終端サンプル番号を返す。
     *
     * @return 終端サンプル番号。この番号のサンプルは含まない
     */
    public long endSampleIndexExclusive() {
        return endSampleIndexExclusive;
    }

    /**
     * 現在保持している PCM サンプル数を返す。
     *
     * @return 保持中の PCM サンプル数
     */
    public int lengthSamples() {
        return Math.toIntExact(endSampleIndexExclusive - startSampleIndex);
    }

    /**
     * 指定範囲の PCM サンプルが現在のバッファ内に全て存在するかを返す。
     *
     * @param startInclusive 確認する範囲の先頭サンプル番号
     * @param endExclusive 確認する範囲の終端サンプル番号。この番号のサンプルは含まない
     * @return 指定範囲を全て保持している場合は true
     */
    public boolean contains(long startInclusive, long endExclusive) {
        return startInclusive >= startSampleIndex && endExclusive <= endSampleIndexExclusive;
    }

    /**
     * 配列全体の PCM サンプルを追加する。
     *
     * @param samples 追加する PCM サンプル配列
     * @param sourceStartSampleIndex samples[0] に対応するソース上のサンプル番号
     */
    public void append(short[] samples, long sourceStartSampleIndex) {
        append(samples, 0, samples.length, sourceStartSampleIndex);
    }

    /**
     * 配列内の指定範囲の PCM サンプルを追加する。
     *
     * @param samples 追加元の PCM サンプル配列
     * @param offset samples 内で追加を開始する配列インデックス
     * @param length 追加対象の PCM サンプル数
     * @param sourceStartSampleIndex samples[offset] に対応するソース上のサンプル番号
     */
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

    /**
     * 別の AudioBuffer から指定範囲の PCM サンプルを追加する。
     *
     * @param source 追加元の AudioBuffer
     * @param startInclusive コピーする範囲の先頭サンプル番号
     * @param endExclusive コピーする範囲の終端サンプル番号。この番号のサンプルは含まない
     */
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
        long vadCopyStart = Math.floorDiv(writeStart, samplesPerVadValue) * samplesPerVadValue;
        for (long sampleIndex = vadCopyStart; sampleIndex < endExclusive; sampleIndex += samplesPerVadValue) {
            float value = source.vadValue(sampleIndex);
            if (!Float.isNaN(value)) {
                putVadValue(sampleIndex, value);
            }
        }
        trimToCapacity();
    }

    /**
     * 指定サンプル番号の PCM 値を返す。
     *
     * @param sampleIndex 取得する PCM サンプル番号
     * @return 指定サンプル番号の PCM 値
     */
    public short sampleAt(long sampleIndex) {
        if (sampleIndex < startSampleIndex || sampleIndex >= endSampleIndexExclusive) {
            throw new IndexOutOfBoundsException("sample index is outside buffer range");
        }
        return pcm[physicalIndex(sampleIndex)];
    }

    /**
     * 指定範囲の PCM サンプルを -1.0 以上 1.0 未満の float 値へ変換して返す。
     *
     * @param startInclusive 変換する範囲の先頭サンプル番号
     * @param length 変換する PCM サンプル数
     * @return 正規化した float 配列
     */
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

    /**
     * 指定サンプル番号に対応する VAD 値を保存する。
     *
     * @param sampleIndex VAD 値に対応する PCM サンプル番号
     * @param value 保存する VAD 値
     */
    public void putVadValue(long sampleIndex, float value) {
        long vadIndex = sampleIndex / samplesPerVadValue;
        vadValues[Math.floorMod(vadIndex, vadValues.length)] = value;
        if (vadEndIndexExclusive == 0 && vadStartIndex == 0) {
            vadStartIndex = vadIndex;
        }
        vadEndIndexExclusive = Math.max(vadEndIndexExclusive, vadIndex + 1);
        trimVadToPcmRange();
    }

    /**
     * 指定サンプル番号に対応する VAD 値を返す。
     *
     * @param sampleIndex 取得する VAD 値に対応する PCM サンプル番号
     * @return VAD 値。範囲外の場合は Float.NaN
     */
    public float vadValue(long sampleIndex) {
        long vadIndex = sampleIndex / samplesPerVadValue;
        if (vadIndex < vadStartIndex || vadIndex >= vadEndIndexExclusive) {
            return Float.NaN;
        }
        return vadValues[Math.floorMod(vadIndex, vadValues.length)];
    }

    /**
     * 論理サンプル番号を循環バッファ内の配列インデックスへ変換する。
     *
     * @param sampleIndex 変換する PCM サンプル番号
     * @return pcm 配列内のインデックス
     */
    private int physicalIndex(long sampleIndex) {
        return Math.floorMod(sampleIndex, pcm.length);
    }

    /**
     * PCM 保持範囲を容量以内に切り詰める。
     */
    private void trimToCapacity() {
        long minimumStart = Math.max(0, endSampleIndexExclusive - pcm.length);
        if (minimumStart > startSampleIndex) {
            startSampleIndex = minimumStart;
            trimVadToPcmRange();
        }
    }

    /**
     * VAD 保持範囲を現在の PCM 保持範囲に合わせて切り詰める。
     */
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
