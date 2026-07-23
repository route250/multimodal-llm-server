import createVADModule from "/vendor/tenvad/ten_vad.js";

export class ChatAudio {
    constructor(options) {
        this.group = options.group;
        this.sessionId = options.sessionId;
        this.clientLog = options.clientLog || (() => {});
        this.onSystem = options.onSystem || (() => {});
        this.onSpeechStateDisplay = options.onSpeechStateDisplay || (() => {});
        this.onAssistantText = options.onAssistantText || (() => null);
        this.onAssistantTextRemove = options.onAssistantTextRemove || (() => {});
        this.onAssistantSubtitle = options.onAssistantSubtitle || (() => {});
        this.onAssistantSubtitleDone = options.onAssistantSubtitleDone || (() => {});
        this.onMicStatus = options.onMicStatus || (() => {});
        this.onMicRecording = options.onMicRecording || (() => {});
        this.onAudioMetrics = options.onAudioMetrics || (() => {});
        this.targetSampleRate = 16000;
        this.tenVadHopSamples = 256;
        this.tenVadThreshold = 0.5;
        this.pcmVadHeaderBytes = 32;
        this.pcmVadContentType = "audio/pcm-vad; rate=16000; channels=1; format=s16le; vad-frame-samples=256";
        this.sendSampleThreshold = this.tenVadHopSamples * 12;
        this.localPauseVadThreshold = 50;
        this.localPauseConsecutiveVadFrames = 3;
        this.localResumeVadThreshold = 35;
        this.localResumeSilenceVadFrames = 8;
        this.audioContext = null;
        this.micStream = null;
        this.standbyMicStream = null;
        this.micSource = null;
        this.micProcessor = null;
        this.resampler = null;
        this.tenVadModule = null;
        this.tenVadHandlePtr = 0;
        this.tenVadHandle = 0;
        this.tenVadAudioPtr = 0;
        this.tenVadProbabilityPtr = 0;
        this.tenVadFlagPtr = 0;
        this.pendingSamples = [];
        this.pendingSampleCount = 0;
        this.pendingStartSampleIndex = 0;
        this.clientMicSampleIndex = 0;
        this.loudVadFrameCount = 0;
        this.quietVadFrameCount = 0;
        this.audioPostInitialRetryDelayMs = 1000;
        this.audioPostMaxRetryDelayMs = 10000;
        this.audioPostRetryDelayMs = 0;
        this.audioPostBlockedUntilMs = 0;
        this.postQueue = Promise.resolve();
        this.playbackContext = null;
        this.queuedAudioDeltas = [];
        this.currentPlayback = null;
        this.pausedPlayback = null;
        this.localVadPlaybackPaused = false;
        this.serverSttPlaybackPaused = false;
        this.speechDetectionState = "UNDETECTED";
        this.assistantPipelineState = "IDLE";
        this.activeAssistantTurnId = 0;
        this.canceledAssistantTurnIds = new Set();
    }

    statusFields() {
        return {
            activeAssistantTurnId: this.activeAssistantTurnId,
            clientMicSampleIndex: this.clientMicSampleIndex,
            queuedAudioDeltas: this.queuedAudioDeltas.length,
            currentPlayback: Boolean(this.currentPlayback),
            pausedPlayback: Boolean(this.pausedPlayback),
            localVadPlaybackPaused: this.localVadPlaybackPaused,
            serverSttPlaybackPaused: this.serverSttPlaybackPaused,
            playbackReady: Boolean(this.isPlaybackReady()),
            audioContextState: this.playbackContext ? this.playbackContext.state : "none",
            audioPostRetryDelayMs: this.audioPostRetryDelayMs
        };
    }

    /**
     * 新しいチャット接続で assistantTurnId を 1 から受け入れられるように、
     * 切断済みの前セッションに属するターン追跡状態を初期化する。
     */
    resetAssistantTurnTracking() {
        this.activeAssistantTurnId = 0;
        this.canceledAssistantTurnIds.clear();
        this.localVadPlaybackPaused = false;
        this.serverSttPlaybackPaused = false;
        this.clientLog("assistant-turn-tracking-reset");
    }

    isMicrophoneActive() {
        return Boolean(this.audioContext);
    }

    // 顔処理の開始を遅延するため、ローカル VAD とサーバ VAD の発話状態を公開する。
    isSpeechActive() {
        return new Set(["DETECTED", "TRAILING_SILENCE", "TURN_DETECTING", "TRANSCRIBING"])
            .has(this.speechDetectionState);
    }

    // 現在の再生実装は AudioBufferSourceNode を即時開始するため、再生予定時刻との差は常に 0ms である。
    // 将来 start(when) による予約再生へ変更した場合は、この値を予定時刻と currentTime の差へ置き換える。
    playbackScheduleGapMs() {
        return 0;
    }

    // 許可済みマイクを待機状態で保持し、必要になるまで音声入力を無効化する。
    setStandbyMicrophone(stream) {
        if (this.audioContext) {
            throw new Error("microphone is already active");
        }
        this.standbyMicStream = stream;
        this.standbyMicStream.getAudioTracks().forEach((track) => {
            track.enabled = false;
        });
    }

    // カメラ停止時に、待機中または利用中のマイク参照を破棄する。
    releaseStandbyMicrophone() {
        if (this.micStream === this.standbyMicStream) {
            this.stopMicrophone();
        }
        this.standbyMicStream = null;
    }

