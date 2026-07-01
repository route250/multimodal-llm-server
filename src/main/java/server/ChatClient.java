package server;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import model.download.SmartTurnV3ModelDownloader;
import model.download.SileroVadModelDownloader;
import onnx.OnnxModelException;
import stt.SpeechToTextException;
import stt.WhisperServerSpeechToText;
import vad.VadAudioProcessor;
import vad.silero.LazySileroVad;
import vad.smartturn.LazySmartTurnV3;

public class ChatClient {
    private final String id;
    private final ChatGroup chatGroup;
    private final LinkedBlockingQueue<ServerEvent> events = new LinkedBlockingQueue<>();
    private final VadAudioProcessor audioProcessor;
    private final Object audioTaskLock = new Object();
    private final Object lifecycleLock = new Object();
    private CompletableFuture<Void> audioTaskTail = CompletableFuture.completedFuture(null);
    private boolean closed;

    public ChatClient(String id, ChatGroup chatGroup) {
        this(id, chatGroup, new VadAudioProcessor(
                new LazySileroVad(sileroModelPath()),
                new LazySmartTurnV3(smartTurnModelPath()),
                new WhisperServerSpeechToText(),
                chatGroup::execute));
    }

    ChatClient(String id, ChatGroup chatGroup, VadAudioProcessor audioProcessor) {
        this.id = id;
        this.chatGroup = chatGroup;
        this.audioProcessor = audioProcessor;
    }

    public String id() {
        return id;
    }

    public int nextId() {
        return this.chatGroup.nextId();
    }
    public void execute(Runnable r) {
        this.chatGroup.execute(r);
    }
    public Future<?> submit(Runnable task) {
        return this.chatGroup.submit(task);
    }
    public <T> Future<T> submit(Runnable task, T result ) {
        return this.chatGroup.submit(task,result);
    }
    public <T> Future<T> submit(Callable<T> task) {
        return this.chatGroup.submit(task);
    }

    public LinkedBlockingQueue<ServerEvent> events() {
        return events;
    }

    public void handle(ChatRequest request) {
        if ("audio".equals(request.type())) {
            handleAudio(request);
            return;
        }
        sendToGroup(request.toEvent());
    }

    private void handleAudio(ChatRequest request) {
        if (!request.isPcm16LeAudio()) {
            throw new HttpRequestException(415,
                    "unsupported audio content type. Use: audio/pcm; rate=16000; channels=1; format=s16le");
        }
        byte[] body = request.body().clone();
        synchronized (audioTaskLock) {
            audioTaskTail = audioTaskTail.handle((ignored, error) -> null)
                    .thenRunAsync(() -> processAudio(body), this::execute);
        }
    }

    private void processAudio(byte[] body) {
        if (isClosed()) {
            return;
        }
        try {
            Optional<String> transcript = audioProcessor.acceptPcm16Le(body);
            transcript.ifPresent(value -> sendToGroupIfOpen(ServerEvent.message(value)));
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

    private void sendAudioProcessingFailure(RuntimeException e) {
        sendToGroupIfOpen(ServerEvent.system("audio processing failed: " + e.getMessage()));
    }

    private void sendToGroupIfOpen(ServerEvent event) {
        synchronized (lifecycleLock) {
            if (!closed) {
                sendToGroup(event);
            }
        }
    }

    private void sendToGroup(ServerEvent event) {
        chatGroup.publish(event);
    }

    public void receive(ServerEvent event) {
        events.offer(event);
    }

    public void close() {
        synchronized (lifecycleLock) {
            closed = true;
        }
    }

    private boolean isClosed() {
        synchronized (lifecycleLock) {
            return closed;
        }
    }

    private static Path sileroModelPath() {
        return SileroVadModelDownloader.MODEL_PATH.toAbsolutePath().normalize();
    }

    private static Path smartTurnModelPath() {
        return SmartTurnV3ModelDownloader.MODEL_PATH.toAbsolutePath().normalize();
    }
}
