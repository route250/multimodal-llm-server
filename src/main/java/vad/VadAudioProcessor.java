package vad;

import audio.AudioBuffer;
import audio.Pcm16Le;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import stt.SpeechToText;
import vad.silero.SileroVad;
import vad.silero.VoiceActivityDetector;

public class VadAudioProcessor {
    /** VAD と STT に渡す PCM のサンプルレート。単位は Hz。 */
    public static final int SAMPLE_RATE = 16_000;
    /** VAD を 1 回実行する PCM サンプル数。 */
    public static final int VAD_FRAME_SAMPLES = SileroVad.CHUNK_SAMPLES;
    /** 発話開始時に先頭へ追加する過去音声のサンプル数。9,600 サンプルは 16 kHz で 600 ms。 */
    public static final int PRE_ROLL_SAMPLES = 9_600;
    /** 発話終了と判定するまでに必要な無音区間のサンプル数。9,600 サンプルは 16 kHz で 600 ms。 */
    public static final int END_SILENCE_SAMPLES = 9_600;
    /** SmartTurn によるターン検出を開始するまでに必要な無音区間のサンプル数。 */
    public static final int MIN_TURN_DETECTION_SILENCE_SAMPLES = END_SILENCE_SAMPLES;
    /** SmartTurn によるターン検出を再試行する間隔のサンプル数。 */
    public static final int TURN_DETECTION_INTERVAL_SAMPLES = 9_600;
    /** 非発話状態から発話状態へ切り替える VAD 確率の下限値。 */
    public static final float START_THRESHOLD = 0.5f;
    /** 発話状態を継続する VAD 確率の下限値。 */
    public static final float END_THRESHOLD = 0.35f;

    /** 受信直後の PCM と VAD 値を保持するバッファ。 */
    private final AudioBuffer receiveBuffer = new AudioBuffer(SAMPLE_RATE * 30, VAD_FRAME_SAMPLES);
    /** STT に渡す発話区間の PCM を保持する長時間用バッファ。 */
    private final AudioBuffer sttBuffer = new AudioBuffer(SAMPLE_RATE * 30, VAD_FRAME_SAMPLES);
    /** PCM フレームから発話確率を計算する VAD 実装。 */
    private final VoiceActivityDetector vad;
    /** 発話区間が会話ターンとして完了したかを判定する実装。 */
    private final TurnDetector turnDetector;
    /** 確定した発話区間を文字起こしする STT 実装。 */
    private final SpeechToText speechToText;
    /** STT を音声受信処理とは別に実行する executor */
    private final Executor transcriptionExecutor;
    /** STT タスクを発話確定順に実行するための末尾 Future。 */
    private final Object transcriptionTaskLock = new Object();
    private CompletableFuture<Void> transcriptionTaskTail = CompletableFuture.completedFuture(null);
    /** STT 完了時に文字起こし結果を通知する処理。 */
    private final ArrayDeque<TranscribeSegment> transcribeResults = new ArrayDeque<>();
    /** STT タスクで発生し、次回の音声処理で表面化させる例外。 */
    private RuntimeException pendingTranscriptionFailure;
    /** 次に受信する PCM チャンクの先頭サンプル番号。 */
    private long nextSampleIndex;
    /** 次に VAD を実行するフレームの先頭サンプル番号。 */
    private long nextVadStartSampleIndex;
    /** 現在の音声処理状態。 */
    private SpeechState speechState = SpeechState.UNDETECTED;
    /** 現在の発話区間の先頭サンプル番号。PRE_ROLL_SAMPLES を含む場合がある。 */
    private long speechStartSampleIndex;
    /** END_THRESHOLD を超えた最後の VAD フレームの終端サンプル番号。 */
    private long lastSpeechSampleIndex;
    /** SmartTurn によるターン検出を最後に実行したフレームの終端サンプル番号。 */
    private long lastTurnDetectionSampleIndex = Long.MIN_VALUE;