    async startMicrophone() {
        if (this.audioContext) {
            return;
        }
        this.onMicStatus("requesting microphone");
        try {
            await this.preparePlayback();
            await this.ensureTenVadReady();
            this.micStream = this.standbyMicStream || await navigator.mediaDevices.getUserMedia({
                audio: {
                    channelCount: 1,
                    sampleRate: { ideal: this.targetSampleRate },
                    echoCancellation: true,
                    echoCancellationType: "system",
                    noiseSuppression: true,
                    autoGainControl: false
                }
            });
            this.micStream.getAudioTracks().forEach((track) => {
                track.enabled = true;
            });
            this.audioContext = new AudioContext();
            await this.audioContext.resume();
            this.resampler = this.createResampler(this.audioContext.sampleRate, this.targetSampleRate);
            this.micSource = this.audioContext.createMediaStreamSource(this.micStream);
            this.micProcessor = this.audioContext.createScriptProcessor(4096, 1, 1);
            this.micProcessor.onaudioprocess = (event) => {
                const input = event.inputBuffer.getChannelData(0);
                this.enqueueSamples(this.resampler(input));
            };
            this.micSource.connect(this.micProcessor);
            this.micProcessor.connect(this.audioContext.destination);
            this.onMicRecording(true);
            this.onMicStatus(this.microphoneStatus());
        } catch (error) {
            this.onMicStatus(`microphone error: ${error.message}`);
            this.stopMicrophone();
        }
    }

    toggleMicrophone() {
        if (this.audioContext) {
            this.stopMicrophone();
            return;
        }
        this.startMicrophone();
    }

    stopMicrophone() {
        if (this.micProcessor) {
            this.micProcessor.disconnect();
            this.micProcessor.onaudioprocess = null;
            this.micProcessor = null;
        }
        if (this.micSource) {
            this.micSource.disconnect();
            this.micSource = null;
        }
        if (this.micStream) {
            if (this.micStream === this.standbyMicStream) {
                this.micStream.getAudioTracks().forEach((track) => {
                    track.enabled = false;
                });
            } else {
                this.micStream.getTracks().forEach((track) => track.stop());
            }
            this.micStream = null;
        }
        if (this.audioContext) {
            this.audioContext.close();
            this.audioContext = null;
        }
        try {
            this.flushSamples();
        } catch (error) {
            this.onMicStatus(`audio send error: ${error.message}`);
        }
        this.destroyTenVad();
        this.resampler = null;
        this.onMicRecording(false);
        this.onAudioMetrics({ vadProbability: 0, rms: 0 });
        this.updateSpeechState("UNDETECTED");
        this.onMicStatus("microphone stopped");
    }

    updateSpeechState(state) {
        this.speechDetectionState = state;
        this.updateStatusDisplay();
    }

    updateAssistantState(state) {
        this.assistantPipelineState = state === "LLM" || state === "TTS" ? state : "IDLE";
        this.updateStatusDisplay();
    }

    // 音声処理が IDLE のときだけ、実際の音声再生状態を表示する。
    playbackDisplayState() {
        if (this.pausedPlayback || (this.currentPlayback && this.isPlaybackPaused())) {
            return "PLAYBACK_PAUSED";
        }
        return this.currentPlayback ? "PLAYING" : null;
    }

    updateStatusDisplay() {
        const activeStates = new Set(["DETECTED", "TRAILING_SILENCE", "TURN_DETECTING", "TRANSCRIBING"]);
        const speechActive = activeStates.has(this.speechDetectionState);
        const playbackState = this.playbackDisplayState();
        const displayState = speechActive
            ? this.speechDetectionState
            : this.assistantPipelineState !== "IDLE"
                ? this.assistantPipelineState
                : playbackState || "IDLE";
        const labels = {
            DETECTED: "REC",
            TRAILING_SILENCE: "WAIT",
            TURN_DETECTING: "TURN",
            TRANSCRIBING: "STT",
            LLM: "LLM",
            TTS: "TTS",
            PLAYING: "PLY",
            PLAYBACK_PAUSED: "PAU",
            IDLE: "IDLE"
        };
        this.onSpeechStateDisplay({
            displayState,
            displayText: labels[displayState] || "IDLE",
            active: speechActive || this.assistantPipelineState !== "IDLE" || Boolean(playbackState)
        });
    }

    async ensureTenVadReady() {
        if (this.tenVadHandle) {
            return;
        }
        if (!this.tenVadModule) {
            this.tenVadModule = await createVADModule({
                locateFile: (path) => `/vendor/tenvad/${path}`
            });
            this.addTenVadHelpers();
        }
        this.tenVadHandlePtr = this.tenVadModule._malloc(4);
        const result = this.tenVadModule._ten_vad_create(this.tenVadHandlePtr, this.tenVadHopSamples, this.tenVadThreshold);
        if (result !== 0) {
            this.tenVadModule._free(this.tenVadHandlePtr);
            this.tenVadHandlePtr = 0;
            throw new Error(`TEN VAD create failed: ${result}`);
        }
        this.tenVadHandle = this.tenVadModule.getValue(this.tenVadHandlePtr, "i32");
        this.tenVadAudioPtr = this.tenVadModule._malloc(this.tenVadHopSamples * 2);
        this.tenVadProbabilityPtr = this.tenVadModule._malloc(4);
        this.tenVadFlagPtr = this.tenVadModule._malloc(4);
    }

