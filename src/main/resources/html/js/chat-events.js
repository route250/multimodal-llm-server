export class ChatEvents {
    constructor(options) {
        this.group = options.group;
        this.sessionId = options.sessionId;
        this.onSystem = options.onSystem || (() => {});
        this.onUserMessage = options.onUserMessage || (() => {});
        this.onMessage = options.onMessage || (() => {});
        this.onMessageDelta = options.onMessageDelta || (() => {});
        this.onMessageDone = options.onMessageDone || (() => {});
        this.onTranscriptPartial = options.onTranscriptPartial || (() => {});
        this.onAssistantAudioChunk = options.onAssistantAudioChunk || (() => {});
        this.onAudioControl = options.onAudioControl || (() => {});
        this.onSpeechState = options.onSpeechState || (() => {});
        this.onAssistantState = options.onAssistantState || (() => {});
        this.onFacePresence = options.onFacePresence || (() => {});
        this.statusFields = options.statusFields || (() => ({}));
        this.source = null;
        this.logSequence = 0;
    }

    connect() {
        this.close();
        this.clientLog("event-source-create");
        const source = new EventSource(`/chat/connect?group=${encodeURIComponent(this.group())}&sessionId=${encodeURIComponent(this.sessionId)}`);
        this.source = source;

        source.addEventListener("system", (event) => {
            this.onSystem(JSON.parse(event.data).message);
        });
        source.addEventListener("user-message", (event) => {
            this.onUserMessage(JSON.parse(event.data).message);
        });
        source.addEventListener("message", (event) => {
            this.onMessage(JSON.parse(event.data).message);
        });
        source.addEventListener("message-delta", (event) => {
            this.onMessageDelta(JSON.parse(event.data).message);
        });
        source.addEventListener("message-done", () => {
            this.clientLog("message-done-received");
            this.onMessageDone();
        });
        source.addEventListener("transcript-partial", (event) => {
            try {
                this.onTranscriptPartial(JSON.parse(JSON.parse(event.data).message));
            } catch (error) {
                this.clientLog("transcript-partial-parse-error", { error: error.message });
            }
        });
        source.addEventListener("assistant-audio-chunk", (event) => {
            try {
                const payload = JSON.parse(JSON.parse(event.data).message);
                this.clientLog("assistant-audio-chunk-received", {
                    assistantTurnId: payload.assistantTurnId || 0,
                    chunkId: payload.chunkId || 0,
                    detail: `audioDeltas=${payload.audioDeltas ? payload.audioDeltas.length : 0};textChars=${payload.text ? payload.text.length : 0}`
                });
                this.onAssistantAudioChunk(payload);
            } catch (error) {
                this.clientLog("assistant-audio-chunk-parse-error", { error: error.message });
                this.onSystem(`assistant audio chunk parse error: ${error.message}`);
            }
        });
        source.addEventListener("audio-control", (event) => {
            try {
                const payload = JSON.parse(JSON.parse(event.data).message);
                this.clientLog("audio-control-received", {
                    assistantTurnId: payload.assistantTurnId || 0,
                    detail: `action=${payload.action || ""};reason=${payload.reason || ""};speechSequenceId=${payload.speechSequenceId || 0}`
                });
                this.onAudioControl(payload);
            } catch (error) {
                this.clientLog("audio-control-parse-error", { error: error.message });
                this.onSystem(`audio control parse error: ${error.message}`);
            }
        });
        source.addEventListener("speech-state", (event) => {
            try {
                this.onSpeechState(JSON.parse(JSON.parse(event.data).message));
            } catch (error) {
                this.clientLog("speech-state-parse-error", { error: error.message });
            }
        });
        source.addEventListener("assistant-state", (event) => {
            try {
                this.onAssistantState(JSON.parse(JSON.parse(event.data).message));
            } catch (error) {
                this.clientLog("assistant-state-parse-error", { error: error.message });
            }
        });
        source.addEventListener("face-presence", (event) => {
            try {
                this.onFacePresence(JSON.parse(JSON.parse(event.data).message));
            } catch (error) {
                this.clientLog("face-presence-parse-error", { error: error.message });
            }
        });
        return source;
    }

    close() {
        if (this.source) {
            this.source.close();
            this.source = null;
        }
    }

    clientLog(event, fields = {}) {
        const payload = {
            event,
            sequence: ++this.logSequence,
            ...this.statusFields(),
            ...fields
        };
        const url = `/chat/client-log?group=${encodeURIComponent(this.group())}&sessionId=${encodeURIComponent(this.sessionId)}`;
        const body = JSON.stringify(payload);
        if (navigator.sendBeacon) {
            const sent = navigator.sendBeacon(url, new Blob([body], { type: "application/json; charset=utf-8" }));
            if (sent) {
                return;
            }
        }
        fetch(url, {
            method: "POST",
            headers: {"Content-Type": "application/json; charset=utf-8"},
            body,
            keepalive: true
        }).catch(() => {
            // ログ送信失敗で画面操作を止めない。
        });
    }
}