    /**
     * VAD、ターン検出、STT の実装、STT 用 executor を受け取って音声処理器を作成する。
     *
     * @param vad PCM フレームから発話確率を計算する実装
     * @param turnDetector 発話区間が会話ターンとして完了したかを判定する実装
     * @param speechToText 発話区間の PCM を文字起こしする実装
     * @param transcriptionExecutor STT を実行する executor。音声受信処理とは別タスクで実行する
     */
    public VadAudioProcessor(
            VoiceActivityDetector vad,
            TurnDetector turnDetector,
            SpeechToText speechToText,
            Executor transcriptionExecutor) {
        this.vad = Objects.requireNonNull(vad);
        this.turnDetector = Objects.requireNonNull(turnDetector);
        this.speechToText = Objects.requireNonNull(speechToText);
        this.transcriptionExecutor = Objects.requireNonNull(transcriptionExecutor);
    }

    /**
     * PCM 16-bit little-endian のバイト列を受け取り、発話終了時に文字起こし結果を返す。
     *
     * @param bytes PCM 16-bit little-endian 形式の音声バイト列
     * @return 発話区間が確定した場合は文字起こし結果。未確定の場合は Optional.empty()
     */
    public synchronized Optional<String> acceptPcm16Le(byte[] bytes) {
        short[] samples = Pcm16Le.decode(bytes);
        long chunkStartSampleIndex = nextSampleIndex;
        receiveBuffer.append(samples, chunkStartSampleIndex);
        nextSampleIndex += samples.length;
        return processAvailableWindows();
    }

    /**
     * 受信済み PCM から VAD 実行可能なフレームを順に処理する。
     *
     * @return この処理中に発話区間が確定した場合は最後の文字起こし結果。未確定の場合は Optional.empty()
     */
    private Optional<String> processAvailableWindows() {
        long latestFrameStart = receiveBuffer.endSampleIndexExclusive() - VAD_FRAME_SAMPLES;
        while (nextVadStartSampleIndex <= latestFrameStart) {
            if (nextVadStartSampleIndex < receiveBuffer.startSampleIndex()) {
                nextVadStartSampleIndex = alignToNextFrame(receiveBuffer.startSampleIndex());
                continue;
            }

            float probability = vad.speechProbability(receiveBuffer.floats(nextVadStartSampleIndex, VAD_FRAME_SAMPLES));
            receiveBuffer.putVadValue(nextVadStartSampleIndex, probability);
            updateSpeechState(nextVadStartSampleIndex, probability);
            nextVadStartSampleIndex += VAD_FRAME_SAMPLES;
        }
        if (pendingTranscriptionFailure != null) {
            RuntimeException failure = pendingTranscriptionFailure;
            pendingTranscriptionFailure = null;
            throw failure;
        }

        TranscribeSegment segment = transcribeResults.pollFirst();
        if (segment != null) {
            return Optional.of(segment.text);
        }
        return Optional.empty();
    }