    addTenVadHelpers() {
        if (!this.tenVadModule.getValue) {
            this.tenVadModule.getValue = (ptr, type) => {
                const view = new DataView(this.tenVadModule.HEAPU8.buffer);
                if (type === "i32") {
                    return view.getInt32(ptr, true);
                }
                if (type === "float") {
                    return view.getFloat32(ptr, true);
                }
                throw new Error(`unsupported TEN VAD value type: ${type}`);
            };
        }
    }

    destroyTenVad() {
        if (!this.tenVadModule) {
            return;
        }
        if (this.tenVadAudioPtr) {
            this.tenVadModule._free(this.tenVadAudioPtr);
            this.tenVadAudioPtr = 0;
        }
        if (this.tenVadProbabilityPtr) {
            this.tenVadModule._free(this.tenVadProbabilityPtr);
            this.tenVadProbabilityPtr = 0;
        }
        if (this.tenVadFlagPtr) {
            this.tenVadModule._free(this.tenVadFlagPtr);
            this.tenVadFlagPtr = 0;
        }
        if (this.tenVadHandlePtr) {
            this.tenVadModule._ten_vad_destroy(this.tenVadHandlePtr);
            this.tenVadModule._free(this.tenVadHandlePtr);
            this.tenVadHandlePtr = 0;
            this.tenVadHandle = 0;
        }
    }

    microphoneStatus() {
        const track = this.micStream.getAudioTracks()[0];
        const settings = track ? track.getSettings() : {};
        const trackRate = settings.sampleRate ? `${settings.sampleRate}Hz` : "unknown";
        const trackChannels = settings.channelCount ? `${settings.channelCount}ch` : "unknown";
        return `microphone track ${trackRate} ${trackChannels}; context ${this.audioContext.sampleRate}Hz; sending ${this.targetSampleRate}Hz`;
    }

    createResampler(inputSampleRate, outputSampleRate) {
        let previousSample = null;
        let sourceOffset = 0;
        const ratio = inputSampleRate / outputSampleRate;
        return (input) => {
            const source = previousSample === null ? input : this.prepend(previousSample, input);
            const outputLength = Math.max(0, Math.floor((source.length - 1 - sourceOffset) / ratio));
            const output = new Float32Array(outputLength);
            for (let i = 0; i < outputLength; i++) {
                const position = sourceOffset + i * ratio;
                const index = Math.floor(position);
                const fraction = position - index;
                output[i] = source[index] + (source[index + 1] - source[index]) * fraction;
            }
            const consumedPosition = sourceOffset + outputLength * ratio;
            sourceOffset = consumedPosition - Math.floor(consumedPosition);
            previousSample = source[source.length - 1];
            return output;
        };
    }

    prepend(sample, input) {
        const values = new Float32Array(input.length + 1);
        values[0] = sample;
        values.set(input, 1);
        return values;
    }

    enqueueSamples(samples) {
        if (samples.length === 0) {
            return;
        }
        if (this.pendingSampleCount === 0) {
            this.pendingStartSampleIndex = this.clientMicSampleIndex;
        }
        this.pendingSamples.push(samples);
        this.pendingSampleCount += samples.length;
        this.clientMicSampleIndex += samples.length;
        if (this.pendingSampleCount >= this.sendSampleThreshold) {
            try {
                this.flushSamples();
            } catch (error) {
                this.onMicStatus(`audio send error: ${error.message}`);
            }
        }
    }

    analyzeLocalTenVad(vadBytes) {
        if (!this.currentPlayback && !this.pausedPlayback) {
            this.loudVadFrameCount = 0;
            this.quietVadFrameCount = 0;
            return;
        }
        for (const vadValue of vadBytes) {
            const speechValue = vadValue & 0x7f;
            if (this.localVadPlaybackPaused) {
                this.quietVadFrameCount = speechValue < this.localResumeVadThreshold ? this.quietVadFrameCount + 1 : 0;
                if (this.quietVadFrameCount >= this.localResumeSilenceVadFrames) {
                    this.quietVadFrameCount = 0;
                    this.loudVadFrameCount = 0;
                    this.resumePlaybackForLocalVad(this.activeAssistantTurnId);
                    return;
                }
            } else {
                this.loudVadFrameCount = speechValue >= this.localPauseVadThreshold ? this.loudVadFrameCount + 1 : 0;
                if (this.loudVadFrameCount >= this.localPauseConsecutiveVadFrames) {
                    this.pausePlaybackForLocalVad(this.activeAssistantTurnId);
                    this.loudVadFrameCount = 0;
                    this.quietVadFrameCount = 0;
                    return;
                }
            }
        }
    }

    flushSamples() {
        if (this.pendingSampleCount === 0) {
            return;
        }
        const alignedSampleCount = this.pendingSampleCount - (this.pendingSampleCount % this.tenVadHopSamples);
        if (alignedSampleCount === 0) {
            return;
        }
        const pcmSamples = this.takePendingPcmSamples(alignedSampleCount);
        const audioMetrics = this.processTenVad(pcmSamples);
        const body = this.createPcmVadBody(pcmSamples, audioMetrics.vadBytes, audioMetrics.rmsBytes);
        const startSample = this.pendingStartSampleIndex;
        const endSample = startSample + pcmSamples.length;
        this.postAudio(body, startSample, endSample);
        if (this.pendingSampleCount > 0) {
            this.pendingStartSampleIndex = endSample;
        }
    }

