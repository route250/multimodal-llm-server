package audio;

import java.util.Arrays;

public class AudioBuffer {
    /** PCM サンプルを保持する循環バッファ。要素数はサンプル数単位の容量。 */
    private final short[] pcm;
    /** VAD 値を保持する循環バッファ。1 要素は samplesPerVadValue 個の PCM サンプルに対応する。 */
    private final byte[] vadValues;
    /** RMS 値を保持する循環バッファ。1 要素は samplesPerVadValue 個の PCM サンプルに対応する。 */
    private final byte[] rmsValues;
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
        this.vadValues = new byte[(capacitySamples + samplesPerVadValue - 1) / samplesPerVadValue];
        this.rmsValues = new byte[vadValues.length];
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
     * 配列全体の PCM サンプルと同じ範囲の VAD/RMS 値を追加する。
     *
     * @param samples 追加する PCM サンプル配列
     * @param sourceStartSampleIndex samples[0] に対応するソース上のサンプル番号
     * @param vadValues VAD 値配列。0〜100 の整数値を持ち、1 要素は samplesPerVadValue 個の PCM サンプルに対応する
     * @param rmsValues RMS 値配列。0〜100 の整数値を持ち、1 要素は samplesPerVadValue 個の PCM サンプルに対応する
     */
    public void append(short[] samples, long sourceStartSampleIndex, byte[] vadValues, byte[] rmsValues) {
        append(samples, vadValues, rmsValues, 0, samples.length, sourceStartSampleIndex);
    }

    /**
     * 配列内の指定範囲の PCM サンプルと同じ範囲の VAD/RMS 値を追加する。
     *
     * @param samples 追加元の PCM サンプル配列
     * @param vadValues VAD 値配列。1 要素は samplesPerVadValue 個の PCM サンプルに対応する
     * @param rmsValues RMS 値配列。1 要素は samplesPerVadValue 個の PCM サンプルに対応する
     * @param offset samples 内で追加を開始する配列インデックス
     * @param length 追加対象の PCM サンプル数
     * @param sourceStartSampleIndex samples[offset] に対応するソース上のサンプル番号
     */
    public void append(
            short[] samples,
            byte[] vadValues,
            byte[] rmsValues,
            int offset,
            int length,
            long sourceStartSampleIndex) {
        if (length <= 0) {
            return;
        }
        if (offset < 0 || length < 0 || length > samples.length - offset) {
            throw new IndexOutOfBoundsException("invalid sample range");
        }
        if (length != Math.multiplyExact(vadValues.length, samplesPerVadValue)
                || vadValues.length != rmsValues.length) {
            throw new IllegalArgumentException("invalid array size");
        }
        if (sourceStartSampleIndex % samplesPerVadValue != 0) {
            throw new IllegalArgumentException("sourceStartSampleIndex must align to metric frame");
        }

        long previousEndSampleIndex = endSampleIndexExclusive;
        long sourceEndSampleIndex = sourceStartSampleIndex + length;
        long writeStartSampleIndex = Math.max(sourceStartSampleIndex, previousEndSampleIndex);
        if (sourceEndSampleIndex <= writeStartSampleIndex) {
            return;
        }
        if (writeStartSampleIndex > endSampleIndexExclusive) {
            endSampleIndexExclusive = writeStartSampleIndex;
            trimToCapacity();
        }

        int writeOffset = offset + Math.toIntExact(writeStartSampleIndex - sourceStartSampleIndex);
        int writeLength = Math.toIntExact(sourceEndSampleIndex - writeStartSampleIndex);
        copyToCircular(samples, writeOffset, pcm, physicalIndex(writeStartSampleIndex), writeLength);
        endSampleIndexExclusive = sourceEndSampleIndex;
        trimToCapacity();

        int metricOffset = Math.toIntExact((writeStartSampleIndex - sourceStartSampleIndex) / samplesPerVadValue);
        int metricLength = vadValues.length - metricOffset;
        long firstVadIndex = writeStartSampleIndex / samplesPerVadValue;
        copyToCircular(vadValues, metricOffset, this.vadValues,
                Math.floorMod(firstVadIndex, this.vadValues.length), metricLength);
        copyToCircular(rmsValues, metricOffset, this.rmsValues,
                Math.floorMod(firstVadIndex, this.rmsValues.length), metricLength);
        if (vadEndIndexExclusive == 0 && vadStartIndex == 0) {
            vadStartIndex = firstVadIndex;
        }
        vadEndIndexExclusive = Math.max(vadEndIndexExclusive, firstVadIndex + metricLength);
        trimVadToPcmRange();
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
        long writeStart = Math.max(startInclusive, endSampleIndexExclusive);
        if (endExclusive <= writeStart) {
            return;
        }
        if (writeStart > endSampleIndexExclusive) {
            endSampleIndexExclusive = writeStart;
            trimToCapacity();
        }
        copyCircular(source.pcm, source.physicalIndex(writeStart), pcm, physicalIndex(writeStart),
                Math.toIntExact(endExclusive - writeStart));
        endSampleIndexExclusive = endExclusive;
        long vadCopyStart = Math.floorDiv(writeStart, samplesPerVadValue) * samplesPerVadValue;
        int metricCount = Math.toIntExact((endExclusive - vadCopyStart + samplesPerVadValue - 1) / samplesPerVadValue);
        byte[] copiedVadValues = new byte[metricCount];
        byte[] copiedRmsValues = new byte[metricCount];
        copyAvailableMetrics(source.vadValues, source.vadStartIndex, source.vadEndIndexExclusive,
                vadCopyStart, copiedVadValues);
        copyAvailableMetrics(source.rmsValues, source.vadStartIndex, source.vadEndIndexExclusive,
                vadCopyStart, copiedRmsValues);
        long firstVadIndex = vadCopyStart / samplesPerVadValue;
        copyToCircular(copiedVadValues, 0, vadValues,
                Math.floorMod(firstVadIndex, vadValues.length), metricCount);
        copyToCircular(copiedRmsValues, 0, rmsValues,
                Math.floorMod(firstVadIndex, rmsValues.length), metricCount);
        if (vadEndIndexExclusive == 0 && vadStartIndex == 0) {
            vadStartIndex = firstVadIndex;
        }
        vadEndIndexExclusive = Math.max(vadEndIndexExclusive, firstVadIndex + metricCount);
        trimVadToPcmRange();
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
     * 指定サンプル番号に対応する VAD 値を返す。
     *
     * @param sampleIndex 取得する VAD 値に対応する PCM サンプル番号
     * @return VAD 値（0〜100）。範囲外の場合は -1
     */
    public int vadValue(long sampleIndex) {
        long vadIndex = sampleIndex / samplesPerVadValue;
        if (vadIndex < vadStartIndex || vadIndex >= vadEndIndexExclusive) {
            return -1;
        }
        return Byte.toUnsignedInt(vadValues[Math.floorMod(vadIndex, vadValues.length)]);
    }

    /**
     * 指定サンプル番号に対応する RMS 値を返す。
     *
     * @param sampleIndex 取得する RMS 値に対応する PCM サンプル番号
     * @return RMS 値（0〜100）。範囲外の場合は -1
     */
    public int rmsValue(long sampleIndex) {
        long vadIndex = sampleIndex / samplesPerVadValue;
        if (vadIndex < vadStartIndex || vadIndex >= vadEndIndexExclusive) {
            return -1;
        }
        return Byte.toUnsignedInt(rmsValues[Math.floorMod(vadIndex, rmsValues.length)]);
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
            int length = Math.toIntExact(Math.min(minimumVadIndex, vadEndIndexExclusive) - vadStartIndex);
            fillCircular(vadValues, Math.floorMod(vadStartIndex, vadValues.length), length, (byte) 0);
            fillCircular(rmsValues, Math.floorMod(vadStartIndex, rmsValues.length), length, (byte) 0);
            vadStartIndex = Math.min(minimumVadIndex, vadEndIndexExclusive);
        }
    }