    /**
     * 1 フレーム分の VAD 結果を発話状態へ反映する。
     *
     * @param frameStartSampleIndex VAD を実行したフレームの先頭サンプル番号
     * @param probability VAD が返した発話確率
     * @return 発話終了を検出した場合は文字起こし結果。検出していない場合は Optional.empty()
     */
    private void updateSpeechState(long frameStartSampleIndex, float probability) {
        long frameEndSampleIndex = frameStartSampleIndex + VAD_FRAME_SAMPLES;
        switch (speechState) {
            case UNDETECTED -> {
                // 未検出状態で START_THRESHOLD 以上になったフレームを発話開始として扱う。
                if (probability >= START_THRESHOLD) {
                    speechState = SpeechState.DETECTED;
                    speechStartSampleIndex = Math.max(receiveBuffer.startSampleIndex(), frameStartSampleIndex - PRE_ROLL_SAMPLES);
                    lastSpeechSampleIndex = frameEndSampleIndex;
                    lastTurnDetectionSampleIndex = Long.MIN_VALUE;
                }
                return;
            }
            case DETECTED -> {
                // 発話検出状態で END_THRESHOLD を超えている間は発話継続として扱う。
                if (probability > END_THRESHOLD) {
                    lastSpeechSampleIndex = frameEndSampleIndex;
                    return;
                }
                speechState = SpeechState.TRAILING_SILENCE;
                return;
            }
            case TRAILING_SILENCE -> {
                // 後続無音状態で END_THRESHOLD を超えた場合は同じ発話区間として発話検出状態へ戻す。
                if (probability > END_THRESHOLD) {
                    speechState = SpeechState.DETECTED;
                    lastSpeechSampleIndex = frameEndSampleIndex;
                    return;
                }

                // MIN_TURN_DETECTION_SILENCE_SAMPLES 未満の無音は、ターン検出の対象にしない。
                if (frameEndSampleIndex - lastSpeechSampleIndex < MIN_TURN_DETECTION_SILENCE_SAMPLES) {
                    return;
                }
                if (lastTurnDetectionSampleIndex != Long.MIN_VALUE
                        && frameEndSampleIndex - lastTurnDetectionSampleIndex < TURN_DETECTION_INTERVAL_SAMPLES) {
                    return;
                }

                lastTurnDetectionSampleIndex = frameEndSampleIndex;

                long speechEndSampleIndex = Math.min(receiveBuffer.endSampleIndexExclusive(), frameEndSampleIndex);
                if (!receiveBuffer.contains(speechStartSampleIndex, speechEndSampleIndex)) {
                    return;
                }
                speechState = SpeechState.TURN_DETECTING;
                if (!detectTurn(speechEndSampleIndex)) {
                    speechState = SpeechState.TRAILING_SILENCE;
                    return;
                }
                speechState = SpeechState.TRANSCRIBING;
                sttBuffer.appendRangeFrom(receiveBuffer, speechStartSampleIndex, speechEndSampleIndex);
                transcribeCompletedSpeech(lastSpeechSampleIndex, speechEndSampleIndex);
                return;
            }
            case TURN_DETECTING -> {
                return;
            }
            case TRANSCRIBING -> {
                // START_THRESHOLD 以上になったら新しい発話区間として発話検出状態へ戻す。
                if (probability >= START_THRESHOLD) {
                    speechState = SpeechState.DETECTED;
                    lastSpeechSampleIndex = frameEndSampleIndex;
                }
                return;
            }
        }
        throw new IllegalStateException("unknown speech state: " + speechState);
    }

    /**
     * SmartTurn によるターン検出を実行し、完了なら STT、未完了なら後続無音状態へ戻す。
     *
     * @param frameEndSampleIndex 現在処理している VAD フレームの終端サンプル番号
     * @return ターン完了を検出した場合は文字起こし結果。未完了の場合は Optional.empty()
     */
    private boolean detectTurn(long speechEndSampleIndex) {
        return turnDetector.isTurnComplete(receiveBuffer.floats(
                speechStartSampleIndex,
                Math.toIntExact(speechEndSampleIndex - speechStartSampleIndex)));
    }