    takePendingPcmSamples(sampleCount) {
        const pcmSamples = new Int16Array(sampleCount);
        let targetOffset = 0;
        while (targetOffset < sampleCount) {
            const source = this.pendingSamples[0];
            const copyCount = Math.min(source.length, sampleCount - targetOffset);
            for (let i = 0; i < copyCount; i++) {
                pcmSamples[targetOffset + i] = this.floatToPcm16(source[i]);
            }
            targetOffset += copyCount;
            if (copyCount === source.length) {
                this.pendingSamples.shift();
            } else {
                this.pendingSamples[0] = source.subarray(copyCount);
            }
            this.pendingSampleCount -= copyCount;
        }
        return pcmSamples;
    }

    floatToPcm16(sample) {
        const clamped = Math.max(-1, Math.min(1, sample));
        return clamped < 0 ? Math.round(clamped * 32768) : Math.round(clamped * 32767);
    }

    processTenVad(pcmSamples) {
        const frameCount = Math.floor(pcmSamples.length / this.tenVadHopSamples);
        const vadBytes = new Uint8Array(frameCount);
        const rmsBytes = new Uint8Array(frameCount);
        let latestProbability = 0;
        let latestRms = 0;
        for (let frameIndex = 0; frameIndex < frameCount; frameIndex++) {
            const frameOffset = frameIndex * this.tenVadHopSamples;
            this.tenVadModule.HEAP16.set(pcmSamples.subarray(frameOffset, frameOffset + this.tenVadHopSamples), this.tenVadAudioPtr / 2);
            const result = this.tenVadModule._ten_vad_process(
                this.tenVadHandle,
                this.tenVadAudioPtr,
                this.tenVadHopSamples,
                this.tenVadProbabilityPtr,
                this.tenVadFlagPtr
            );
            if (result !== 0) {
                throw new Error(`TEN VAD process failed: ${result}`);
            }
            const probability = this.tenVadModule.getValue(this.tenVadProbabilityPtr, "float");
            latestProbability = probability;
            const value = Math.max(0, Math.min(100, Math.round(probability * 100)));
            const playbackFlag = (this.currentPlayback || this.pausedPlayback) ? 0x80 : 0;
            vadBytes[frameIndex] = (value & 0x7f) | playbackFlag;
            latestRms = this.pcmRms(pcmSamples, frameOffset, frameOffset + this.tenVadHopSamples);
            rmsBytes[frameIndex] = Math.max(0, Math.min(100, Math.round(latestRms * 100)));
        }
        if (frameCount > 0) {
            this.onAudioMetrics({
                vadProbability: latestProbability,
                rms: latestRms
            });
        }
        this.analyzeLocalTenVad(vadBytes);
        return { vadBytes, rmsBytes };
    }

    // PCM16 の振幅を -1.0〜1.0 に正規化したうえで二乗平均平方根を求める。
    pcmRms(pcmSamples, start, end) {
        let squareSum = 0;
        for (let index = start; index < end; index++) {
            const sample = pcmSamples[index] / 32768;
            squareSum += sample * sample;
        }
        return Math.sqrt(squareSum / Math.max(1, end - start));
    }

    createPcmVadBody(pcmSamples, vadBytes, rmsBytes) {
        const body = new ArrayBuffer(this.pcmVadHeaderBytes + pcmSamples.byteLength + vadBytes.byteLength + rmsBytes.byteLength);
        const view = new DataView(body);
        const bytes = new Uint8Array(body);
        bytes[0] = 0x4d;
        bytes[1] = 0x56;
        bytes[2] = 0x41;
        bytes[3] = 0x44;
        view.setUint16(4, 2, true);
        view.setUint16(6, 0, true);
        view.setUint32(8, this.targetSampleRate, true);
        view.setUint16(12, 1, true);
        view.setUint16(14, 1, true);
        view.setUint32(16, pcmSamples.length, true);
        view.setUint32(20, this.tenVadHopSamples, true);
        view.setUint32(24, 0, true);
        view.setUint32(28, 0, true);
        for (let i = 0; i < pcmSamples.length; i++) {
            view.setInt16(this.pcmVadHeaderBytes + i * 2, pcmSamples[i], true);
        }
        const vadOffset = this.pcmVadHeaderBytes + pcmSamples.byteLength;
        const rmsOffset = vadOffset + vadBytes.byteLength;
        bytes.set(vadBytes, vadOffset);
        bytes.set(rmsBytes, rmsOffset);
        return body;
    }

