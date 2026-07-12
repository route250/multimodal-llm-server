# MlServer Specs

## Overview

`MlServer` is a Java HTTP server for a multimodal chat backend.

The server has two responsibilities:

- Serve static frontend files from `src/main/resources/html`.
- Provide chat endpoints for bidirectional chat flow using HTTP requests and Server-Sent Events.

`MlServer` uses JDK standard `com.sun.net.httpserver.HttpServer`.
Each request is handled by `Executors.newVirtualThreadPerTaskExecutor()`.

## Static File Serving

All requests except chat API paths are resolved as static files.

Chat API paths:

- `GET /chat/connect`
- `POST /chat/request`

Static root:

```text
src/main/resources/html
```

Resolution rules:

```text
/        -> src/main/resources/html/index.html
/foo     -> src/main/resources/html/foo or src/main/resources/html/foo/index.html
/foo/    -> src/main/resources/html/foo/index.html
/foo.js  -> src/main/resources/html/foo.js
```

`MlServer` reads files from the filesystem first, so changes under `src/main/resources/html` can be served without changing Java code.
When the file is not found on the filesystem, `MlServer` tries the classpath resource under `/html/...`.

Supported methods:

- `GET`
- `HEAD`

Other methods return `405 Method Not Allowed`.

## Chat Groups

`ChatGroup` represents a chat room.

A chat room is a place where multiple clients, such as users and LLM agents, can connect and exchange events.

`MlServer` creates three default chat groups on startup:

```text
group-1
group-2
group-3
```

When the `group` query parameter is omitted, `group-1` is used.

If a request specifies an unknown group, `MlServer` returns:

```http
404 Not Found
Content-Type: application/json; charset=utf-8

{"error":"chat group not found"}
```

## Chat Clients

`ChatClient` represents one connected client inside a `ChatGroup`.

The client ID is currently derived from the `sessionId` query parameter.

Responsibilities:

- Hold the outbound event queue for its SSE connection.
- Keep the latest 20 user/assistant messages as LLM conversation history.
- Receive events from the `ChatGroup`.
- Handle incoming `ChatRequest`.
- Send resulting events to its `ChatGroup`.

Conversation history is updated with these rules:

- Text input and non-empty FINAL STT text are sent to the LLM immediately, but the user message is not added to history before the LLM call.
- When a played assistant chunk is reported by `/chat/playback` with `recognized:true`, the related user message is added to history once for that `assistantTurnId`.
- The recognized assistant chunk text is added to the assistant history for that `assistantTurnId`. If another recognized chunk for the same `assistantTurnId` arrives, its text is appended to the same assistant history message.
- If the LLM request fails or TTS fails before any recognized assistant chunk is recorded, the user message is added to history without an assistant message.
- If no recognized assistant chunk is reported for a completed turn, the user message remains pending. When a later user turn starts, the stale pending user message is added to history without an assistant message.
- Assistant chunks that are never reported as recognized are not added to history.

If `/chat/request` is called with a `sessionId` that is not connected to the specified `ChatGroup`, the server returns:

```http
404 Not Found
Content-Type: application/json; charset=utf-8

{"error":"chat client not connected"}
```

## Endpoints

### `GET /chat/connect`

Starts an SSE connection and joins a client to a chat group.

Query parameters:

```text
group      ChatGroup ID. Optional. Defaults to group-1.
sessionId  Client/session ID. Optional. Defaults to default.
```

Example:

```http
GET /chat/connect?group=group-1&sessionId=user-a
```

Behavior:

1. Resolve the `ChatGroup` from `group`.
2. Create and register a `ChatClient` in that group using `sessionId`.
3. Start an SSE stream with `Content-Type: text/event-stream; charset=utf-8`.
4. Send a `system` event for the connection.
5. Poll the `ChatClient` event queue.
6. Write queued events to the SSE stream.
7. Write `: keep-alive` comments every 15 seconds when no event exists.
8. On disconnect, remove the `ChatClient` from the `ChatGroup`.

SSE event format:

```text
event: message
data: {"message":"...","timestamp":"..."}
```

Current event types:

```text
system
user-message
message
assistant-audio-chunk
assistant-state
audio-control
speech-state
transcript-partial
face-presence
message-done
```

### `POST /chat/request`

Receives input from a connected client.

Query parameters:

```text
group      ChatGroup ID. Optional. Defaults to group-1.
sessionId  Client/session ID. Optional. Defaults to default.
```

Example:

```http
POST /chat/request?group=group-1&sessionId=user-a
Content-Type: text/plain; charset=utf-8

hello
```

Behavior:

1. Resolve the `ChatGroup` from `group`.
2. Resolve the `ChatClient` from `sessionId` inside the group.
3. Read the request body.
4. Build a `ChatRequest`.
5. Pass the request to `ChatClient#handle(ChatRequest)`.
6. `ChatClient` converts the request into a `ServerEvent`.
7. `ChatClient` sends the event to `ChatGroup`.
8. `ChatGroup` broadcasts the event to all connected `ChatClient` instances.
9. Each `ChatClient` receives the event through its queue.
10. `/chat/connect` streams those queued events to the actual HTTP client.

Successful response:

```http
202 Accepted
Content-Type: application/json; charset=utf-8

{"status":"accepted","groupId":"group-1","sessionId":"user-a","type":"text","bytes":5}
```

Supported request content handling:

```text
text/*            Treated as text.
application/json  Treated as text.
audio/pcm         Rejected for audio processing because browser VAD bytes are required.
audio/pcm-vad     Treated as a PCM16LE 16kHz mono audio chunk with browser VAD bytes.
other             Treated as binary.
```

Current `ChatRequest` conversion is a test implementation:

```text
text   -> received text: ...
audio  -> queued for the per-client audio buffer and VAD processor.
binary -> received binary chunk: N bytes (...)
```

Audio requests must use browser VAD bytes:

```http
Content-Type: audio/pcm-vad; rate=16000; channels=1; format=s16le; vad-frame-samples=256
X-Client-Mic-Start-Sample: 0
X-Client-Mic-End-Sample: 3200
```

ブラウザクライアントは 16 kHz へ変換したマイクPCMの累積サンプル番号を上記ヘッダに入れます。
サーバはこのサンプル範囲と AI 音声再生区間を照合し、該当PCMを VAD/STT へ渡す前に 0 で埋めます。

`audio/pcm-vad` の body は、32 byte header、PCM16LE、VAD byte array の順です。
header は little-endian で、`magic=MVAD`、`version=1`、`flags=0`、`sampleRate=16000`、`channels=1`、
`pcmFormat=1`、`pcmSampleCount`、`vadFrameSamples=256`、`vadByteCount`、`reserved=0` を保持します。
VAD byte は下位 7 bit に `0..100` の整数値を保持し、最上位 bit `0x80` はブラウザ側で AI 音声の
`currentPlayback` または `pausedPlayback` が存在する間に `1` にします。
サーバは VAD byte の下位 7 bit を発話判定に使い、サーバ側では VAD 確率を再計算しません。

Each `ChatClient` keeps a 6 second receive buffer and a 30 second STT buffer.
Audio requests return `202 Accepted` after the chunk is queued.
PCM decoding and VAD run asynchronously in request order for each `ChatClient`.
STT runs on a separate asynchronous task after a speech turn is confirmed, so later audio chunks can continue PCM decoding and VAD while transcription is still running.
STT tasks are queued in speech-confirmation order for each `AudioProcessor`.
Async audio processing failures are sent to connected clients as SSE `system` events.
The receive buffer stores PCM samples and browser VAD values in 256 sample units.
Browser VAD values are applied once per 256 samples, which is 16 ms at 16 kHz.

Speech spike detection starts when the VAD value is at least `0.65`.
Speech starts when the VAD value then stays above `0.35` for at least 3,200 samples, which is 200 ms at 16 kHz.
When speech starts, the target range includes the previous 0.6 seconds of audio.
When VAD becomes at most `0.35`, the processor enters trailing silence.
After trailing silence reaches 9,600 samples, which is 600 ms at 16 kHz, SmartTurn is evaluated every
9,600 samples. If SmartTurn returns incomplete, trailing silence continues. If VAD becomes greater than
`0.35` before SmartTurn returns complete, the same speech returns to detected state and no FINAL STT is queued.
If SmartTurn still returns incomplete after trailing silence reaches 19,200 samples, which is 1,200 ms,
the server queues a `FINAL` STT task anyway.
発話継続中は 76,800 サンプル、つまり 4.8 秒ごとに `PARTIAL` STT を実行します。
`PARTIAL` の非空結果は現在の AI 音声と生成中 turn を cancel しますが、LLM と TTS は開始しません。
`FINAL` では同じ `speechSequenceId` の過去 `PARTIAL` 結果と `FINAL` 結果を結合します。
`FINAL` の対象範囲が空の場合、STT 結果は空文字として扱います。
結合後テキストが空文字でない場合、サーバは OpenAI Responses API 互換エンドポイントへ `stream:true`
で送信し、LLM の応答差分を TTS 分割単位へ変換して `assistant-audio-chunk` イベントとして配信します。
結合後テキストが空文字の場合は、
一時停止中の AI 音声再生を再開します。FINAL STT 実行中に新しい発話で `speechSequenceId` が変わった場合、
古い FINAL 結果は破棄します。
LLM 応答差分は、以下の条件で TTS へ送信する単位に分割します。