    /**
     * STT タスクが受信バッファや STT バッファと競合しないよう、対象発話だけをコピーする。
     */
    private AudioBuffer copyTranscriptionAudio(long startSampleIndex, long endSampleIndexExclusive) {
        int length = Math.toIntExact(endSampleIndexExclusive - startSampleIndex);
        AudioBuffer transcriptionAudio = new AudioBuffer(Math.max(1, length), VAD_FRAME_SAMPLES);
        transcriptionAudio.appendRangeFrom(sttBuffer, startSampleIndex, endSampleIndexExclusive);
        return transcriptionAudio;
    }
    /**
     * 確定した発話区間を STT 実行中状態で文字起こしし、完了後に未検出状態へ戻す。
     *
     * @param speechEndSampleIndex 文字起こし対象の終端サンプル番号。この番号のサンプルは含まない
     * @return 文字起こし結果
     */
    private void transcribeCompletedSpeech(long lastSpeechSampleIndex, long speechEndSampleIndex) {
        AudioBuffer transcriptionAudio = copyTranscriptionAudio(speechStartSampleIndex, speechEndSampleIndex);
        long transcriptionStartSampleIndex = speechStartSampleIndex;
        synchronized (transcriptionTaskLock) {
            transcriptionTaskTail = transcriptionTaskTail.handle((ignored, error) -> null)
                    .thenRunAsync(
                            () -> transcribeAsync(
                                    lastSpeechSampleIndex,
                                    transcriptionAudio,
                                    transcriptionStartSampleIndex,
                                    speechEndSampleIndex),
                            transcriptionExecutor);
        }
    }

    /**
     * 別タスクで STT を実行し、結果または失敗を通知する。
     */
    private void transcribeAsync(
            long lastSpeechSampleIndex,
            AudioBuffer transcriptionAudio,
            long startSampleIndex,
            long endSampleIndexExclusive) {
        try {
            String text = speechToText.transcribe(transcriptionAudio, startSampleIndex, endSampleIndexExclusive);
            synchronized (this) {
                boolean matchesCurrentSpeech = this.lastSpeechSampleIndex == lastSpeechSampleIndex;
                if (matchesCurrentSpeech && text != null && !text.isEmpty()) {
                    transcribeResults.addLast(new TranscribeSegment(text,startSampleIndex, endSampleIndexExclusive));
                }
                if (speechState == SpeechState.TRANSCRIBING && matchesCurrentSpeech) {
                    speechState = SpeechState.UNDETECTED;
                }
            }
        } catch (RuntimeException e) {
            synchronized (this) {
                if (this.lastSpeechSampleIndex == lastSpeechSampleIndex && pendingTranscriptionFailure == null) {
                    pendingTranscriptionFailure = e;
                }
            }
        } finally {
            synchronized (this) {
                if (speechState == SpeechState.TRANSCRIBING && this.lastSpeechSampleIndex == lastSpeechSampleIndex) {
                    speechState = SpeechState.UNDETECTED;
                }
            }
        }
    }

    /**
     * サンプル番号を次の VAD フレーム境界へ揃える。
     *
     * @param sampleIndex 揃える対象のサンプル番号
     * @return sampleIndex 以上で最小の VAD フレーム境界のサンプル番号
     */
    private static long alignToNextFrame(long sampleIndex) {
        long remainder = sampleIndex % VAD_FRAME_SAMPLES;
        if (remainder == 0) {
            return sampleIndex;
        }
        return sampleIndex + VAD_FRAME_SAMPLES - remainder;
    }

    /**
     * VAD フレーム処理から STT 完了までの音声処理状態。
     */
    private enum SpeechState {
        /** 発話を検出していない状態。 */
        UNDETECTED,
        /** 発話を検出し、END_THRESHOLD を超えるフレームが続いている状態。 */
        DETECTED,
        /** 発話後に END_THRESHOLD 以下のフレームが続き、終了判定待ちの状態。 */
        TRAILING_SILENCE,
        /** SmartTurn で発話ターンの完了を判定している状態。 */
        TURN_DETECTING,
        /** 確定した発話区間を STT へ渡している状態。 */
        TRANSCRIBING
    }
    public static class TranscribeSegment {
        public final long startSampleIndex;
        public final long endSampleIndexExclusive;
        public final String text;
        public TranscribeSegment( String text, long startSampleIndex, long endSampleIndexExclusive ) {
            this.text = text;
            this.startSampleIndex = startSampleIndex;
            this.endSampleIndexExclusive = endSampleIndexExclusive;
        }
    }
}