    postAudio(body, startSample, endSample) {
        this.postQueue = this.postQueue
            .then(async () => {
                const now = Date.now();
                if (now < this.audioPostBlockedUntilMs) {
                    // 再接続待機中の古い音声は送らず、待機終了後に生成された音声から送信を再開する。
                    return;
                }
                const response = await fetch(`/chat/request?group=${encodeURIComponent(this.group())}&sessionId=${encodeURIComponent(this.sessionId)}`, {
                    method: "POST",
                    headers: {
                        "Content-Type": this.pcmVadContentType,
                        "X-Client-Mic-Start-Sample": String(startSample),
                        "X-Client-Mic-End-Sample": String(endSample)
                    },
                    body
                });
                if (!response.ok) {
                    const text = await response.text();
                    throw new Error(text || `HTTP ${response.status}`);
                }
                if (this.audioPostRetryDelayMs > 0) {
                    this.clientLog("audio-send-recovered", {
                        detail: `previousRetryDelayMs=${this.audioPostRetryDelayMs}`
                    });
                    if (this.micStream && this.audioContext) {
                        this.onMicStatus(this.microphoneStatus());
                    }
                }
                this.audioPostRetryDelayMs = 0;
                this.audioPostBlockedUntilMs = 0;
            })
            .catch((error) => {
                this.audioPostRetryDelayMs = this.audioPostRetryDelayMs === 0
                    ? this.audioPostInitialRetryDelayMs
                    : Math.min(this.audioPostMaxRetryDelayMs, this.audioPostRetryDelayMs * 2);
                this.audioPostBlockedUntilMs = Date.now() + this.audioPostRetryDelayMs;
                this.clientLog("audio-send-retry-scheduled", {
                    detail: `retryDelayMs=${this.audioPostRetryDelayMs}`,
                    error: error.message
                });
                this.onMicStatus(`audio send error: ${error.message}; retry in ${this.audioPostRetryDelayMs}ms`);
            });
    }

    async ensurePlaybackReady() {
        if (!this.playbackContext) {
            const AudioContextClass = window.AudioContext || window.webkitAudioContext;
            if (!AudioContextClass) {
                this.clientLog("playback-context-unsupported");
                throw new Error("AudioContext is not supported");
            }
            this.playbackContext = new AudioContextClass();
            this.clientLog("playback-context-created", { audioContextState: this.playbackContext.state });
        }
        if (this.playbackContext.state !== "running") {
            await this.playbackContext.resume();
            this.clientLog("playback-context-resumed", { audioContextState: this.playbackContext.state });
        }
    }

    preparePlayback() {
        return this.withTimeout(this.ensurePlaybackReady(), 1500, "AudioContext resume timed out")
            .then(() => this.flushQueuedAudioDeltas())
            .catch((error) => {
                this.onSystem(`audio playback init error: ${error.message}`);
            });
    }

    withTimeout(promise, timeoutMs, message) {
        let timeoutId = 0;
        const timeout = new Promise((resolve, reject) => {
            timeoutId = setTimeout(() => reject(new Error(message)), timeoutMs);
        });
        return Promise.race([promise, timeout]).finally(() => clearTimeout(timeoutId));
    }

    isPlaybackReady() {
        return this.playbackContext && this.playbackContext.state === "running";
    }

    normalizeAssistantChunk(payload) {
        return {
            assistantTurnId: payload.assistantTurnId || 0,
            chunkId: payload.chunkId || 0,
            text: payload.text || "",
            audioDeltas: Array.isArray(payload.audioDeltas) ? payload.audioDeltas : [],
            audioDurationSeconds: Number(payload.audioDurationSeconds || 0),
            recognized: false,
            rendered: null
        };
    }

    queueAudioDelta(payload) {
        const normalized = this.normalizeAssistantChunk(payload);
        const turnId = normalized.assistantTurnId || 0;
        if (this.canceledAssistantTurnIds.has(turnId) || turnId < this.activeAssistantTurnId) {
            this.clientLog("audio-delta-dropped", {
                assistantTurnId: turnId,
                chunkId: normalized.chunkId || 0,
                detail: `canceled=${this.canceledAssistantTurnIds.has(turnId)};older=${turnId < this.activeAssistantTurnId}`
            });
            return;
        }
        this.activeAssistantTurnId = Math.max(this.activeAssistantTurnId, turnId);
        if (this.chunkHasAudio(normalized) && !this.isPlaybackReady()) {
            this.queuedAudioDeltas.push(normalized);
            this.clientLog("audio-delta-queued-waiting-context", {
                assistantTurnId: turnId,
                chunkId: normalized.chunkId || 0,
                detail: `audioDeltas=${normalized.audioDeltas.length}`
            });
            if (this.queuedAudioDeltas.length > 100) {
                this.queuedAudioDeltas = this.queuedAudioDeltas.slice(-100);
                this.clientLog("audio-delta-queue-trimmed", { assistantTurnId: turnId });
            }
            this.preparePlayback();
            return;
        }
        this.queuedAudioDeltas.push(normalized);
        this.clientLog("audio-delta-queued", {
            assistantTurnId: turnId,
            chunkId: normalized.chunkId || 0,
            detail: `audioDeltas=${normalized.audioDeltas.length}`
        });
        this.pumpPlayback();
    }

    chunkHasAudio(payload) {
        return payload.audioDeltas && payload.audioDeltas.some((delta) => delta.data);
    }

    flushQueuedAudioDeltas() {
        if (!this.isPlaybackReady() || this.queuedAudioDeltas.length === 0) {
            this.clientLog("flush-queued-audio-skipped", {
                detail: `ready=${this.isPlaybackReady()};queued=${this.queuedAudioDeltas.length}`
            });
            return;
        }
        this.clientLog("flush-queued-audio");
        this.pumpPlayback();
    }

