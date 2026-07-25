package server;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.LinkedBlockingQueue;

import audio.AudioDiagnostics;
import audio.AudioProcessor;
import audio.AudioProcessor.SpeechStateChange;
import audio.AudioProcessor.TranscriptionKind;
import audio.AudioProcessor.TranscriptionResult;
import audio.AudioProcessor.TranscriptionStarted;
import audio.stt.Lfm2AudioSpeechToText;
import audio.stt.SpeechToTextException;
import audio.tts.AudioDelta;
import audio.tts.Lfm2AudioTextToSpeech;
import audio.tts.StreamingTextChunker;
import audio.tts.TextToSpeech;
import audio.tts.TextToSpeechException;
import audio.vad.smartturn.LazySmartTurnV3;
import facedb.FaceDB;
import facedb.FacePossibility;
import json.Json;
import llm.Message;
import llm.Message.Role;
import llm.LLM;
import llm.LlmOpenAI;
import llm.tools.PersonToolABC;
import llm.LanguageModelException;
import model.download.SmartTurnV3ModelDownloader;
import onnx.OnnxModelException;

/**
 * 1 件の SSE 接続に対応するチャット参加者です。
 * <p>
 * 音声入力の直列処理、STT 結果による応答の中断、LLM/TTS の非同期実行、
 * および再生確認済みメッセージだけを会話履歴へ確定する処理を担当します。
 * ChatGroup へのイベント配信と、クライアント固有のイベント待ち行列は分離しています。
 */
public class ChatClient {
    /** 設定がない ChatClient が使用するシステムプロンプトです。 */
    public static final String DEFAULT_SYSTEM_PROMPT = GroupLlmSettings.LFM25_PROMPT;
    /** 会話履歴としてメモリに保持する最大メッセージ件数です。 */
    private static final int MAX_HISTORY_MESSAGES = 20;
    /** face message marker */
    public static final String FACE_MARKER = "faceEvent";
    public static final String FACE_UNKNOWN = "unknown";
    public static final String FACE_ASSIGNED = "assigned";
    public static final String FACE_KNOWN = "known";
    public static final String FACE_LOST = "lost";

    /** ChatGroup 内でこの接続を識別する sessionId です。 */
    private final String id;
    /** このクライアントが参加するチャットルームです。 */
    private final ChatGroup chatGroup;
    /** SSE ハンドラが取り出す、この接続専用のイベント待ち行列です。 */
    private final LinkedBlockingQueue<ServerEvent> events = new LinkedBlockingQueue<>();
    /** PCM/VAD/RMS を受け取り、発話区間判定と音声認識を実行する処理器です。 */
    private final AudioProcessor audioProcessor;
    /** この接続の音声診断ログに付与する groupId と sessionId です。 */
    private final AudioDiagnostics.Context diagnosticsContext;
    /** 次の LLM 呼び出しで使用するモデルとプロンプトです。設定更新スレッドから置換されます。 */
    private volatile LlmContext languageModel;
    /** LLM のテキストを再生用音声へ変換する処理器です。 */
    private final TextToSpeech textToSpeech;
    /** 音声チャンクを受信順に処理するための直列化用ロックです。 */
    private final Object audioTaskLock = new Object();
    /** close とイベント配信の競合を防ぐロックです。 */
    private final Object lifecycleLock = new Object();
    /** 会話履歴と再生確認待ちメッセージを保護するロックです。 */
    private final Object conversationLock = new Object();
    /** assistant turn、中断、ブラウザ再生状態を保護するロックです。 */
    private final Object playbackControlLock = new Object();
    /** 発話シーケンスごとの部分認識テキストを保護するロックです。 */
    private final Object partialTranscriptLock = new Object();
    /** ブラウザ trackId と FaceDB trackId の対応表を保護するロックです。 */
    private final Object faceTrackLock = new Object();
    /** 再生確認済みの発話だけを保持する、最大 20 件の会話履歴です。 */
    private final List<Message> conversationHistory = new ArrayList<>();
    /** ユーザー発話により中断済みで、以後の出力を破棄する assistant turn の集合です。 */
    private final Set<Long> canceledAssistantTurnIds = new HashSet<>();
    /** 履歴へユーザー発話を確定済みの assistant turn の集合です。 */
    private final Set<Long> rememberedUserAssistantTurnIds = new HashSet<>();
    /** assistant 音声が再生確認されるまで保留するユーザー発話です。 */
    private final Map<Long, PendingUserMessage> pendingUserMessages = new LinkedHashMap<>();
    /** chunk ごとの再生確認を受けるまで保留する assistant テキストです。 */
    private final Map<AssistantChunkKey, PendingAssistantChunk> pendingAssistantChunks = new LinkedHashMap<>();
    /** assistant turn ごとの履歴内メッセージ位置です。複数 chunk を 1 メッセージへ結合します。 */
    private final Map<Long, Integer> rememberedAssistantMessageIndexes = new HashMap<>();
    /** 発話シーケンスごとに受信した部分認識テキストです。 */
    private final Map<Long, String> partialTranscripts = new LinkedHashMap<>();
    // 同一セッション内で、入室済みの顔トラックを区別する。
    private final Map<String, String> trackmap = new LinkedHashMap<>();
    /** 現在入室中のブラウザ顔トラックです。最後の 1 人が退出した時だけ不在通知を履歴へ残します。 */
    private final Map<String, FaceEventResult> activeFaceTracks = new LinkedHashMap<>();
    /** 前の音声処理の完了後に次の処理を接続するための Future です。 */
    private CompletableFuture<Void> audioTaskTail = CompletableFuture.completedFuture(null);
    /** 実行中の LLM/TTS タスクです。ユーザー発話時に cancel します。 */
    private Future<?> activeAssistantTask;
    /** 現在の assistant 応答を識別する連番です。 */
    private long currentAssistantTurnId;
    /** assistant 音声 chunk を識別する連番です。 */
    private long nextAssistantChunkId;
    /** pause/resume/cancel イベントを対応付ける中断連番です。 */
    private long currentInterruptionId;
    /** ブラウザが再生中と報告した assistant turn です。 */
    private long activePlaybackAssistantTurnId;
    /** ブラウザ再生開始時のマイクサンプル位置です。未設定時は Long.MIN_VALUE です。 */
    private long activePlaybackStartSampleIndex = Long.MIN_VALUE;
    /** STT 完了待ちで一時停止した assistant turn です。 */
    private long sttWaitAssistantTurnId;
    /** STT 完了待ちで一時停止した発話シーケンスです。 */
    private long sttWaitSpeechSequenceId = Long.MIN_VALUE;
    /** 現在の assistant turn が出力を配信可能かを表します。 */
    private boolean assistantTurnActive;
    /** STT の最終結果を待つため、再生を一時停止中かを表します。 */
    private boolean sttWaitActive;
    /** close 済みで新規処理とイベント配信を停止したかを表します。 */
    private boolean closed;