    /** 直線配列から循環配列へ指定要素数をコピーする。 */
    private static void copyToCircular(
            short[] source, int sourceOffset, short[] target, int targetOffset, int length) {
        int firstLength = Math.min(length, target.length - targetOffset);
        System.arraycopy(source, sourceOffset, target, targetOffset, firstLength);
        if (firstLength < length) {
            System.arraycopy(source, sourceOffset + firstLength, target, 0, length - firstLength);
        }
    }

    /** 直線配列から循環配列へ指定要素数をコピーする。 */
    private static void copyToCircular(
            byte[] source, int sourceOffset, byte[] target, int targetOffset, int length) {
        int targetLength = target.length;
        int firstLength = Math.min(length, targetLength - targetOffset);
        System.arraycopy(source, sourceOffset, target, targetOffset, firstLength);
        if (firstLength < length) {
            System.arraycopy(source, sourceOffset + firstLength, target, 0, length - firstLength);
        }
    }

    /** 循環配列から循環配列へ指定要素数をコピーする。 */
    private static void copyCircular(
            short[] source, int sourceOffset, short[] target, int targetOffset, int length) {
        int sourceLength = source.length;
        int targetLength = target.length;
        int copied = 0;
        while (copied < length) {
            int sourcePart = Math.min(length - copied, sourceLength - sourceOffset);
            int targetPart = Math.min(length - copied, targetLength - targetOffset);
            int partLength = Math.min(sourcePart, targetPart);
            System.arraycopy(source, sourceOffset, target, targetOffset, partLength);
            copied += partLength;
            sourceOffset = (sourceOffset + partLength) % sourceLength;
            targetOffset = (targetOffset + partLength) % targetLength;
        }
    }

    /** 循環配列から循環配列へ指定要素数をコピーする。 */
    private static void copyCircular(
            byte[] source, int sourceOffset, byte[] target, int targetOffset, int length) {
        int sourceLength = source.length;
        int targetLength = target.length;
        int copied = 0;
        while (copied < length) {
            int sourcePart = Math.min(length - copied, sourceLength - sourceOffset);
            int targetPart = Math.min(length - copied, targetLength - targetOffset);
            int partLength = Math.min(sourcePart, targetPart);
            System.arraycopy(source, sourceOffset, target, targetOffset, partLength);
            copied += partLength;
            sourceOffset = (sourceOffset + partLength) % sourceLength;
            targetOffset = (targetOffset + partLength) % targetLength;
        }
    }

    /** 循環配列の指定範囲を同じ値で埋める。 */
    private static void fillCircular(byte[] target, int targetOffset, int length, byte value) {
        int firstLength = Math.min(length, target.length - targetOffset);
        Arrays.fill(target, targetOffset, targetOffset + firstLength, value);
        if (firstLength < length) {
            Arrays.fill(target, 0, length - firstLength, value);
        }
    }

    /** 有効なメトリクス範囲だけを循環配列から直線配列へコピーする。 */
    private static void copyAvailableMetrics(
            byte[] source, long sourceStart, long sourceEnd, long targetStart, byte[] target) {
        long copyStart = Math.max(sourceStart, targetStart);
        long copyEnd = Math.min(sourceEnd, targetStart + target.length);
        if (copyStart >= copyEnd) {
            return;
        }
        copyCircular(source, Math.floorMod(copyStart, source.length), target,
                Math.toIntExact(copyStart - targetStart), Math.toIntExact(copyEnd - copyStart));
    }
}