- 句読点: `。`、`、`、`，`、`,`、`.`、`！`、`!`、`？`、`?`、`；`、`;`、`：`、`:`
- 空白: `Character.isWhitespace(c)` が `true` の文字
- 最大長: 80 文字

TTS の結果は `assistant-audio-chunk` イベントとして配信します。
`assistant-audio-chunk` の payload には `assistantTurnId`、`chunkId`、`text`、`audioDeltas`、`audioDurationSeconds`
を含めます。`audioDeltas` の各要素は `data`、`format`、`sampleRate` を含みます。
クライアントは現在の再生対象より古い `assistantTurnId`、またはキャンセル済みの `assistantTurnId` を持つ
音声チャンクを破棄します。

STT タスクが投入された場合、サーバは `audio-control` イベントを配信します。payload は
`action`、`assistantTurnId`、`interruptionId`、`speechSequenceId`、`reason` を含みます。

```text
pause   STT 結果待ちに入ったため、対象の AI 音声再生を一時停止する。reason は stt-wait。
resume  FINAL の結合後テキストが空文字だったため、同じ AI 音声再生を再開する。reason は empty-stt。
cancel  PARTIAL または FINAL の結合後テキストが非空だったため、対象の AI 音声再生と古い応答出力を破棄する。reason は user-transcript。
```
VAD による短時間の再生停止と再開はブラウザだけで完結させ、サーバは VAD 検出を理由に `audio-control`
を配信しません。
ブラウザは AI 音声の再生開始、停止、再開、終了、キャンセルを `/chat/playback` へ通知します。
サーバは通知された再生区間と停止後 12,000 サンプルを EchoMask として保持します。
再生中の開いた区間も、音声チャンク受信時点で EchoMask と同じ扱いで無音化します。

```http
POST /chat/playback?group=group-1&sessionId=user-a
Content-Type: application/json; charset=utf-8

{"assistantTurnId":1,"state":"start","clientMicSampleIndex":16000}
```

`state` は `start`、`resume`、`pause`、`stop`、`end`、`cancel` を受け付けます。
ブラウザは `echoCancellation:true` と `noiseSuppression:true` を有効にし、AI 音声再生中に TEN VAD 値が
50 以上の状態が 3 フレーム、つまり 48 ms 続いたらローカルで再生を一時停止します。
一時停止中は TEN VAD 値が 35 未満の状態が 8 フレーム、つまり 128 ms 続いたらローカルで再開します。
ブラウザは `localVadPlaybackPaused` と `serverSttPlaybackPaused` の 2 つの停止フラグを保持し、
両方が `false` になった時だけ再生を再開します。
`assistant-audio-chunk` の `message` は JSON 文字列です。`audioDeltas[].data` は base64、`audioDeltas[].format`
は `pcm`、`audioDeltas[].sampleRate` は通常 `24000` です。
LLM 応答と TTS 処理が終了したら `message-done` イベントを配信します。

再生状態の調査用に、サーバとブラウザは `tmp/audio-debug/audio-debug.log` へ JSON Lines 形式の診断ログを出力します。
サーバは `assistant-turn-start`、`llm-message-delta`、`tts-chunk-start`、`tts-audio-delta`、`tts-chunk-done`、
`sse-send-assistant-audio-chunk`、`sse-send-audio-control`、`playback-report` を記録します。
ブラウザは `/chat/client-log` へ状態を送信し、サーバは `browser-assistant-audio-chunk-received`、
`browser-audio-delta-queued`、`browser-pump-playback-blocked`、`browser-playback-start`、
`browser-playback-ended`、`browser-local-vad-pause-set`、`browser-server-stt-pause-set`、
`browser-cancel-playback-received` などを記録します。
ブラウザログは `assistantTurnId`、`activeAssistantTurnId`、`queuedAudioDeltas`、`currentPlayback`、
`pausedPlayback`、`localVadPlaybackPaused`、`serverSttPlaybackPaused`、`playbackReady` を含みます。
デフォルトでは OpenAI 公式 Responses API を使います。