    /** 本番用の音声認識・LLM・音声合成を使用して接続クライアントを作成します。 */
    public ChatClient(String id, ChatGroup chatGroup) {
        this(id, chatGroup, new AudioProcessor(
                new LazySmartTurnV3(smartTurnModelPath()),
                new Lfm2AudioSpeechToText(),
                chatGroup::execute),
                chatGroup.server().languageModelSettings(chatGroup.id()),
                defaultTextToSpeech());
    }

    /** Group の LLM 設定を OpenAI 実装とプロンプトテンプレートへ変換する生成経路です。 */
    private ChatClient(
            String id,
            ChatGroup chatGroup,
            AudioProcessor audioProcessor,
            GroupLlmSettings settings,
            TextToSpeech textToSpeech) {
        this(id, chatGroup, audioProcessor, new LlmOpenAI(settings.toConfig()), settings.promptTemplates(), textToSpeech);
    }

    /** テスト用に AudioProcessor を差し替え、LLM と TTS は既定の無効化設定を使います。 */
    ChatClient(String id, ChatGroup chatGroup, AudioProcessor audioProcessor) {
        this(id, chatGroup, audioProcessor, new LlmOpenAI(), GroupLlmSettings.defaults("group-1").promptTemplates(), TextToSpeech.disabled());
    }

    /** テスト用に AudioProcessor と LLM を差し替え、TTS は無効化します。 */
    ChatClient(String id, ChatGroup chatGroup, AudioProcessor audioProcessor, LLM llm) {
        this(id, chatGroup, audioProcessor, llm, GroupLlmSettings.defaults("group-1").promptTemplates(), TextToSpeech.disabled());
    }

    /** テスト用に AudioProcessor、LLM、TTS を個別に差し替えます。 */
    ChatClient(
            String id,
            ChatGroup chatGroup,
            AudioProcessor audioProcessor,
            LLM llm,
            TextToSpeech textToSpeech) {
        this(id, chatGroup, audioProcessor, llm, GroupLlmSettings.defaults("group-1").promptTemplates(), textToSpeech);
    }

    /** テストおよび個別接続用に、メインプロンプトだけを指定します。 */
    /** 指定されたメインプロンプトから、人物認識用を含むプロンプトテンプレートを組み立てます。 */
    ChatClient(
            String id,
            ChatGroup chatGroup,
            AudioProcessor audioProcessor,
            LLM llm,
            String systemPrompt,
            TextToSpeech textToSpeech) {
        this(id, chatGroup, audioProcessor, llm,GroupLlmSettings.custom(systemPrompt).promptTemplates(), textToSpeech);
    }

    /** 依存オブジェクトを直接受け取る内部生成経路です。AudioProcessor の通知先もここで登録します。 */
    ChatClient(
            String id,
            ChatGroup chatGroup,
            AudioProcessor audioProcessor,
            LLM llm,
            PromptTemplates promptTemplates,
            TextToSpeech textToSpeech) {
        this.id = id;
        this.chatGroup = chatGroup;
        this.audioProcessor = audioProcessor;
        this.diagnosticsContext = AudioDiagnostics.context(chatGroup.id(), id);
        this.languageModel = new LlmContext(llm, promptTemplates);
        this.textToSpeech = textToSpeech;
        this.audioProcessor.setDiagnosticsContext(chatGroup.id(), id);
        this.audioProcessor.setSpeechStateListener(this::handleSpeechStateChange);
        this.audioProcessor.setTranscriptionStartedListener(this::handleTranscriptionStarted);
    }

    /** この接続の sessionId を返します。 */
    public String id() {
        return id;
    }

    /** 現在このクライアントが使用している音声区間判定閾値を返す。 */
    AudioProcessor.Thresholds audioThresholds() {
        return audioProcessor.thresholds();
    }

    /** 音声区間判定閾値を次の VAD フレームから反映する。 */
    void setAudioThresholds(AudioProcessor.Thresholds thresholds) {
        audioProcessor.setThresholds(thresholds);
    }

    /** Group 設定の保存後に、次の LLM 呼び出しから使用するモデルを切り替えます。 */
    void setLanguageModel(LLM llm, PromptTemplates promptTemplates) {
        this.languageModel = new LlmContext(llm, promptTemplates);
    }

    /** ChatGroup が管理するイベント用の連番を取得します。 */
    public int nextId() {
        return this.chatGroup.nextId();
    }
    /** ChatGroup の executor でタスクを実行します。 */
    public void execute(Runnable r) {
        this.chatGroup.execute(r);
    }
    /** ChatGroup の executor へ結果を持たないタスクを投入します。 */
    public Future<?> submit(Runnable task) {
        return this.chatGroup.submit(task);
    }
    /** ChatGroup の executor へタスクを投入し、完了時に指定した結果を返します。 */
    public <T> Future<T> submit(Runnable task, T result ) {
        return this.chatGroup.submit(task,result);
    }
    /** ChatGroup の executor へ結果を返すタスクを投入します。 */
    public <T> Future<T> submit(Callable<T> task) {
        return this.chatGroup.submit(task);
    }

    /** SSE ハンドラが待機するイベント待ち行列を返します。 */
    public LinkedBlockingQueue<ServerEvent> events() {
        return events;
    }

    /** リクエスト種別に応じて、音声処理・テキスト応答・イベント配信へ振り分けます。 */
    public void handle(ChatRequest request) {
        if ("audio".equals(request.type())) {
            handleAudio(request);
            return;
        }
        if ("text".equals(request.type())) {
            handleText(request);
            return;
        }
        sendToGroup(request.toEvent());
    }

    /** 空白だけの本文を除外して、テキスト入力への assistant 応答を開始します。 */
    private void handleText(ChatRequest request) {
        String text = request.textBody().trim();
        if (!text.isBlank()) {
            startAssistantReply(text);
        }
    }

    /** クライアント時刻を持たない互換入力を、未指定サンプル位置として処理します。 */
    private void handleAudio(ChatRequest request) {
        handleAudio(request, Long.MIN_VALUE, Long.MIN_VALUE);
    }