    pumpPlayback() {
        const nextPayload = this.queuedAudioDeltas[0];
        const needsAudio = nextPayload && this.chunkHasAudio(nextPayload);
        if ((needsAudio && !this.isPlaybackReady()) || this.isPlaybackPaused() || this.currentPlayback || this.queuedAudioDeltas.length === 0) {
            this.clientLog("pump-playback-blocked", {
                detail: `ready=${this.isPlaybackReady()};paused=${this.isPlaybackPaused()};current=${Boolean(this.currentPlayback)};queued=${this.queuedAudioDeltas.length};needsAudio=${Boolean(needsAudio)}`
            });
            return;
        }
        const payload = this.queuedAudioDeltas.shift();
        const turnId = payload.assistantTurnId || 0;
        if (this.canceledAssistantTurnIds.has(turnId) || turnId < this.activeAssistantTurnId) {
            this.clientLog("pump-playback-drop-payload", {
                assistantTurnId: turnId,
                chunkId: payload.chunkId || 0,
                detail: `canceled=${this.canceledAssistantTurnIds.has(turnId)};older=${turnId < this.activeAssistantTurnId}`
            });
            this.pumpPlayback();
            return;
        }
        try {
            this.clientLog("pump-playback-start", { assistantTurnId: turnId, chunkId: payload.chunkId || 0 });
            this.startPlayback(payload, 0);
        } catch (error) {
            this.clientLog("pump-playback-error", { assistantTurnId: turnId, chunkId: payload.chunkId || 0, error: error.message });
            this.onSystem(`audio playback error: ${error.message}`);
            this.currentPlayback = null;
            this.pumpPlayback();
        }
    }

    startPlayback(payload, offsetSeconds) {
        if (!payload.rendered && payload.text) {
            payload.rendered = {
                bubble: this.onAssistantText(payload.text, payload),
                text: payload.text
            };
            this.onAssistantSubtitle(payload.text, payload);
        }
        if (!this.chunkHasAudio(payload)) {
            payload.recognized = true;
            this.clientLog("start-playback-text-only-payload", {
                assistantTurnId: payload.assistantTurnId || 0,
                chunkId: payload.chunkId || 0
            });
            this.reportPlayback("end", payload.assistantTurnId || 0, payload, 0, true);
            this.onAssistantSubtitleDone(payload);
            this.pumpPlayback();
            return;
        }
        const decoded = this.decodeAudioDeltas(payload.audioDeltas);
        const buffer = this.playbackContext.createBuffer(1, decoded.samples.length, decoded.sampleRate);
        payload.audioDurationSeconds = payload.audioDurationSeconds || buffer.duration;
        buffer.copyToChannel(decoded.samples, 0);

        const source = this.playbackContext.createBufferSource();
        source.buffer = buffer;
        source.connect(this.playbackContext.destination);
        this.currentPlayback = {
            payload,
            source,
            offsetSeconds,
            startedAt: this.playbackContext.currentTime,
            durationSeconds: buffer.duration,
            stopRequested: false
        };
        this.updateStatusDisplay();
        this.clientLog("playback-start", {
            assistantTurnId: payload.assistantTurnId || 0,
            chunkId: payload.chunkId || 0,
            detail: `offsetSeconds=${offsetSeconds};samples=${decoded.samples.length};duration=${buffer.duration}`
        });
        this.reportPlayback(offsetSeconds > 0 ? "resume" : "start", payload.assistantTurnId || 0, payload, offsetSeconds, false);
        source.onended = () => {
            const playback = this.currentPlayback;
            if (!playback || playback.source !== source) {
                return;
            }
            this.currentPlayback = null;
            this.updateStatusDisplay();
            if (!playback.stopRequested) {
                const playedSeconds = playback.durationSeconds;
                const recognized = !playback.payload.recognized
                    && this.shouldRecognizePlayback(playback.payload, playedSeconds, true);
                playback.payload.recognized = playback.payload.recognized || recognized;
                this.clientLog("playback-ended", {
                    assistantTurnId: playback.payload.assistantTurnId || 0,
                    chunkId: playback.payload.chunkId || 0,
                    detail: `recognized=${recognized};playedSeconds=${playedSeconds}`
                });
                this.reportPlayback("end", playback.payload.assistantTurnId || 0, playback.payload, playedSeconds, recognized);
                this.onAssistantSubtitleDone(playback.payload);
                this.pumpPlayback();
            } else {
                this.clientLog("playback-ended-after-stop", {
                    assistantTurnId: playback.payload.assistantTurnId || 0,
                    chunkId: playback.payload.chunkId || 0
                });
            }
        };
        source.start(0, Math.min(offsetSeconds, buffer.duration));
    }

    handleAudioControl(payload) {
        const assistantTurnId = payload.assistantTurnId || 0;
        if (assistantTurnId <= 0) {
            this.clientLog("audio-control-ignored-without-assistant-turn", {
                assistantTurnId,
                detail: `action=${payload.action || ""}`
            });
            return;
        }
        this.activeAssistantTurnId = Math.max(this.activeAssistantTurnId, assistantTurnId);
        if (payload.action === "pause") {
            this.pausePlaybackForServerSttWait(assistantTurnId);
            return;
        }
        if (payload.action === "resume") {
            this.resumePlaybackForServerSttWait(assistantTurnId);
            return;
        }
        if (payload.action === "cancel") {
            this.cancelPlayback(assistantTurnId);
        }
    }

    isPlaybackPaused() {
        return this.localVadPlaybackPaused || this.serverSttPlaybackPaused;
    }

