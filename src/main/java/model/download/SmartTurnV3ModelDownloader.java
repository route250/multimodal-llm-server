package model.download;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

public final class SmartTurnV3ModelDownloader {
    public static final URI MODEL_URI = URI.create(
            "https://huggingface.co/pipecat-ai/smart-turn-v3/resolve/main/smart-turn-v3.1-cpu.onnx");
    public static final Path MODEL_PATH = Path.of(
            ".local", "opt", "smart-turn-v3", "models", "smart-turn-v3.1-cpu.onnx");

    private SmartTurnV3ModelDownloader() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        ModelDownloader.ensureDownloaded(MODEL_URI, MODEL_PATH);
        System.out.println("Downloaded Smart Turn v3 model: " + MODEL_PATH.toAbsolutePath().normalize());
    }
}
