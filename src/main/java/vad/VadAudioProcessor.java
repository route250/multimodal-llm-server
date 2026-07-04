package vad;

import audio.AudioBuffer;
import audio.AudioDiagnostics;
import audio.Pcm16Le;

import java.time.Duration;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import stt.SpeechToText;
import stt.Transcription;

public class VadAudioProcessor {
    /** VAD と STT に渡す PCM のサンプルレート。単位は Hz。 */
    public static final int SAMPLE_RATE = 16_000;
    /** ブラウザ VAD 値 1 個に対応する PCM サンプル数。 */
    public static final int VAD_FRAME_SAMPLES = 256;
    /** 発話開始時に先頭へ追加する過去音声のサンプル数。9,600 サンプルは 16 kHz で 600 ms。 */
    public static final int PRE_ROLL_SAMPLES = 9_600;
    /** スパイクから発話開始と判定するまでに必要なサンプル数。3,200 サンプルは 16 kHz で 200 ms。 */
    public static final int SPIKE_SAMPLES = 3_200;
    /** 発話終了と判定するまでに必要な無音区間のサンプル数。9,600 サンプルは 16 kHz で 600 ms。 */
    public static final int END_SILENCE_SAMPLES = 9_600;
    /** 発話が継続している間に中間 STT を実行する間隔。76,800 サンプルは 16 kHz で 4.8 秒。 */
    public static final int PARTIAL_TRANSCRIPTION_INTERVAL_SAMPLES = 76_800;
    /** 重複セグメント後も STT 入力へ残す先頭側マージン。1,600 サンプルは 16 kHz で 100 ms。 */
    public static final int TRANSCRIPTION_OVERLAP_MARGIN_SAMPLES = 1_600;
    /** Whisper の prompt に渡す直近テキストの最大文字数。 */
    public static final int TRANSCRIPTION_PROMPT_MAX_CHARS = 500;
    /** 短音声で prompt を抑止する長さ。24,000 サンプルは 16 kHz で 1,500 ms。 */
    public static final int SHORT_AUDIO_PROMPT_SUPPRESSION_SAMPLES = 24_000;
    /** 短音声で prompt を抑止する RMS 下限。0.01 は -40 dBFS 相当。 */
    public static final float LOW_RMS_PROMPT_SUPPRESSION_THRESHOLD = 0.01f;
    /** 1 セグメント一致だけで重複と判断する最小文字数。 */
    public static final int MIN_OVERLAP_TEXT_LENGTH = 8;
    /** 1 セグメント一致だけで重複と判断する最小音声長。11,200 サンプルは 16 kHz で 700 ms。 */
    public static final int MIN_OVERLAP_SAMPLES = 11_200;
    /** SmartTurn によるターン検出を開始するまでに必要な無音区間のサンプル数。 */
    public static final int MIN_TURN_DETECTION_SILENCE_SAMPLES = END_SILENCE_SAMPLES;
    /** SmartTurn によるターン検出を再試行する間隔のサンプル数。 */
    public static final int TURN_DETECTION_INTERVAL_SAMPLES = 9_600;
    /** 非発話状態から発話状態へ切り替える VAD 確率の下限値。 */
    public static final float START_THRESHOLD = 0.65f;
    /** 発話状態を継続する VAD 確率の下限値。 */
    public static final float END_THRESHOLD = 0.35f;