```text
OPENAI_API_KEY=sk-...
LLAMACPP_BASE_URL=https://api.openai.com/v1
LLM_MODEL=gpt-5-nano
LLM_SYSTEM_PROMPT=あなたは日本語で簡潔に応答するアシスタントです。
LLM_TIMEOUT_SECONDS=120
```

`LLM_MODEL` は Java の正規表現として扱います。
サーバは `GET /v1/models` でモデル一覧を取得し、モデル ID に対して `Pattern.matcher(modelId).find()` が真になる最初のモデルを `POST /v1/responses` の `model` に指定します。
`OPENAI_API_KEY` が設定されている場合は、`GET /v1/models` と `POST /v1/responses` の両方へ `Authorization: Bearer` ヘッダーを付与します。
テキスト入力と STT 結果は、同じ `ChatClient` の直近 20 件の user/assistant 履歴と一緒に `POST /v1/responses` へ送信します。

### Group ごとの LLM 設定

`GET /chat/settings?group=group-1` は Group の LLM 設定を返します。
`POST /chat/settings?group=group-1` は次の JSON を受け取り、保存前に設定を検証します。

```json
{
  "baseUrl": "http://localhost:8767/v1",
  "model": "LFM2\\.5",
  "apiKey": "",
  "systemPrompt": "メインプロンプト"
}
```

- `baseUrl` は空文字にできません。`apiKey` は空文字を指定できます。
- Base URL のホスト名が `openai.com` またはそのサブドメインで `apiKey` が空文字の場合は、サーバー環境の `OPENAI_API_KEY` を使用します。環境変数も空の場合は HTTP 400 を返します。
- `model` は空文字にできず、Java 正規表現としてコンパイルできる必要があります。
- 保存前に `GET /v1/models` を実行して `model` に一致するモデル ID を 1 件特定し、そのモデルで `POST /v1/responses` を実行して空でない応答を確認します。
- 検証失敗時は HTTP 400 を返し、設定ファイルと接続中クライアントの設定は変更しません。
- 検証成功時だけ `.local/group-1/llm.json` の形式で保存し、同じ Group に接続中のクライアントは次の assistant turn から新設定を使用します。
- 設定画面のリセット値は Group ごとに異なります。`group-1` と `group-3` は `baseUrl=http://localhost:8767/v1`、`model=LFM2\\.5`、空の `apiKey`、既定メインプロンプトです。`group-2` は `baseUrl=https://api.openai.com/v1`、`model=gpt-4.1-nano`、空の `apiKey`、既定メインプロンプトです。

LFM2.5 Audio の STT/TTS は、デフォルトで `llama-liquid-audio-server` の OpenAI Chat Completions 互換エンドポイントを使います。

```text
LFM2_AUDIO_STT_URL=http://localhost:8766/v1/chat/completions
LFM2_AUDIO_STT_MODEL=lfm2-audio
LFM2_AUDIO_STT_SYSTEM_PROMPT=Perform ASR in japanese.
LFM2_AUDIO_STT_TIMEOUT_SECONDS=120

LFM2_AUDIO_TTS_URL=http://localhost:8766/v1/chat/completions
LFM2_AUDIO_TTS_MODEL=lfm2-audio
LFM2_AUDIO_TTS_SYSTEM_PROMPT=Perform TTS.
LFM2_AUDIO_TTS_TIMEOUT_SECONDS=120
```

## Message Flow

The intended server-side flow is:

```text
HTTP client
  -> POST /chat/request
  -> MlServer#handleChatRequest
  -> ChatGroup#client(sessionId)
  -> ChatClient#handle(ChatRequest)
  -> ChatClient sends ServerEvent to ChatGroup
  -> ChatGroup broadcasts ServerEvent to connected ChatClients
  -> each ChatClient queues the event
  -> GET /chat/connect stream writes queued events to HTTP client
```

This keeps `ChatGroup` as the room and `ChatClient` as the participant.
`MlServer` is responsible for HTTP routing, protocol translation, default `ChatGroup` creation, shared executor ownership, and process-wide ID generation.

## Current Limitations

- Authentication is not implemented.
- `sessionId` collision handling is not defined.
- Reconnecting with the same `sessionId` replaces the client in `ChatGroup`.
- Chat history is kept in memory per `ChatClient` and is lost when that client disconnects.
- Audio chunks are decoded as PCM16LE and passed through VAD/STT/LLM/TTS.
- Error response schema is not yet centralized.