    pausePlaybackForLocalVad(assistantTurnId) {
        if (assistantTurnId !== this.activeAssistantTurnId) {
            this.clientLog("local-vad-pause-ignored", {
                assistantTurnId,
                detail: `activeAssistantTurnId=${this.activeAssistantTurnId}`
            });
            return;
        }
        const wasPaused = this.isPlaybackPaused();
        this.localVadPlaybackPaused = true;
        this.updateStatusDisplay();
        this.clientLog("local-vad-pause-set", { assistantTurnId, detail: `wasPaused=${wasPaused}` });
        this.pauseCurrentPlayback(assistantTurnId, wasPaused);
    }

    pausePlaybackForServerSttWait(assistantTurnId) {
        if (assistantTurnId !== this.activeAssistantTurnId) {
            this.clientLog("server-stt-pause-ignored", {
                assistantTurnId,
                detail: `activeAssistantTurnId=${this.activeAssistantTurnId}`
            });
            return;
        }
        const wasPaused = this.isPlaybackPaused();
        this.serverSttPlaybackPaused = true;
        this.updateStatusDisplay();
        this.clientLog("server-stt-pause-set", { assistantTurnId, detail: `wasPaused=${wasPaused}` });
        this.pauseCurrentPlayback(assistantTurnId, wasPaused);
    }

    pauseCurrentPlayback(assistantTurnId, wasPaused) {
        if (wasPaused) {
            this.clientLog("pause-current-playback-already-paused", { assistantTurnId });
            return;
        }
        if (!this.currentPlayback || !this.playbackContext) {
            this.clientLog("pause-current-playback-no-current", { assistantTurnId });
            return;
        }
        const playback = this.currentPlayback;
        const elapsed = Math.max(0, this.playbackContext.currentTime - playback.startedAt);
        playback.offsetSeconds += elapsed;
        const playedSeconds = playback.offsetSeconds;
        const recognized = !playback.payload.recognized
            && this.shouldRecognizePlayback(playback.payload, playedSeconds, false);
        playback.payload.recognized = playback.payload.recognized || recognized;
        playback.stopRequested = true;
        this.pausedPlayback = {
            payload: playback.payload,
            offsetSeconds: playback.offsetSeconds
        };
        this.updateStatusDisplay();
        playback.source.stop();
        this.clientLog("pause-current-playback-stopped-source", {
            assistantTurnId,
            chunkId: playback.payload.chunkId || 0,
            detail: `offsetSeconds=${playback.offsetSeconds};elapsed=${elapsed};recognized=${playback.payload.recognized}`
        });
        this.reportPlayback("pause", assistantTurnId, playback.payload, playedSeconds, recognized);
    }

    resumePlaybackForLocalVad(assistantTurnId) {
        if (assistantTurnId !== this.activeAssistantTurnId || this.canceledAssistantTurnIds.has(assistantTurnId)) {
            this.clientLog("local-vad-resume-ignored", {
                assistantTurnId,
                detail: `activeAssistantTurnId=${this.activeAssistantTurnId};canceled=${this.canceledAssistantTurnIds.has(assistantTurnId)}`
            });
            return;
        }
        this.localVadPlaybackPaused = false;
        this.updateStatusDisplay();
        this.clientLog("local-vad-resume-set", { assistantTurnId });
        this.resumePlaybackIfUnpaused(assistantTurnId);
    }

    resumePlaybackForServerSttWait(assistantTurnId) {
        if (assistantTurnId !== this.activeAssistantTurnId || this.canceledAssistantTurnIds.has(assistantTurnId)) {
            this.clientLog("server-stt-resume-ignored", {
                assistantTurnId,
                detail: `activeAssistantTurnId=${this.activeAssistantTurnId};canceled=${this.canceledAssistantTurnIds.has(assistantTurnId)}`
            });
            return;
        }
        this.serverSttPlaybackPaused = false;
        this.updateStatusDisplay();
        this.clientLog("server-stt-resume-set", { assistantTurnId });
        this.resumePlaybackIfUnpaused(assistantTurnId);
    }

    resumePlaybackIfUnpaused(assistantTurnId) {
        if (this.isPlaybackPaused()) {
            this.clientLog("resume-playback-still-paused", { assistantTurnId });
            return;
        }
        if (this.pausedPlayback) {
            const playback = this.pausedPlayback;
            this.pausedPlayback = null;
            this.updateStatusDisplay();
            try {
                this.clientLog("resume-paused-playback", {
                    assistantTurnId,
                    detail: `offsetSeconds=${playback.offsetSeconds}`
                });
                this.startPlayback(playback.payload, playback.offsetSeconds);
                return;
            } catch (error) {
                this.clientLog("resume-playback-error", { assistantTurnId, error: error.message });
                this.onSystem(`audio resume error: ${error.message}`);
            }
        }
        this.clientLog("resume-pump-playback", { assistantTurnId });
        this.pumpPlayback();
    }