    /** 受信直後の PCM と VAD 値を保持するバッファ。 */
    private final AudioBuffer receiveBuffer = new AudioBuffer(SAMPLE_RATE * 30, VAD_FRAME_SAMPLES);
    /** STT に渡す発話区間の PCM を保持する長時間用バッファ。5 分までの発話を保持する。 */
    private final AudioBuffer sttBuffer = new AudioBuffer(SAMPLE_RATE * 5 * 60, VAD_FRAME_SAMPLES);
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
    private final ArrayDeque<TranscriptionResult> transcribeResults = new ArrayDeque<>();
    /** 音声処理状態の変更を外部へ通知する処理。 */
    private Consumer<SpeechStateChange> speechStateListener = change -> {
    };
    /** STT タスクで発生し、次回の音声処理で表面化させる例外。 */
    private RuntimeException pendingTranscriptionFailure;
    /** 次回以降の STT 入力に使える先頭サンプル番号。重複セグメント検出後に前へ戻らない値として更新する。 */
    private long transcriptionStartFloorSampleIndex;
    /** 重複セグメント検出に使う直近の採用済み STT 結果。 */
    private TranscriptionReference previousTranscription;
    /** Whisper の prompt に渡す直近の文字起こしテキスト。 */
    private String transcriptionPrompt = "";
    /** 現在の発話区間を識別する番号。古い STT タスクの結果を捨てるために使う。 */
    private long speechSequenceId;
    /** 次に受信する PCM チャンクの先頭サンプル番号。 */
    private long nextSampleIndex;
    /** 次に VAD を実行するフレームの先頭サンプル番号。 */
    private long nextVadStartSampleIndex;
    /** 現在の音声処理状態。 */
    private SpeechState speechState = SpeechState.UNDETECTED;
    /** スパイクが検出された際の開始サンプル番号。 */
    private long spikeStartSampleIndex;
    /** 現在の発話区間の先頭サンプル番号。PRE_ROLL_SAMPLES を含む場合がある。 */
    private long speechStartSampleIndex;
    /** STT 用バッファへ蓄積済みの現在発話区間の終端サンプル番号。 */
    private long sttBufferedEndSampleIndex;
    /** END_THRESHOLD を超えた最後の VAD フレームの終端サンプル番号。 */
    private long lastSpeechSampleIndex;
    /** SmartTurn によるターン検出を最後に実行したフレームの終端サンプル番号。 */
    private long lastTurnDetectionSampleIndex = Long.MIN_VALUE;
    /** 次に中間 STT を実行する発話内の終端サンプル番号。 */
    private long nextPartialTranscriptionEndSampleIndex = Long.MAX_VALUE;
    /** 次に結果として返す文字起こしの先頭サンプル番号。 */
    private long nextPartialTranscriptionStartSampleIndex;
    /** 診断ログへ出力する接続情報。 */
    private AudioDiagnostics.Context diagnosticsContext = AudioDiagnostics.Context.empty();

    /**
     * ブラウザ VAD、ターン検出、STT の実装、STT 用 executor を受け取って音声処理器を作成する。
     *
     * @param turnDetector 発話区間が会話ターンとして完了したかを判定する実装
     * @param speechToText 発話区間の PCM を文字起こしする実装
     * @param transcriptionExecutor STT を実行する executor。音声受信処理とは別タスクで実行する
     */
    public VadAudioProcessor(
            TurnDetector turnDetector,
            SpeechToText speechToText,
            Executor transcriptionExecutor) {
        this.turnDetector = Objects.requireNonNull(turnDetector);
        this.speechToText = Objects.requireNonNull(speechToText);
        this.transcriptionExecutor = Objects.requireNonNull(transcriptionExecutor);
    }

    private void setState(long pos, SpeechState newState) {
        setState(pos, newState, Float.NaN);
    }

    private void setState(long pos, SpeechState newState, float vadProbability) {
        if (speechState != newState) {
            SpeechState previousState = speechState;
            speechState = newState;
            AudioDiagnostics.log("vad-state-change", diagnosticsContext, AudioDiagnostics.fields(
                    "speechSequenceId", speechSequenceId,
                    "startSampleIndex", pos,
                    "durationMs", sampleIndexToMillis(pos),
                    "vadProbability", Float.isNaN(vadProbability) ? null : vadProbability,
                    "stateFrom", previousState,
                    "stateTo", newState));
            speechStateListener.accept(new SpeechStateChange(
                    previousState,
                    newState,
                    speechSequenceId,
                    pos));
        }
    }

    /**
     * 診断ログへ出力する接続情報を設定する。
     *
     * @param groupId チャットグループID
     * @param sessionId セッションID
     */
    public synchronized void setDiagnosticsContext(String groupId, String sessionId) {
        diagnosticsContext = AudioDiagnostics.context(groupId, sessionId);
    }

    /**
     * 音声処理状態の変更通知を設定する。
     *
     * @param listener 状態変更を受け取る処理
     */
    public synchronized void setSpeechStateListener(Consumer<SpeechStateChange> listener) {
        speechStateListener = listener == null ? change -> {
        } : listener;
    }

    /**
     * 指定サンプル番号より前の音声を今後の STT 対象から外す。
     *
     * @param sampleIndex STT 対象として許可する最小サンプル番号
     */
    public synchronized void ignoreTranscriptionBefore(long sampleIndex) {
        transcriptionStartFloorSampleIndex = Math.max(transcriptionStartFloorSampleIndex, sampleIndex);
    }

    /**
     * ブラウザ VAD 値なしの PCM 入力は受け付けない。
     *
     * @param bytes PCM 16-bit little-endian 形式の音声バイト列
     * @return 常に例外を送出する
     */
    public synchronized Optional<Transcription> acceptPcm16Le(byte[] bytes) {
        return acceptPcm16LeDetailed(bytes).map(TranscriptionResult::transcription);
    }

