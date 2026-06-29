package server;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import model.download.SileroVadModelDownloader;
import onnx.OnnxModelException;
import stt.DummySpeechToText;
import vad.silero.LazySileroVad;
import vad.silero.VadAudioProcessor;

public class ChatClient {
    private final String id;
    private final ChatGroup chatGroup;
    private final LinkedBlockingQueue<ServerEvent> events = new LinkedBlockingQueue<>();
    private final VadAudioProcessor audioProcessor = new VadAudioProcessor(
            new LazySileroVad(modelPath()), new DummySpeechToText());

    public ChatClient(String id, ChatGroup chatGroup) {
        this.id = id;
        this.chatGroup = chatGroup;
    }

    public String id() {
        return id;
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
        try {
            Optional<String> transcript = audioProcessor.acceptPcm16Le(request.body());
            transcript.ifPresent(value -> sendToGroup(ServerEvent.message(value)));
        } catch (IllegalArgumentException e) {
            throw new HttpRequestException(400, e.getMessage());
        } catch (OnnxModelException e) {
            throw new HttpRequestException(500, e.getMessage());
        }
    }

    private void sendToGroup(ServerEvent event) {
        chatGroup.publish(event);
    }

    public void receive(ServerEvent event) {
        events.offer(event);
    }

    private static Path modelPath() {
        return SileroVadModelDownloader.MODEL_PATH.toAbsolutePath().normalize();
    }
}