    cancelPlayback(assistantTurnId) {
        this.canceledAssistantTurnIds.add(assistantTurnId);
        this.clientLog("cancel-playback-received", { assistantTurnId });
        if (assistantTurnId === this.activeAssistantTurnId) {
            this.localVadPlaybackPaused = false;
            this.serverSttPlaybackPaused = false;
            this.clientLog("cancel-playback-cleared-pauses", { assistantTurnId });
        }
        this.queuedAudioDeltas = this.queuedAudioDeltas.filter((payload) => (payload.assistantTurnId || 0) !== assistantTurnId);
        if (this.pausedPlayback && (this.pausedPlayback.payload.assistantTurnId || 0) === assistantTurnId) {
            this.rollbackUnrecognizedChunk(this.pausedPlayback.payload);
            this.pausedPlayback = null;
            this.updateStatusDisplay();
            this.clientLog("cancel-playback-cleared-paused-source", { assistantTurnId });
        }
        if (this.currentPlayback && (this.currentPlayback.payload.assistantTurnId || 0) === assistantTurnId) {
            const playback = this.currentPlayback;
            const elapsed = this.playbackContext ? Math.max(0, this.playbackContext.currentTime - playback.startedAt) : 0;
            const playedSeconds = playback.offsetSeconds + elapsed;
            const recognized = !playback.payload.recognized
                && this.shouldRecognizePlayback(playback.payload, playedSeconds, false);
            playback.payload.recognized = playback.payload.recognized || recognized;
            if (!playback.payload.recognized) {
                this.rollbackUnrecognizedChunk(playback.payload);
            }
            this.reportPlayback("cancel", assistantTurnId, playback.payload, playedSeconds, playback.payload.recognized);
            playback.stopRequested = true;
            playback.source.stop();
            this.currentPlayback = null;
            this.updateStatusDisplay();
            this.clientLog("cancel-playback-stopped-current-source", {
                assistantTurnId,
                chunkId: playback.payload.chunkId || 0
            });
        } else {
            this.reportPlayback("cancel", assistantTurnId, { chunkId: 0, audioDurationSeconds: 0 }, 0, false);
        }
        this.pumpPlayback();
    }

    cancelAllPlayback(reason = "local-stop") {
        const assistantTurnIds = new Set();
        if (this.activeAssistantTurnId > 0) {
            assistantTurnIds.add(this.activeAssistantTurnId);
        }
        for (const payload of this.queuedAudioDeltas) {
            const assistantTurnId = payload.assistantTurnId || 0;
            if (assistantTurnId > 0) {
                assistantTurnIds.add(assistantTurnId);
            }
        }
        if (this.currentPlayback) {
            const assistantTurnId = this.currentPlayback.payload.assistantTurnId || 0;
            if (assistantTurnId > 0) {
                assistantTurnIds.add(assistantTurnId);
            }
        }
        if (this.pausedPlayback) {
            const assistantTurnId = this.pausedPlayback.payload.assistantTurnId || 0;
            if (assistantTurnId > 0) {
                assistantTurnIds.add(assistantTurnId);
            }
        }
        if (assistantTurnIds.size === 0) {
            this.queuedAudioDeltas = [];
            this.localVadPlaybackPaused = false;
            this.serverSttPlaybackPaused = false;
            this.updateAssistantState("IDLE");
            this.updateSpeechState("UNDETECTED");
            this.clientLog("cancel-all-playback-empty", { detail: reason });
            return;
        }
        for (const assistantTurnId of assistantTurnIds) {
            this.cancelPlayback(assistantTurnId);
        }
        this.updateAssistantState("IDLE");
        this.updateSpeechState("UNDETECTED");
        this.clientLog("cancel-all-playback-done", {
            assistantTurnId: this.activeAssistantTurnId,
            detail: `${reason};turns=${assistantTurnIds.size}`
        });
    }

    shouldRecognizePlayback(payload, playedSeconds, completed) {
        const durationSeconds = Number(payload.audioDurationSeconds || 0);
        if (playedSeconds >= 0.4) {
            return true;
        }
        return completed && durationSeconds >= 0 && durationSeconds < 0.4;
    }

    rollbackUnrecognizedChunk(payload) {
        if (!payload || payload.recognized || !payload.rendered) {
            return;
        }
        this.onAssistantTextRemove(payload.rendered.bubble, payload.rendered.text);
        payload.rendered = null;
        this.clientLog("assistant-chunk-rollback", {
            assistantTurnId: payload.assistantTurnId || 0,
            chunkId: payload.chunkId || 0
        });
    }

    reportPlayback(state, assistantTurnId, payload = {}, playedSeconds = 0, recognized = false) {
        if (!assistantTurnId) {
            return;
        }
        fetch(`/chat/playback?group=${encodeURIComponent(this.group())}&sessionId=${encodeURIComponent(this.sessionId)}`, {
            method: "POST",
            headers: {"Content-Type": "application/json; charset=utf-8"},
            body: JSON.stringify({
                assistantTurnId,
                chunkId: payload.chunkId || 0,
                state,
                recognized,
                playedSeconds,
                durationSeconds: payload.audioDurationSeconds || 0,
                clientMicSampleIndex: this.clientMicSampleIndex
            })
        }).catch((error) => {
            this.onSystem(`playback report error: ${error.message}`);
        });
    }

    decodeAudioDeltas(deltas) {
        let sampleRate = 24000;
        let totalSamples = 0;
        const arrays = [];
        for (const delta of deltas) {
            if (!delta.data) {
                continue;
            }
            sampleRate = delta.sampleRate || sampleRate;
            const bytes = Uint8Array.from(atob(delta.data), (char) => char.charCodeAt(0));
            const samples = new Float32Array(bytes.length / 2);
            const view = new DataView(bytes.buffer);
            for (let i = 0; i < samples.length; i++) {
                samples[i] = view.getInt16(i * 2, true) / 32768;
            }
            arrays.push(samples);
            totalSamples += samples.length;
        }
        const merged = new Float32Array(totalSamples);
        let offset = 0;
        for (const samples of arrays) {
            merged.set(samples, offset);
            offset += samples.length;
        }
        return { samples: merged, sampleRate };
    }
}