    /**
     * ブラウザ VAD 値なしの PCM 入力は受け付けない。
     *
     * @param bytes PCM 16-bit little-endian 形式の音声バイト列
     * @return 常に例外を送出する
     */
    public synchronized Optional<TranscriptionResult> acceptPcm16LeDetailed(byte[] bytes) {
        throw new IllegalArgumentException("browser VAD bytes are required");
    }

    /**
     * PCM 16-bit little-endian とブラウザ VAD のバイト列を受け取り、発話終了時に文字起こし結果を返す。
     *
     * @param bytes PCM 16-bit little-endian 形式の音声バイト列
     * @param vadBytes 下位 7 bit に 0..100 の VAD 値、最上位 bit に再生フラグを持つバイト列
     * @return 発話区間が確定した場合は文字起こし結果。未確定の場合は Optional.empty()
     */
    public synchronized Optional<Transcription> acceptPcm16LeWithVad(byte[] bytes, byte[] vadBytes) {
        return acceptPcm16LeWithVadDetailed(bytes, vadBytes).map(TranscriptionResult::transcription);
    }

    /**
     * PCM 16-bit little-endian とブラウザ VAD のバイト列を受け取り、発話終了時に文字起こし結果と種別を返す。
     *
     * @param bytes PCM 16-bit little-endian 形式の音声バイト列
     * @param vadBytes 下位 7 bit に 0..100 の VAD 値、最上位 bit に再生フラグを持つバイト列
     * @return 発話区間が確定した場合は文字起こし結果。未確定の場合は Optional.empty()
     */
    public synchronized Optional<TranscriptionResult> acceptPcm16LeWithVadDetailed(byte[] bytes, byte[] vadBytes) {
        short[] samples = Pcm16Le.decode(bytes);
        long chunkStartSampleIndex = nextSampleIndex;
        receiveBuffer.append(samples, chunkStartSampleIndex);
        for (int i = 0; i < vadBytes.length; i++) {
            int vadValue = Byte.toUnsignedInt(vadBytes[i]) & 0x7f;
            receiveBuffer.putVadValue(chunkStartSampleIndex + (long) i * VAD_FRAME_SAMPLES, vadValue / 100.0f);
        }
        nextSampleIndex += samples.length;
        return processAvailableWindows();
    }

    /**
     * 現在投入済みの STT タスクが完了するまで待つ。
     *
     * <p>デバッグ実行やテストで音声投入直後に executor を閉じる場合、後続 STT が投入される前に
     * executor が終了しないよう、このメソッドでキュー末尾の完了を待つ。</p>
     *
     * @param timeout 最大待機時間
     */
    public void awaitTranscriptions(Duration timeout) throws InterruptedException {
        CompletableFuture<Void> taskTail;
        synchronized (transcriptionTaskLock) {
            taskTail = transcriptionTaskTail;
        }
        try {
            taskTail.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            throw new IllegalStateException("transcription task failed", e.getCause());
        } catch (TimeoutException e) {
            throw new IllegalStateException("timed out waiting for transcription tasks", e);
        }
    }