    /**
     * PCM/VAD/RMS の 1 チャンクを受け付けます。
     * バイト配列を複製してからキューへ入れるため、HTTP リクエストのバッファが解放されても
     * 非同期処理が参照する内容は変化しません。
     */
    public void handleAudio(ChatRequest request, long clientStartSampleIndex, long clientEndSampleIndexExclusive) {
        if (!request.isPcmVadAudio()) {
            throw new HttpRequestException(400,
                    "unsupported audio content type. Use: audio/pcm-vad; rate=16000; channels=1; format=s16le; vad-frame-samples=256");
        }
        byte[] body = request.body().clone();
        byte[] vadBytes = request.vadBytes().clone();
        byte[] rmsBytes = request.rmsBytes().clone();
        synchronized (audioTaskLock) {
            audioTaskTail = audioTaskTail.handle((ignored, error) -> null)
                    .thenRunAsync(
                            () -> processAudio(body, vadBytes, rmsBytes, clientStartSampleIndex, clientEndSampleIndexExclusive),
                            this::execute);
        }
    }

    /** 直列化済みの音声チャンクを STT へ渡し、認識結果を処理します。 */
    private void processAudio(
            byte[] body,
            byte[] vadBytes,
            byte[] rmsBytes,
            long clientStartSampleIndex,
            long clientEndSampleIndexExclusive) {
        if (isClosed()) {
            return;
        }
        try {
            AudioDiagnostics.log("audio-chunk-process", diagnosticsContext, Json.fields(
                    "startSampleIndex", clientStartSampleIndex == Long.MIN_VALUE ? null : clientStartSampleIndex,
                    "endSampleIndexExclusive", clientEndSampleIndexExclusive == Long.MIN_VALUE ? null : clientEndSampleIndexExclusive,
                    "pcmBytes", body.length,
                    "vadBytes", vadBytes.length,
                    "rmsBytes", rmsBytes.length));
            Optional<TranscriptionResult> result = audioProcessor.acceptPcm16LeWithVadDetailed(body, vadBytes, rmsBytes);
            if (isClosed()) {
                return;
            }
            result.ifPresent(this::handleTranscriptionResult);
        } catch (IllegalArgumentException e) {
            sendAudioProcessingFailure(e);
        } catch (SpeechToTextException e) {
            sendAudioProcessingFailure(e);
        } catch (OnnxModelException e) {
            sendAudioProcessingFailure(e);
        } catch (RuntimeException e) {
            sendAudioProcessingFailure(e);
        }
    }

    /**
     * ブラウザの再生状態を受け取り、再生済み chunk を会話履歴へ確定します。
     * recognized=true の報告がない chunk は履歴へ保存しません。
     */
    public void handlePlayback(PlaybackEvent playbackEvent) {
        AudioDiagnostics.log("playback-report", diagnosticsContext, Json.fields(
                "assistantTurnId", playbackEvent.assistantTurnId(),
                "state", playbackEvent.state(),
                "clientMicSampleIndex", playbackEvent.clientMicSampleIndex()));
        synchronized (playbackControlLock) {
            String state = playbackEvent.state();
            if ("start".equals(state) || "resume".equals(state)) {
                activePlaybackAssistantTurnId = playbackEvent.assistantTurnId();
                activePlaybackStartSampleIndex = Math.max(0, playbackEvent.clientMicSampleIndex());
                return;
            }
            if ("pause".equals(state) || "stop".equals(state) || "end".equals(state) || "cancel".equals(state)) {
                finishPlaybackTracking(playbackEvent.assistantTurnId(), playbackEvent.clientMicSampleIndex());
            }
        }
        if (playbackEvent.recognized()) {
            rememberRecognizedChunk(playbackEvent.assistantTurnId(), playbackEvent.chunkId());
        }
    }

    /**
     * 顔トラックの入退室を FaceDB へ反映し、ブラウザと LLM 向けの認識結果を生成します。
     * ブラウザの trackId は一時的なため、FaceDB 用の trackId に変換して扱います。
     */
    public FaceEventResult handleFacePresence(FaceDB faceDB,FaceEventRequest request ) throws IOException {
        if ("person-left".equals(request.eventType())) {
            String faceDbTrackId;
            synchronized (faceTrackLock) {
                faceDbTrackId = this.trackmap.remove(request.trackId());
            }
            faceDB.finish(faceDbTrackId);
            FaceEventResult result = FaceEventResult.left().withTrackId(request.trackId());
            handleFacePresence(result, faceDbTrackId);
            return result;
        }
        String faceDbTrackId;
        synchronized (faceTrackLock) {
            faceDbTrackId = this.trackmap.get(request.trackId());
            if( faceDbTrackId==null) {
                faceDbTrackId = faceDB.createTrackId();
                this.trackmap.put(request.trackId(),faceDbTrackId);
            }
        }
        FacePossibility registered = faceDB.register(faceDbTrackId,request.descriptor(), request.imageDataUrl());
        FacePossibility.PersonPossibility nearest = registered.nearest();
        FaceEventResult result;
        if (nearest == null) {
            result = new FaceEventResult(
                    "accepted",
                    "",
                    "",
                    null,
                    false,
                    registered.sampleId,
                    request.presenceState(),
                    request.trackId());
        } else {
            result = new FaceEventResult(
                "accepted",
                nearest.personId,
                nearest.name,
                (double) nearest.distance,
                true,
                registered.sampleId,
                request.presenceState(),
                request.trackId());
        }
        handleFacePresence(result, faceDbTrackId);
        return result;
    }

    /**
     * ブラウザ向けイベントにはブラウザの trackId を残し、LLM には FaceDB の trackId を通知します。
     */
    private void handleFacePresence(FaceEventResult result, String faceDbTrackId) {
        boolean finalPersonLeft;
        synchronized (conversationLock) {
            if ("person-left".equals(result.presenceState())) {
                activeFaceTracks.remove(result.trackId());
            } else if ("person-entered".equals(result.presenceState())) {
                activeFaceTracks.put(result.trackId(), result);
            }
            finalPersonLeft = "person-left".equals(result.presenceState()) && activeFaceTracks.isEmpty();
        }
        Message faceEventMessage = null;
        if( finalPersonLeft ) {
            faceEventMessage = new Message(Role.System, languageModel.promptTemplates().personLeft(result.personName(), faceDbTrackId));
            faceEventMessage.meta(FACE_MARKER, FACE_LOST);
        } else if ("person-entered".equals(result.presenceState()) ) {
            String trackId = faceDbTrackId == null || faceDbTrackId.isBlank() ? "" : faceDbTrackId;
            boolean known = result.known() && result.personName() != null && !result.personName().isBlank();
            if( known ) {
                faceEventMessage = new Message(Role.System, languageModel.promptTemplates().faceMessage(result.personName(), trackId));
                faceEventMessage.meta(FACE_MARKER,FACE_KNOWN);
            } else {
                faceEventMessage = new Message(Role.System, languageModel.promptTemplates().faceMessage(trackId));
                faceEventMessage.meta(FACE_MARKER,FACE_UNKNOWN);
            }
        }
        if( faceEventMessage != null ) {
            synchronized (conversationLock) {
                conversationHistory.add(faceEventMessage);
                trimConversationHistory();
            }
        }
        sendToGroupIfOpen(ServerEvent.facePresence(result));
        if ("person-entered".equals(result.presenceState()) && faceEventMessage != null) {
            startAssistantReplyFromHistory(faceEventMessage, result.known() && result.personName() != null && !result.personName().isBlank());
        }
    }
    /** テスト用に、ロックで保護された会話履歴の不変コピーを返します。 */
    List<Message> conversationHistoryForTest() {
        synchronized (conversationLock) {
            return List.copyOf(conversationHistory);
        }
    }

