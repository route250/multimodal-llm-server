package audio.vad;

public interface TurnDetector {
    /**
     * 指定された音声サンプルが発話ターンの完了を示すかを返す。
     *
     * @param samples 判定対象の音声サンプル。値の範囲は -1.0 以上 1.0 未満
     * @return 発話ターンが完了している場合は true
     */
    boolean isTurnComplete(float[] samples);
}