    /**
     * 受信済み PCM から VAD 実行可能なフレームを順に処理する。
     *
     * @return この処理中に発話区間が確定した場合は最後の文字起こし結果。未確定の場合は Optional.empty()
     */
    private Optional<TranscriptionResult> processAvailableWindows() {
        long latestFrameStart = receiveBuffer.endSampleIndexExclusive() - VAD_FRAME_SAMPLES;
        while (nextVadStartSampleIndex <= latestFrameStart) {
            if (nextVadStartSampleIndex < receiveBuffer.startSampleIndex()) {
                nextVadStartSampleIndex = alignToNextFrame(receiveBuffer.startSampleIndex());
                continue;
            }

            float probability = receiveBuffer.vadValue(nextVadStartSampleIndex);
            if (Float.isNaN(probability)) {
                break;
            }
            receiveBuffer.putVadValue(nextVadStartSampleIndex, probability);
            updateSpeechState(nextVadStartSampleIndex, probability);
            nextVadStartSampleIndex += VAD_FRAME_SAMPLES;
        }
        if (pendingTranscriptionFailure != null) {
            RuntimeException failure = pendingTranscriptionFailure;
            pendingTranscriptionFailure = null;
            throw failure;
        }

        TranscriptionResult transcriptionResult = transcribeResults.pollFirst();
        if (transcriptionResult != null) {
            return Optional.of(transcriptionResult);
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
                    setState(frameStartSampleIndex, SpeechState.SPIKE, probability);
                    spikeStartSampleIndex = frameStartSampleIndex;
                }
                return;
            }
            case SPIKE -> {
                if (probability > END_THRESHOLD) {
                    if (frameStartSampleIndex - spikeStartSampleIndex >= SPIKE_SAMPLES) {
                        setState(frameStartSampleIndex, SpeechState.DETECTED, probability);
                        startSpeech(spikeStartSampleIndex, frameEndSampleIndex);
                    }
                } else {
                    setState(frameStartSampleIndex, SpeechState.UNDETECTED, probability);
                }
                return;
            }
            case DETECTED -> {
                // 発話検出状態で END_THRESHOLD を超えている間は発話継続として扱う。
                if (probability > END_THRESHOLD) {
                    lastSpeechSampleIndex = frameEndSampleIndex;
                    appendCurrentSpeechToSttBuffer(frameEndSampleIndex);
                    maybeTranscribePartialSpeech(frameEndSampleIndex);
                    return;
                }
                setState(frameStartSampleIndex, SpeechState.TRAILING_SILENCE, probability);
                return;
            }
            case TRAILING_SILENCE -> {
                // 後続無音状態で END_THRESHOLD を超えた場合は同じ発話区間として発話検出状態へ戻す。
                if (probability > END_THRESHOLD) {
                    setState(frameStartSampleIndex, SpeechState.DETECTED, probability);
                    lastSpeechSampleIndex = frameEndSampleIndex;
                    appendCurrentSpeechToSttBuffer(frameEndSampleIndex);
                    maybeTranscribePartialSpeech(frameEndSampleIndex);
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
                appendCurrentSpeechToSttBuffer(speechEndSampleIndex);
                if (!sttBuffer.contains(speechStartSampleIndex, speechEndSampleIndex)) {
                    return;
                }
                setState(frameStartSampleIndex, SpeechState.TURN_DETECTING, probability);
                if (!detectTurn(speechEndSampleIndex)) {
                    setState(frameStartSampleIndex, SpeechState.TRAILING_SILENCE, probability);
                    return;
                }
                setState(frameStartSampleIndex, SpeechState.TRANSCRIBING, probability);
                transcribeCompletedSpeech(speechEndSampleIndex);
                return;
            }
            case TURN_DETECTING -> {
                return;
            }
            case TRANSCRIBING -> {
                // START_THRESHOLD 以上になったら新しい発話区間として発話検出状態へ戻す。
                if (probability >= START_THRESHOLD) {
                    setState(frameStartSampleIndex, SpeechState.DETECTED, probability);
                    startSpeech(frameStartSampleIndex, frameEndSampleIndex);
                }
                return;
            }
        }
        throw new IllegalStateException("unknown speech state: " + speechState);
    }

    /**
     * 新しい発話区間を開始し、重複検出済みの先頭位置より前を STT 対象から外す。
     */
    private void startSpeech(long frameStartSampleIndex, long frameEndSampleIndex) {
        speechSequenceId++;
        long preRollStartSampleIndex = Math.max(receiveBuffer.startSampleIndex(), frameStartSampleIndex - PRE_ROLL_SAMPLES);
        long boundedStartFloorSampleIndex = Math.min(transcriptionStartFloorSampleIndex, frameStartSampleIndex);
        speechStartSampleIndex = Math.max(preRollStartSampleIndex, boundedStartFloorSampleIndex);
        sttBufferedEndSampleIndex = speechStartSampleIndex;
        lastSpeechSampleIndex = frameEndSampleIndex;
        lastTurnDetectionSampleIndex = Long.MIN_VALUE;
        nextPartialTranscriptionEndSampleIndex = frameStartSampleIndex + PARTIAL_TRANSCRIPTION_INTERVAL_SAMPLES;
        nextPartialTranscriptionStartSampleIndex = speechStartSampleIndex;
        appendCurrentSpeechToSttBuffer(frameEndSampleIndex);
    }

    /**
     * SmartTurn によるターン検出を実行し、完了なら STT、未完了なら後続無音状態へ戻す。
     *
     * @param frameEndSampleIndex 現在処理している VAD フレームの終端サンプル番号
     * @return ターン完了を検出した場合は文字起こし結果。未完了の場合は Optional.empty()
     */
    private boolean detectTurn(long speechEndSampleIndex) {
        long startSampleIndex = transcriptionRangeStartSampleIndex(speechEndSampleIndex);
        return turnDetector.isTurnComplete(sttBuffer.floats(
                startSampleIndex,
                Math.toIntExact(speechEndSampleIndex - startSampleIndex)));
    }

    /**
     * 現在の発話区間を STT 用バッファへ追加する。
     *
     * <p>receiveBuffer は直近 30 秒だけを保持するため、長い発話では終了時に先頭側の PCM が破棄される。
     * STT 用バッファへ発話中に順次移すことで、長い発話でも文字起こし対象を保持する。</p>
     */
    private void appendCurrentSpeechToSttBuffer(long endSampleIndexExclusive) {
        long copyStartSampleIndex = Math.max(speechStartSampleIndex, sttBufferedEndSampleIndex);
        if (endSampleIndexExclusive <= copyStartSampleIndex) {
            return;
        }
        sttBuffer.appendRangeFrom(receiveBuffer, copyStartSampleIndex, endSampleIndexExclusive);
        sttBufferedEndSampleIndex = endSampleIndexExclusive;
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
     * 発話継続中に 2.4 秒単位の中間 STT を投入する。
     */
    private void maybeTranscribePartialSpeech(long frameEndSampleIndex) {
        while (frameEndSampleIndex >= nextPartialTranscriptionEndSampleIndex) {
            long endSampleIndexExclusive = nextPartialTranscriptionEndSampleIndex;
            long startSampleIndex = Math.max(
                    nextPartialTranscriptionStartSampleIndex,
                    transcriptionRangeStartSampleIndex(endSampleIndexExclusive));
            if (endSampleIndexExclusive > startSampleIndex
                    && sttBuffer.contains(startSampleIndex, endSampleIndexExclusive)) {
                transcribeSpeech(
                        speechSequenceId,
                        startSampleIndex,
                        endSampleIndexExclusive,
                        TranscriptionKind.PARTIAL);
                nextPartialTranscriptionStartSampleIndex = Math.max(
                        speechStartSampleIndex,
                        endSampleIndexExclusive - TRANSCRIPTION_OVERLAP_MARGIN_SAMPLES);
            }
            nextPartialTranscriptionEndSampleIndex += PARTIAL_TRANSCRIPTION_INTERVAL_SAMPLES;
        }
    }

    /**
     * 現在の STT 開始下限を反映した、STT 入力範囲の先頭サンプル番号を返す。
     */
    private long transcriptionRangeStartSampleIndex(long endSampleIndexExclusive) {
        long boundedStartFloorSampleIndex = Math.min(transcriptionStartFloorSampleIndex, endSampleIndexExclusive);
        return Math.max(speechStartSampleIndex, boundedStartFloorSampleIndex);
    }

    /**
     * 確定した発話区間を STT 実行中状態で文字起こしし、完了後に未検出状態へ戻す。
     *
     * @param speechEndSampleIndex 文字起こし対象の終端サンプル番号。この番号のサンプルは含まない
     * @return 文字起こし結果
     */
    private void transcribeCompletedSpeech(long speechEndSampleIndex) {
        long transcriptionStartSampleIndex = Math.max(
                transcriptionRangeStartSampleIndex(speechEndSampleIndex),
                nextPartialTranscriptionStartSampleIndex);
        if (speechEndSampleIndex <= transcriptionStartSampleIndex) {
            return;
        }
        long transcriptionSpeechSequenceId = speechSequenceId;
        transcribeSpeech(
                transcriptionSpeechSequenceId,
                transcriptionStartSampleIndex,
                speechEndSampleIndex,
                TranscriptionKind.FINAL);
    }

    /**
     * 指定範囲の音声をコピーして STT タスクを発話順に投入する。
     */
    private void transcribeSpeech(
            long transcriptionSpeechSequenceId,
            long transcriptionStartSampleIndex,
            long endSampleIndexExclusive,
            TranscriptionKind kind) {
        AudioBuffer transcriptionAudio = copyTranscriptionAudio(transcriptionStartSampleIndex, endSampleIndexExclusive);
        float rms = rms(transcriptionAudio, transcriptionStartSampleIndex, endSampleIndexExclusive);
        boolean suppressPrompt = shouldSuppressPrompt(
                endSampleIndexExclusive - transcriptionStartSampleIndex,
                rms);
        String prompt = suppressPrompt ? "" : transcriptionPrompt;
        AudioDiagnostics.log("stt-queued", diagnosticsContext, AudioDiagnostics.fields(
                "speechSequenceId", transcriptionSpeechSequenceId,
                "kind", kind,
                "startSampleIndex", transcriptionStartSampleIndex,
                "endSampleIndexExclusive", endSampleIndexExclusive,
                "durationMs", samplesToMillis(endSampleIndexExclusive - transcriptionStartSampleIndex),
                "pcmBytes", (endSampleIndexExclusive - transcriptionStartSampleIndex) * Short.BYTES,
                "rms", rms,
                "promptSuppressed", suppressPrompt,
                "promptChars", prompt.length()));
        synchronized (transcriptionTaskLock) {
            transcriptionTaskTail = transcriptionTaskTail.handle((ignored, error) -> null)
                    .thenRunAsync(
                            () -> transcribeAsync(
                                    transcriptionSpeechSequenceId,
                                    transcriptionAudio,
                                    transcriptionStartSampleIndex,
                                    endSampleIndexExclusive,
                                    prompt,
                                    kind),
                            transcriptionExecutor);
        }
    }

    /**
     * 別タスクで STT を実行し、結果または失敗を通知する。
     */
    private void transcribeAsync(
            long transcriptionSpeechSequenceId,
            AudioBuffer transcriptionAudio,
            long startSampleIndex,
            long endSampleIndexExclusive,
            String prompt,
            TranscriptionKind kind) {
        try {
            AudioDiagnostics.SavedAudioFiles savedAudioFiles = AudioDiagnostics.saveWav(
                            diagnosticsContext,
                            transcriptionSpeechSequenceId,
                            kind.name(),
                            startSampleIndex,
                            endSampleIndexExclusive,
                            transcriptionAudio)
                    .orElse(null);
            AudioDiagnostics.log("stt-start", diagnosticsContext, AudioDiagnostics.fields(
                    "speechSequenceId", transcriptionSpeechSequenceId,
                    "kind", kind,
                    "startSampleIndex", startSampleIndex,
                    "endSampleIndexExclusive", endSampleIndexExclusive,
                    "durationMs", samplesToMillis(endSampleIndexExclusive - startSampleIndex),
                    "pcmBytes", (endSampleIndexExclusive - startSampleIndex) * Short.BYTES,
                    "promptChars", prompt.length(),
                    "wavPath", savedAudioFiles == null ? null : savedAudioFiles.wavPath().toString(),
                    "vadPath", savedAudioFiles == null ? null : savedAudioFiles.vadPath().toString()));
            Transcription transcription = speechToText.transcribeWithSegments(
                    transcriptionAudio,
                    startSampleIndex,
                    endSampleIndexExclusive,
                    prompt);
            String text = transcription.text();
            synchronized (this) {
                boolean matchesCurrentSpeech = this.speechSequenceId == transcriptionSpeechSequenceId;
                if (text != null ) {
                    if( !text.isEmpty() ) {
                        updateTranscriptionStartFloor(
                                startSampleIndex,
                                endSampleIndexExclusive,
                                transcription,
                                kind == TranscriptionKind.PARTIAL && matchesCurrentSpeech);
                        AudioDiagnostics.log("stt-result", diagnosticsContext, AudioDiagnostics.fields(
                                "speechSequenceId", transcriptionSpeechSequenceId,
                                "kind", kind,
                                "startSampleIndex", startSampleIndex,
                                "endSampleIndexExclusive", endSampleIndexExclusive,
                                "durationMs", samplesToMillis(endSampleIndexExclusive - startSampleIndex),
                                "textChars", text.length(),
                                "text", text));
                        if (matchesCurrentSpeech) {
                            transcribeResults.addLast(new TranscriptionResult(
                                    transcriptionSpeechSequenceId,
                                    startSampleIndex,
                                    endSampleIndexExclusive,
                                    kind,
                                    transcription));
                            appendTranscriptionPrompt(text);
                        }
                    } else {
                        AudioDiagnostics.log("stt-empty-result", diagnosticsContext, AudioDiagnostics.fields(
                                "speechSequenceId", transcriptionSpeechSequenceId,
                                "kind", kind,
                                "startSampleIndex", startSampleIndex,
                                "endSampleIndexExclusive", endSampleIndexExclusive,
                                "durationMs", samplesToMillis(endSampleIndexExclusive - startSampleIndex),
                                "textChars", 0,
                                "text", ""));
                    }
                }
                if (kind == TranscriptionKind.FINAL && speechState == SpeechState.TRANSCRIBING && matchesCurrentSpeech) {
                    setState( endSampleIndexExclusive, SpeechState.UNDETECTED );
                }
            }
        } catch (RuntimeException e) {
            AudioDiagnostics.log("stt-error", diagnosticsContext, AudioDiagnostics.fields(
                    "speechSequenceId", transcriptionSpeechSequenceId,
                    "kind", kind,
                    "startSampleIndex", startSampleIndex,
                    "endSampleIndexExclusive", endSampleIndexExclusive,
                    "durationMs", samplesToMillis(endSampleIndexExclusive - startSampleIndex),
                    "errorClass", e.getClass().getName(),
                    "errorMessage", e.getMessage()));
            synchronized (this) {
                if (this.speechSequenceId == transcriptionSpeechSequenceId && pendingTranscriptionFailure == null) {
                    pendingTranscriptionFailure = e;
                }
            }
        } finally {
            synchronized (this) {
                if (kind == TranscriptionKind.FINAL
                        && speechState == SpeechState.TRANSCRIBING
                        && this.speechSequenceId == transcriptionSpeechSequenceId) {
                    setState( endSampleIndexExclusive, SpeechState.UNDETECTED );
                }
            }
        }
    }

    private static boolean shouldSuppressPrompt(long sampleCount, float rms) {
        return sampleCount < SHORT_AUDIO_PROMPT_SUPPRESSION_SAMPLES
                && rms < LOW_RMS_PROMPT_SUPPRESSION_THRESHOLD;
    }

    private static float rms(AudioBuffer audioBuffer, long startSampleIndex, long endSampleIndexExclusive) {
        if (endSampleIndexExclusive <= startSampleIndex) {
            return 0.0f;
        }
        double squareSum = 0.0;
        for (long sampleIndex = startSampleIndex; sampleIndex < endSampleIndexExclusive; sampleIndex++) {
            double sample = audioBuffer.sampleAt(sampleIndex) / 32768.0;
            squareSum += sample * sample;
        }
        return (float) Math.sqrt(squareSum / (endSampleIndexExclusive - startSampleIndex));
    }

    /**
     * 前回末尾と今回先頭の一致セグメントから、次回 STT 入力の先頭サンプル番号を更新する。
     */
    private void updateTranscriptionStartFloor(
            long startSampleIndex,
            long endSampleIndexExclusive,
            Transcription transcription,
            boolean advanceByPartialSegmentEnd) {
        TranscriptionReference current = TranscriptionReference.from(startSampleIndex, transcription);
        if (previousTranscription != null) {
            Optional<SegmentOverlap> overlap = findSegmentOverlap(previousTranscription.segments(), current.segments());
            if (overlap.isPresent()) {
                long nextStart = Math.max(0, overlap.get().currentEndSampleIndex() - TRANSCRIPTION_OVERLAP_MARGIN_SAMPLES);
                transcriptionStartFloorSampleIndex = Math.max(transcriptionStartFloorSampleIndex, nextStart);
            }
        }
        if (advanceByPartialSegmentEnd) {
            current.lastSegmentEndSampleIndex()
                    .ifPresent(segmentEndSampleIndex -> {
                        long boundedSegmentEndSampleIndex = Math.min(segmentEndSampleIndex, endSampleIndexExclusive);
                        long nextStart = Math.max(0, boundedSegmentEndSampleIndex - TRANSCRIPTION_OVERLAP_MARGIN_SAMPLES);
                        transcriptionStartFloorSampleIndex = Math.max(transcriptionStartFloorSampleIndex, nextStart);
                    });
        }
        previousTranscription = current;
    }

    /**
     * Whisper の prompt 用に直近テキストだけを保持する。
     */
    private void appendTranscriptionPrompt(String text) {
        transcriptionPrompt = (transcriptionPrompt + "\n" + text).trim();
        if (transcriptionPrompt.length() > TRANSCRIPTION_PROMPT_MAX_CHARS) {
            transcriptionPrompt = transcriptionPrompt.substring(
                    transcriptionPrompt.length() - TRANSCRIPTION_PROMPT_MAX_CHARS);
        }
    }

    /**
     * 前回の末尾セグメント列と今回の先頭セグメント列の最長一致を返す。
     */
    private static Optional<SegmentOverlap> findSegmentOverlap(
            List<AbsoluteTranscriptSegment> previous,
            List<AbsoluteTranscriptSegment> current) {
        int maxCount = Math.min(previous.size(), current.size());
        SegmentOverlap best = null;
        for (int count = 1; count <= maxCount; count++) {
            int previousStart = previous.size() - count;
            if (segmentsMatch(previous, previousStart, current, count)) {
                AbsoluteTranscriptSegment last = current.get(count - 1);
                int textLength = normalizedTextLength(current, count);
                long sampleLength = last.endSampleIndex() - current.getFirst().startSampleIndex();
                if (count >= 2 || textLength >= MIN_OVERLAP_TEXT_LENGTH || sampleLength >= MIN_OVERLAP_SAMPLES) {
                    best = new SegmentOverlap(last.endSampleIndex());
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean segmentsMatch(
            List<AbsoluteTranscriptSegment> previous,
            int previousStart,
            List<AbsoluteTranscriptSegment> current,
            int count) {
        for (int i = 0; i < count; i++) {
            String previousText = previous.get(previousStart + i).normalizedText();
            String currentText = current.get(i).normalizedText();
            if (previousText.isEmpty() || !previousText.equals(currentText)) {
                return false;
            }
        }
        return true;
    }

    private static int normalizedTextLength(List<AbsoluteTranscriptSegment> segments, int count) {
        int length = 0;
        for (int i = 0; i < count; i++) {
            length += segments.get(i).normalizedText().length();
        }
        return length;
    }

    private static long durationToSamples(Duration duration) {
        return duration.toNanos() * SAMPLE_RATE / 1_000_000_000L;
    }

    private static long samplesToMillis(long samples) {
        return samples * 1_000L / SAMPLE_RATE;
    }

    private static long sampleIndexToMillis(long sampleIndex) {
        return samplesToMillis(sampleIndex);
    }

    private static String normalizeSegmentText(String text) {
        StringBuilder normalized = new StringBuilder(text.length());
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            if (Character.isWhitespace(codePoint)
                    || type == Character.CONNECTOR_PUNCTUATION
                    || type == Character.DASH_PUNCTUATION
                    || type == Character.START_PUNCTUATION
                    || type == Character.END_PUNCTUATION
                    || type == Character.INITIAL_QUOTE_PUNCTUATION
                    || type == Character.FINAL_QUOTE_PUNCTUATION
                    || type == Character.OTHER_PUNCTUATION) {
                continue;
            }
            normalized.appendCodePoint(Character.toLowerCase(codePoint));
        }
        return normalized.toString();
    }

    private record TranscriptionReference(List<AbsoluteTranscriptSegment> segments) {
        static TranscriptionReference from(long startSampleIndex, Transcription transcription) {
            List<AbsoluteTranscriptSegment> segments = transcription.segments().stream()
                    .map(segment -> new AbsoluteTranscriptSegment(
                            startSampleIndex + durationToSamples(segment.start()),
                            startSampleIndex + durationToSamples(segment.end()),
                            normalizeSegmentText(segment.text())))
                    .toList();
            return new TranscriptionReference(segments);
        }

        Optional<Long> lastSegmentEndSampleIndex() {
            for (int i = segments.size() - 1; i >= 0; i--) {
                AbsoluteTranscriptSegment segment = segments.get(i);
                if (!segment.normalizedText().isEmpty()) {
                    return Optional.of(segment.endSampleIndex());
                }
            }
            return Optional.empty();
        }
    }

    private record AbsoluteTranscriptSegment(long startSampleIndex, long endSampleIndex, String normalizedText) {
    }

    private record SegmentOverlap(long currentEndSampleIndex) {
    }

    public enum TranscriptionKind {
        PARTIAL,
        FINAL
    }

    /**
     * STT 結果と、その結果がどの発話・範囲・種別から得られたかを表す。
     *
     * @param speechSequenceId 発話区間ID
     * @param startSampleIndex STT対象の先頭サンプル番号
     * @param endSampleIndexExclusive STT対象の終端サンプル番号。この番号のサンプルは含まない
     * @param kind 中間結果か最終結果か
     * @param transcription STT結果
     */
    public record TranscriptionResult(
            long speechSequenceId,
            long startSampleIndex,
            long endSampleIndexExclusive,
            TranscriptionKind kind,
            Transcription transcription) {
    }

    /**
     * VAD フレーム処理から STT 完了までの音声処理状態変更。
     *
     * @param previousState 変更前の状態
     * @param currentState 変更後の状態
     * @param speechSequenceId 発話区間ID
     * @param sampleIndex 状態変更が発生したサンプル番号
     */
    public record SpeechStateChange(
            SpeechState previousState,
            SpeechState currentState,
            long speechSequenceId,
            long sampleIndex) {
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
    public enum SpeechState {
        /** 発話を検出していない状態。 */
        UNDETECTED,
        /** START_THRESHOLD 以上のフレームを検出し、発話確定に必要な継続時間を確認している状態。 */
        SPIKE,
        /** 発話を検出し、END_THRESHOLD を超えるフレームが続いている状態。 */
        DETECTED,
        /** 発話後に END_THRESHOLD 以下のフレームが続き、終了判定待ちの状態。 */
        TRAILING_SILENCE,
        /** SmartTurn で発話ターンの完了を判定している状態。 */
        TURN_DETECTING,
        /** 確定した発話区間を STT へ渡している状態。 */
        TRANSCRIBING
    }
}