    /** 対象の再生が開始位置以降で終了した場合だけ、再生追跡状態を解除します。 */
    private void finishPlaybackTracking(long assistantTurnId, long clientMicSampleIndex) {
        if (activePlaybackStartSampleIndex == Long.MIN_VALUE
                || activePlaybackAssistantTurnId != assistantTurnId
                || clientMicSampleIndex < activePlaybackStartSampleIndex) {
            return;
        }
        activePlaybackStartSampleIndex = Long.MIN_VALUE;
    }

    /** 音声認識開始時に、現在の assistant 音声を pause して最終認識結果を待機します。 */
    private void handleTranscriptionStarted(TranscriptionStarted started) {
        ServerEvent event = null;
        synchronized (playbackControlLock) {
            if (sttWaitActive && sttWaitSpeechSequenceId == started.speechSequenceId()) {
                return;
            }
            if (currentAssistantTurnId <= 0) {
                return;
            }
            currentInterruptionId++;
            sttWaitActive = true;
            sttWaitAssistantTurnId = currentAssistantTurnId;
            sttWaitSpeechSequenceId = started.speechSequenceId();
            event = ServerEvent.audioControl(
                    "pause",
                    sttWaitAssistantTurnId,
                    currentInterruptionId,
                    started.speechSequenceId(),
                    "stt-wait");
        }
        AudioDiagnostics.log("audio-control-send", diagnosticsContext, Json.fields(
                "action", "pause",
                "assistantTurnId", event == null ? null : sttWaitAssistantTurnId,
                "interruptionId", currentInterruptionId,
                "speechSequenceId", started.speechSequenceId(),
                "reason", "stt-wait"));
        sendToGroupIfOpen(event);
    }

    /** AudioProcessor の発話状態変化をブラウザへ配信します。 */
    private void handleSpeechStateChange(SpeechStateChange change) {
        sendToGroupIfOpen(ServerEvent.speechState(
                change.previousState().name(),
                change.currentState().name(),
                change.speechSequenceId(),
                change.sampleIndex()));
    }

    /** 最終認識が空だった場合、STT 待機で pause した音声を resume します。 */
    private void resumePlaybackForEmptyTranscript(long speechSequenceId) {
        ServerEvent event = null;
        synchronized (playbackControlLock) {
            if (!sttWaitActive || sttWaitSpeechSequenceId != speechSequenceId) {
                return;
            }
            if (canceledAssistantTurnIds.contains(sttWaitAssistantTurnId)) {
                sttWaitActive = false;
                return;
            }
            event = ServerEvent.audioControl(
                    "resume",
                    sttWaitAssistantTurnId,
                    currentInterruptionId,
                    speechSequenceId,
                    "empty-stt");
            sttWaitActive = false;
        }
        AudioDiagnostics.log("audio-control-send", diagnosticsContext, Json.fields(
                "action", "resume",
                "assistantTurnId", event == null ? null : sttWaitAssistantTurnId,
                "interruptionId", currentInterruptionId,
                "speechSequenceId", speechSequenceId,
                "reason", "empty-stt"));
        sendToGroupIfOpen(event);
    }

    /**
     * 部分認識では表示更新と現在の応答の中断を行い、最終認識では新しい応答を開始します。
     * 部分認識と最終認識が重複する場合は、重複する文字列を除いて結合します。
     */
    private void handleTranscriptionResult(TranscriptionResult result) {
        String text = result.transcription().text();
        String transcript = text == null ? "" : text.trim();
        if (result.kind() == TranscriptionKind.PARTIAL) {
            if (!transcript.isBlank()) {
                String partialTranscript = rememberPartialTranscript(result.speechSequenceId(), transcript);
                sendToGroupIfOpen(ServerEvent.transcriptPartial(result.speechSequenceId(), partialTranscript));
                cancelCurrentAssistantTurnForTranscript(result.speechSequenceId());
            }
            return;
        }

        String finalTranscript = takeFinalTranscript(result.speechSequenceId(), transcript);
        if (finalTranscript.isBlank()) {
            resumePlaybackForEmptyTranscript(result.speechSequenceId());
            return;
        }

        cancelCurrentAssistantTurnForTranscript(result.speechSequenceId());
        startAssistantReply(finalTranscript);
    }

    /** 部分認識を発話シーケンス単位で統合し、古いシーケンスの結果を破棄します。 */
    private String rememberPartialTranscript(long speechSequenceId, String transcript) {
        synchronized (partialTranscriptLock) {
            partialTranscripts.keySet().removeIf(id -> id < speechSequenceId);
            String current = partialTranscripts.getOrDefault(speechSequenceId, "");
            String merged = mergeTranscriptText(current, transcript);
            partialTranscripts.put(speechSequenceId, merged);
            return merged;
        }
    }

    /** 保存済みの部分認識を取り出して最終認識と結合し、そのシーケンスを削除します。 */
    private String takeFinalTranscript(long speechSequenceId, String transcript) {
        synchronized (partialTranscriptLock) {
            String partial = partialTranscripts.remove(speechSequenceId);
            return mergeTranscriptText(partial == null ? "" : partial, transcript);
        }
    }

