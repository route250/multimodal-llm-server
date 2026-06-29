# Server Specs

## Overview

This server is a Java HTTP server for a multimodal chat backend.

The server has two responsibilities:

- Serve static frontend files from `src/main/resources/html`.
- Provide chat endpoints for bidirectional chat flow using HTTP requests and Server-Sent Events.

The implementation uses JDK standard `com.sun.net.httpserver.HttpServer`.
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

The server reads files from the filesystem first, so changes under `src/main/resources/html` can be served without changing Java code.
When the file is not found on the filesystem, the server tries the classpath resource under `/html/...`.

Supported methods:

- `GET`
- `HEAD`

Other methods return `405 Method Not Allowed`.

## Chat Groups

`ChatGroup` represents a chat room.

A chat room is a place where multiple clients, such as users and LLM agents, can connect and exchange events.

The server creates three default chat groups on startup:

```text
group-1
group-2
group-3
```

When the `group` query parameter is omitted, `group-1` is used.

If a request specifies an unknown group, the server returns:

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
- Receive events from the `ChatGroup`.
- Handle incoming `ChatRequest`.
- Send resulting events to its `ChatGroup`.

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
message
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
audio/pcm         Treated as a PCM16LE 16kHz mono audio chunk when rate=16000, channels=1, format=s16le.
other             Treated as binary.
```

Current `ChatRequest` conversion is a test implementation:

```text
text   -> received text: ...
audio  -> accepted by the per-client audio buffer and VAD processor.
binary -> received binary chunk: N bytes (...)
```

Audio requests must use:

```http
Content-Type: audio/pcm; rate=16000; channels=1; format=s16le
```

Each `ChatClient` keeps a 6 second receive buffer and a 30 second STT buffer.
The receive buffer stores PCM samples and VAD values in 512 sample units.
VAD runs once per 512 samples, which is 32 ms at 16 kHz.
The Silero ONNX model receives 512 audio samples plus 64 samples of context.

Speech starts when the VAD value is at least `0.5`.
When speech starts, the target range includes the previous 0.6 seconds of audio.
Speech ends when the VAD value stays at or below `0.35` for 0.6 seconds.
On speech end, the speech range is appended to the STT buffer using PCM sample indexes so overlapping ranges are not duplicated.
The current STT implementation is a dummy implementation and emits:

```text
dummy stt result
```

Silero VAD uses an ONNX model at:

```text
models/silero-vad.onnx
```

If the model file does not exist or is 0 bytes, the server downloads it during the first audio request.
The model can also be downloaded explicitly before sending audio:

```bash
mvn -q -DskipTests compile exec:java -Dexec.mainClass=model.download.SileroVadModelDownloader
```

## Message Flow

The intended server-side flow is:

```text
HTTP client
  -> POST /chat/request
  -> Server#handleChatRequest
  -> ChatGroup#client(sessionId)
  -> ChatClient#handle(ChatRequest)
  -> ChatClient sends ServerEvent to ChatGroup
  -> ChatGroup broadcasts ServerEvent to connected ChatClients
  -> each ChatClient queues the event
  -> GET /chat/connect stream writes queued events to HTTP client
```

This keeps `ChatGroup` as the room and `ChatClient` as the participant.
`Server` is responsible for HTTP routing and protocol translation only.

## Current Limitations

- Authentication is not implemented.
- `sessionId` collision handling is not defined.
- Reconnecting with the same `sessionId` replaces the client in `ChatGroup`.
- Chat history is not stored.
- Audio chunks are accepted as bytes, but no decoding or transcription is implemented.
- LLM integration is not implemented.
- Error response schema is not yet centralized.