    /** 前回文字列の末尾と今回文字列の先頭の共通部分を 1 回だけにして結合します。 */
    private static String mergeTranscriptText(String current, String next) {
        String left = current == null ? "" : current.trim();
        String right = next == null ? "" : next.trim();
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty()) {
            return left;
        }
        int overlap = longestSuffixPrefixOverlap(left, right);
        return left + right.substring(overlap);
    }

    /** left の接尾辞と right の接頭辞が一致する最大文字数を返します。 */
    private static int longestSuffixPrefixOverlap(String left, String right) {
        int max = Math.min(left.length(), right.length());
        for (int length = max; length > 0; length--) {
            if (left.regionMatches(left.length() - length, right, 0, length)) {
                return length;
            }
        }
        return 0;
    }

    /** ユーザー発話が確定した時点で、競合する LLM/TTS/再生待ちの応答を中断します。 */
    private void cancelCurrentAssistantTurnForTranscript(long speechSequenceId) {
        ServerEvent event = null;
        Future<?> taskToCancel = null;
        long canceledTurnId;
        synchronized (playbackControlLock) {
            canceledTurnId = sttWaitActive && sttWaitSpeechSequenceId == speechSequenceId
                    ? sttWaitAssistantTurnId
                    : currentAssistantTurnId;
            if (canceledTurnId > 0) {
                canceledAssistantTurnIds.add(canceledTurnId);
                event = ServerEvent.audioControl(
                        "cancel",
                        canceledTurnId,
                        currentInterruptionId,
                        speechSequenceId,
                        "user-transcript");
            }
            if (activeAssistantTask != null) {
                taskToCancel = activeAssistantTask;
            }
            sttWaitActive = false;
            if (currentAssistantTurnId == canceledTurnId) {
                assistantTurnActive = false;
            }
        }
        if (event != null) {
            AudioDiagnostics.log("audio-control-send", diagnosticsContext, Json.fields(
                    "action", "cancel",
                    "assistantTurnId", canceledTurnId,
                    "interruptionId", currentInterruptionId,
                    "speechSequenceId", speechSequenceId,
                    "reason", "user-transcript"));
        }
        if (taskToCancel != null) {
            taskToCancel.cancel(true);
        }
        if (canceledTurnId > 0) {
            synchronized (conversationLock) {
                pendingAssistantChunks.keySet().removeIf(key -> key.assistantTurnId() == canceledTurnId);
            }
        }
        if (event != null) {
            sendToGroupIfOpen(event);
        }
    }

    /** ユーザー発話の認識テキストから、新しい assistant turn を開始します。 */
    private void startAssistantReply(String transcript) {
        long assistantTurnId = beginAssistantTurn();
        AudioDiagnostics.log("assistant-turn-start", diagnosticsContext, Json.fields(
                "assistantTurnId", assistantTurnId,
                "transcriptChars", transcript.length(),
                "transcript", transcript));
        FutureTask<Void> task = new FutureTask<>(() -> {
            replyToTranscript(transcript, assistantTurnId);
            return null;
        });
        startAssistantTask(assistantTurnId, task);
    }

    /** 人物入室など、履歴に追加済みのシステムメッセージを契機に assistant turn を開始します。 */
    private void startAssistantReplyFromHistory(Message triggerMessage, boolean knownPerson) {
        long assistantTurnId = beginAssistantTurn();
        AudioDiagnostics.log("assistant-turn-start", diagnosticsContext, Json.fields(
                "assistantTurnId", assistantTurnId,
                "trigger", "conversation-history",
                "triggerChars", triggerMessage.message().length(),
                "triggerText", triggerMessage.message()));
        FutureTask<Void> task = new FutureTask<>(() -> {
            replyToConversationHistory(triggerMessage, assistantTurnId, knownPerson);
            return null;
        });
        startAssistantTask(assistantTurnId, task);
    }

    /** 最新かつ未中断の turn だけを実行し、古い turn のタスクは開始前に取り消します。 */
    private void startAssistantTask(long assistantTurnId, FutureTask<Void> task) {
        synchronized (playbackControlLock) {
            if (currentAssistantTurnId == assistantTurnId && !canceledAssistantTurnIds.contains(assistantTurnId)) {
                activeAssistantTask = task;
            } else {
                task.cancel(true);
            }
        }
        execute(task);
    }

    /** ユーザー発話を保留し、既存履歴と合わせて LLM へ送るメッセージ列を組み立てます。 */
    private void replyToTranscript(String transcript, long assistantTurnId) {
        if (isClosed()) {
            return;
        }
        StreamingTextChunker chunker = new StreamingTextChunker();
        Message userMessage = new Message(Role.User, transcript);
        List<Message> requestMessages;
        synchronized (conversationLock) {
            rememberStalePendingUserMessages(assistantTurnId);
            pendingUserMessages.put(assistantTurnId, new PendingUserMessage(userMessage));
            requestMessages = requestMessages(userMessage);
        }
        replyWithMessages(assistantTurnId, chunker, userMessage, requestMessages, true, false, null);
    }

    /** 履歴に確定済みのシステムイベントを起点として、履歴全体に対する応答を生成します。 */
    private void replyToConversationHistory(Message triggerMessage, long assistantTurnId, boolean knownPerson) {
        if (isClosed()) {
            return;
        }
        StreamingTextChunker chunker = new StreamingTextChunker();
        List<Message> requestMessages;
        synchronized (conversationLock) {
            requestMessages = List.copyOf(conversationHistory);
        }
        replyWithMessages(assistantTurnId, chunker, triggerMessage, requestMessages, false, true, knownPerson);
    }

    /**
     * LLM のストリーミング出力を文単位の TTS chunk に変換して配信します。
     * 例外時は assistant 応答を履歴へ残さず、保留中のユーザー発話だけを確定します。
     */
    private void replyWithMessages(
            long assistantTurnId,
            StreamingTextChunker chunker,
            Message userMessage,
            List<Message> requestMessages,
            boolean publishUserMessage,
            boolean userMessageAlreadyInHistory,
            Boolean encounterKnownPerson) {
        try {
            if (publishUserMessage) {
                sendToGroupIfOpen(ServerEvent.userMessage(userMessage.message()));
            }
            sendToGroupIfOpen(ServerEvent.assistantState("LLM"));
            LlmContext currentLanguageModel = languageModel;
            PromptTemplates templates = currentLanguageModel.promptTemplates();
            String systemPrompt = templates.expandedSystemPrompt();
            // 
            int faceIdx = -1;
            PromptLoop: for( int idx=requestMessages.size()-1;idx>=0;idx--) {
                String mark = requestMessages.get(idx).meta(FACE_MARKER);
                if( mark!=null) switch(mark) {
                    case FACE_UNKNOWN:
                        systemPrompt = systemPrompt + "\n" +  templates.expandFirstMeetingPrompt();
                        faceIdx = idx;
                        break PromptLoop;
                    case FACE_ASSIGNED:
                        if(idx==requestMessages.size()-1) {
                            systemPrompt = systemPrompt + "\n" + templates.expandAssignedPrompt();
                        }
                        faceIdx = idx;
                        break PromptLoop;
                    case FACE_KNOWN:
                        if(idx==requestMessages.size()-1) {
                            systemPrompt = systemPrompt + "\n" +  templates.expandKnownPersonPrompt();
                        }
                        faceIdx = idx;
                        break PromptLoop;
                    case FACE_LOST:
                        faceIdx = idx;
                        break PromptLoop;
                    default:
                        break PromptLoop;
                }
            }
            List<Message> llmMessages = new ArrayList<>(requestMessages.size() + 1);
            if (!systemPrompt.isBlank()) {
                llmMessages.add(new Message(Message.Role.System, systemPrompt));
            }
            for( int idx=0,n=requestMessages.size(); idx<n; idx++ ) {
                Message m = requestMessages.get(idx);
                if( m.meta(FACE_MARKER)==null || idx==faceIdx ) {
                    llmMessages.add( m.strip() );
                }
            }
            currentLanguageModel.llm().call(llmMessages, List.of(new PersonTool(assistantTurnId)), delta -> {
                if (!isAssistantTurnActive(assistantTurnId)) {
                    return;
                }
                AudioDiagnostics.log("llm-message-delta", diagnosticsContext, Json.fields(
                        "assistantTurnId", assistantTurnId,
                        "deltaChars", delta.length()));
                speak(assistantTurnId, userMessage, userMessageAlreadyInHistory, chunker.append(delta));
            });
            speak(assistantTurnId, userMessage, userMessageAlreadyInHistory, chunker.finish());
            if (!isAssistantTurnActive(assistantTurnId)) {
                return;
            }
            AudioDiagnostics.log("assistant-turn-message-done", diagnosticsContext, Json.fields(
                    "assistantTurnId", assistantTurnId));
            sendToGroupIfOpen(ServerEvent.messageDone());
            finishAssistantTurn(assistantTurnId);
        } catch (LanguageModelException e) {
            rememberUserMessageWithoutAssistant(assistantTurnId);
            if (isAssistantTurnActive(assistantTurnId)) {
                sendToGroupIfOpen(ServerEvent.system("llm request failed: " + e.getMessage()));
            }
            finishAssistantTurn(assistantTurnId);
        } catch (TextToSpeechException e) {
            rememberUserMessageWithoutAssistant(assistantTurnId);
            if (isAssistantTurnActive(assistantTurnId)) {
                sendToGroupIfOpen(ServerEvent.system("tts request failed: " + e.getMessage()));
            }
            finishAssistantTurn(assistantTurnId);
        } finally {
            sendToGroupIfOpen(ServerEvent.assistantState("IDLE"));
        }
    }

    /** assistant turn の連番を進め、以前の実行タスク参照を初期化します。 */
    private long beginAssistantTurn() {
        synchronized (playbackControlLock) {
            currentAssistantTurnId++;
            assistantTurnActive = true;
            activeAssistantTask = null;
            return currentAssistantTurnId;
        }
    }

    /** 指定した turn が最新かつ未中断なら、応答生成の完了状態を記録します。 */
    private void finishAssistantTurn(long assistantTurnId) {
        synchronized (playbackControlLock) {
            if (currentAssistantTurnId == assistantTurnId && !canceledAssistantTurnIds.contains(assistantTurnId)) {
                assistantTurnActive = false;
                activeAssistantTask = null;
            }
        }
    }

    /** 指定した turn が最新で、中断および接続終了されていないかを判定します。 */
    private boolean isAssistantTurnActive(long assistantTurnId) {
        synchronized (playbackControlLock) {
            return currentAssistantTurnId == assistantTurnId
                    && !canceledAssistantTurnIds.contains(assistantTurnId)
                    && !closed;
        }
    }

    /** 確定済み履歴の末尾に今回のユーザー発話を追加した、LLM リクエスト用の不変リストを返します。 */
    private List<Message> requestMessages(Message userMessage) {
        List<Message> messages = new ArrayList<>(conversationHistory.size() + 1);
        messages.addAll(conversationHistory);
        messages.add(userMessage);
        return List.copyOf(messages);
    }

    /** 再生確認済みの chunk を履歴へ追加し、同じ turn の assistant 文は 1 件へ結合します。 */
    private void rememberRecognizedChunk(long assistantTurnId, long chunkId) {
        PendingAssistantChunk chunk;
        synchronized (conversationLock) {
            chunk = pendingAssistantChunks.remove(new AssistantChunkKey(assistantTurnId, chunkId));
            if (chunk == null || chunk.text().isBlank()) {
                return;
            }
            rememberUserMessageForRecognizedAssistant(assistantTurnId, chunk);
            rememberAssistantChunk(assistantTurnId, chunk.text());
            trimConversationHistory();
        }
        AudioDiagnostics.log("assistant-chunk-recognized", diagnosticsContext, Json.fields(
                "assistantTurnId", assistantTurnId,
                "chunkId", chunkId,
                "textChars", chunk.text().length()));
    }

    /** この turn の最初の再生確認時だけ、対応するユーザー発話を履歴へ確定します。 */
    private void rememberUserMessageForRecognizedAssistant(long assistantTurnId, PendingAssistantChunk chunk) {
        if (chunk.userMessageAlreadyInHistory() || rememberedUserAssistantTurnIds.contains(assistantTurnId)) {
            return;
        }
        PendingUserMessage pendingUserMessage = pendingUserMessages.remove(assistantTurnId);
        Message userMessage = pendingUserMessage == null ? chunk.userMessage() : pendingUserMessage.userMessage();
        conversationHistory.add(userMessage);
        rememberedUserAssistantTurnIds.add(assistantTurnId);
    }

    /** LLM/TTS の失敗時に、assistant 応答なしでユーザー発話だけを履歴へ確定します。 */
    private void rememberUserMessageWithoutAssistant(long assistantTurnId) {
        synchronized (conversationLock) {
            PendingUserMessage pendingUserMessage = pendingUserMessages.remove(assistantTurnId);
            if (pendingUserMessage == null || rememberedUserAssistantTurnIds.contains(assistantTurnId)) {
                return;
            }
            conversationHistory.add(pendingUserMessage.userMessage());
            rememberedUserAssistantTurnIds.add(assistantTurnId);
            trimConversationHistory();
        }
    }

    /** 新しい発話開始時に、古い turn の再生未確認ユーザー発話を単独で履歴へ確定します。 */
    private void rememberStalePendingUserMessages(long currentAssistantTurnId) {
        List<Long> staleAssistantTurnIds = pendingUserMessages.keySet().stream()
                .filter(assistantTurnId -> assistantTurnId < currentAssistantTurnId)
                .toList();
        for (long assistantTurnId : staleAssistantTurnIds) {
            PendingUserMessage pendingUserMessage = pendingUserMessages.remove(assistantTurnId);
            if (pendingUserMessage != null && !rememberedUserAssistantTurnIds.contains(assistantTurnId)) {
                pendingAssistantChunks.keySet().removeIf(key -> key.assistantTurnId() == assistantTurnId);
                conversationHistory.add(pendingUserMessage.userMessage());
                rememberedUserAssistantTurnIds.add(assistantTurnId);
            }
        }
        trimConversationHistory();
    }

    /** 同一 assistant turn の再生確認済みテキストを、履歴内の 1 メッセージへ連結します。 */
    private void rememberAssistantChunk(long assistantTurnId, String text) {
        Integer historyIndex = rememberedAssistantMessageIndexes.get(assistantTurnId);
        if (historyIndex == null || historyIndex < 0 || historyIndex >= conversationHistory.size()) {
            conversationHistory.add(new Message(Role.Assistant, text));
            rememberedAssistantMessageIndexes.put(assistantTurnId, conversationHistory.size() - 1);
            return;
        }
        Message currentMessage = conversationHistory.get(historyIndex);
        if (Message.Role.Assistant!=currentMessage.role()) {
            conversationHistory.add(new Message(Role.Assistant, text));
            rememberedAssistantMessageIndexes.put(assistantTurnId, conversationHistory.size() - 1);
            return;
        }
        conversationHistory.set(historyIndex, new Message(Role.Assistant, currentMessage.message() + text));
    }

    /**
     * 人物名の登録成功を system メッセージとして会話履歴へ確定します。
     * 登録完了通知用テンプレートを使い、${USER_NAME}、${FACE_ID}、${BOT_NAME}、${DATETIME}
     * を Group ごとの設定に従って展開します。
     */
    private void rememberFaceNameAssignment(String faceDbTrackId, String name) {
        String notification = languageModel.promptTemplates().assignedPersonMessage(name, faceDbTrackId);
        if (notification.isBlank()) {
            return;
        }
        Message m = new Message(Role.System, notification);
        m.meta(FACE_MARKER, FACE_ASSIGNED);
        synchronized (conversationLock) {
            conversationHistory.add(m);
            trimConversationHistory();
        }
    }

    /** 履歴件数を上限以内にし、削除した先頭件数に合わせて assistant の履歴位置を補正します。 */
    private void trimConversationHistory() {
        int removedCount = 0;
        while (conversationHistory.size() > MAX_HISTORY_MESSAGES) {
            conversationHistory.removeFirst();
            removedCount++;
        }
        if (removedCount == 0) {
            return;
        }
        int removed = removedCount;
        rememberedAssistantMessageIndexes.entrySet().removeIf(entry -> entry.getValue() < removed);
        rememberedAssistantMessageIndexes.replaceAll((assistantTurnId, index) -> index - removed);
    }

    /**
     * テキスト chunk を音声へ変換して配信します。
     * 配信直前に保留情報を記録し、ブラウザからの recognized 報告で履歴へ確定できるようにします。
     */
    private void speak(
            long assistantTurnId,
            Message userMessage,
            boolean userMessageAlreadyInHistory,
            Iterable<String> chunks) {
        for (String chunk : chunks) {
            if (!isAssistantTurnActive(assistantTurnId)) {
                return;
            }
            long chunkId = nextAssistantChunkId();
            List<AudioDelta> audioDeltas = new ArrayList<>();
            AudioDiagnostics.log("tts-chunk-start", diagnosticsContext, Json.fields(
                    "assistantTurnId", assistantTurnId,
                    "chunkId", chunkId,
                    "textChars", chunk.length(),
                    "text", chunk));
            sendToGroupIfOpen(ServerEvent.assistantState("TTS"));
            if (StreamingTextChunker.hasSpeechText(chunk)) {
                textToSpeech.synthesizeStreaming(chunk, audio -> {
                    if (isAssistantTurnActive(assistantTurnId)) {
                        audioDeltas.add(audio);
                        AudioDiagnostics.log("tts-audio-delta", diagnosticsContext, Json.fields(
                                "assistantTurnId", assistantTurnId,
                                "chunkId", chunkId,
                                "deltaIndex", audioDeltas.size(),
                                "base64Chars", audio.data().length(),
                                "format", audio.format(),
                                "sampleRate", audio.sampleRate()));
                    }
                });
            }
            if (!isAssistantTurnActive(assistantTurnId)) {
                return;
            }
            double durationSeconds = audioDurationSeconds(audioDeltas);
            synchronized (conversationLock) {
                pendingAssistantChunks.put(
                        new AssistantChunkKey(assistantTurnId, chunkId),
                        new PendingAssistantChunk(userMessage, userMessageAlreadyInHistory, chunk));
            }
            sendToGroupIfOpen(ServerEvent.assistantAudioChunk(
                    assistantTurnId,
                    chunkId,
                    chunk,
                    audioDeltas,
                    durationSeconds));
            AudioDiagnostics.log("tts-chunk-done", diagnosticsContext, Json.fields(
                    "assistantTurnId", assistantTurnId,
                    "chunkId", chunkId,
                    "audioDeltaCount", audioDeltas.size(),
                    "audioBase64Chars", audioDeltas.stream().mapToLong(audio -> audio.data().length()).sum(),
                    "audioDurationSeconds", durationSeconds));
            if (isAssistantTurnActive(assistantTurnId)) {
                sendToGroupIfOpen(ServerEvent.assistantState("LLM"));
            }
        }
    }

    /** 再生確認と音声データを対応付ける chunkId を採番します。 */
    private long nextAssistantChunkId() {
        synchronized (playbackControlLock) {
            return ++nextAssistantChunkId;
        }
    }

    /** PCM 音声のバイト数とサンプルレートから、配信音声の合計時間を秒で算出します。 */
    private static double audioDurationSeconds(List<AudioDelta> audioDeltas) {
        double seconds = 0;
        for (AudioDelta audio : audioDeltas) {
            if ("pcm".equals(audio.format())) {
                int byteLength = java.util.Base64.getDecoder().decode(audio.data()).length;
                seconds += (byteLength / 2.0) / audio.sampleRate();
            }
        }
        return seconds;
    }

    /** 音声認識または音声区間判定の失敗を診断ログと system イベントで通知します。 */
    private void sendAudioProcessingFailure(RuntimeException e) {
        AudioDiagnostics.log("audio-processing-error", diagnosticsContext, Json.fields(
                "errorClass", e.getClass().getName(),
                "errorMessage", e.getMessage()));
        sendToGroupIfOpen(ServerEvent.system("audio processing failed: " + e.getMessage()));
    }

    /** 接続が開いている場合だけ、イベントを所属 ChatGroup へ配信します。 */
    private void sendToGroupIfOpen(ServerEvent event) {
        synchronized (lifecycleLock) {
            if (!closed) {
                sendToGroup(event);
            }
        }
    }

    /** 所属 ChatGroup の全接続クライアントへイベントを配信します。 */
    private void sendToGroup(ServerEvent event) {
        chatGroup.publish(event);
    }

    /** ChatGroup から配信されたイベントを、このクライアントの SSE 待ち行列へ追加します。 */
    public void receive(ServerEvent event) {
        events.offer(event);
    }

    /** 接続終了を理由として、このクライアントの処理を停止します。 */
    public void close() {
        close("client-close");
    }

    /** 接続を閉じ、進行中の音声認識・応答生成・再生待ち状態をすべて解除します。 */
    void close(String reason) {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
        }
        cancelActiveWorkNow(reason);
    }

    /**
     * 停止操作で、STT、LLM、TTS、再生待ちの応答をまとめてキャンセルする。
     */
    /** 接続は維持したまま、進行中の STT・LLM・TTS・再生待ちを停止します。 */
    public void cancelActiveWork(String reason) {
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
        }
        cancelActiveWorkNow(reason);
    }

    /** ロック取得済みまたは close 済みでも実行できる、処理状態の一括初期化本体です。 */
    private void cancelActiveWorkNow(String reason) {
        Future<?> taskToCancel;
        long canceledTurnId;
        synchronized (playbackControlLock) {
            canceledTurnId = currentAssistantTurnId;
            if (canceledTurnId > 0) {
                canceledAssistantTurnIds.add(canceledTurnId);
            }
            taskToCancel = activeAssistantTask;
            activeAssistantTask = null;
            assistantTurnActive = false;
            sttWaitActive = false;
            activePlaybackStartSampleIndex = Long.MIN_VALUE;
        }
        audioProcessor.cancelTranscriptions();
        synchronized (partialTranscriptLock) {
            partialTranscripts.clear();
        }
        synchronized (conversationLock) {
            pendingAssistantChunks.clear();
            pendingUserMessages.clear();
            rememberedUserAssistantTurnIds.clear();
            rememberedAssistantMessageIndexes.clear();
        }
        if (taskToCancel != null) {
            taskToCancel.cancel(true);
        }
        AudioDiagnostics.log("client-work-cancel", diagnosticsContext, Json.fields(
                "reason", reason,
                "assistantTurnId", canceledTurnId > 0 ? canceledTurnId : null));
    }

    /** 接続終了済みかを、ライフサイクルロック下で判定します。 */
    private boolean isClosed() {
        synchronized (lifecycleLock) {
            return closed;
        }
    }

    /** SmartTurn V3 モデルの絶対正規化パスを返します。 */
    private static Path smartTurnModelPath() {
        return SmartTurnV3ModelDownloader.MODEL_PATH.toAbsolutePath().normalize();
    }

    /** 本番用の LFM2 Audio TTS 実装を生成します。 */
    private static TextToSpeech defaultTextToSpeech() {
        return new Lfm2AudioTextToSpeech();
    }

    /** 1 回の応答で LLM とシステムプロンプトの組み合わせを固定する不変値です。 */
    private record LlmContext(LLM llm, PromptTemplates promptTemplates) {
        private LlmContext {
            if (llm == null) {
                throw new IllegalArgumentException("llm must not be null");
            }
            if (promptTemplates == null) throw new IllegalArgumentException("promptTemplates must not be null");
        }
    }

    /** ブラウザが送信する、assistant 音声 chunk の再生状態と再生確認情報です。 */
    public record PlaybackEvent(
            long assistantTurnId,
            long chunkId,
            String state,
            boolean recognized,
            double playedSeconds,
            double durationSeconds,
            long clientMicSampleIndex) {
        public PlaybackEvent(long assistantTurnId, String state, long clientMicSampleIndex) {
            this(assistantTurnId, 0, state, false, 0, 0, clientMicSampleIndex);
        }

        public PlaybackEvent {
            if (state == null || state.isBlank()) {
                throw new IllegalArgumentException("playback state is required");
            }
            state = state.trim().toLowerCase();
        }
    }

    /** 再生確認待ち assistant chunk を一意に特定する複合キーです。 */
    private record AssistantChunkKey(long assistantTurnId, long chunkId) {
    }

    /** assistant 音声の再生確認まで会話履歴への追加を保留するユーザー発話です。 */
    private record PendingUserMessage(Message userMessage) {
    }

    /** 再生確認まで保留する assistant chunk と、その生成元ユーザー発話です。 */
    private record PendingAssistantChunk(Message userMessage, boolean userMessageAlreadyInHistory, String text) {
    }

    /** LLM が人物名を確定するために呼び出すツールの、このクライアント用実装です。 */
    private class PersonTool extends PersonToolABC {
        private final long assistantTurnId;
        /** 呼び出し元の assistant turn を記録してツール実行の有効性を判定します。 */
        public PersonTool( long assistantTurnId ) {
            super();
            this.assistantTurnId = assistantTurnId;
        }

        @Override
        /** 中断済み turn からの人物名更新を防ぎます。 */
        protected boolean isAssistantTurnActive() {
            return ChatClient.this.isAssistantTurnActive(assistantTurnId);
        }

        @Override
        /** FaceDB の trackId に人物名を割り当てます。 */
        protected void assignFaceName(String trackId, String name) {
            ChatClient.this.chatGroup.assignFaceName(trackId, name);
            ChatClient.this.rememberFaceNameAssignment(trackId, name);
        }
        @Override
        /** 人物名割り当てツールの実行結果を音声診断ログへ記録します。 */
        protected void diag(String callId, String trackId, String name, String status, String reason, Throwable error) {
            if( ChatClient.this.diagnosticsContext!=null ) {
                Map<String,Object> jsonObject = Json.fields(
                    "callId", callId,
                    "trackId", trackId,
                    "name", name,
                    "status", status
                );
                if( reason!=null && reason.length()>0 ) {
                    jsonObject.put("reason",reason);
                }
                if( error!=null ) {
                    jsonObject.put("error", error.getMessage() );
                }
                AudioDiagnostics.log("face-name-assigned", diagnosticsContext, jsonObject);
            }
        }
    }
}
