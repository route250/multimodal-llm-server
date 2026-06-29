package model.download;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

public class SileroVadModelDownloader {
    public static final URI MODEL_URI = URI.create(
            "https://raw.githubusercontent.com/snakers4/silero-vad/master/src/silero_vad/data/silero_vad.onnx");
    public static final Path MODEL_PATH = Path.of("models", "silero-vad.onnx");

    public static void main(String[] args) throws IOException, InterruptedException {
        ensureDownloaded(MODEL_URI, MODEL_PATH);
        System.out.println("Downloaded Silero VAD model: " + MODEL_PATH.toAbsolutePath().normalize());
    }

    public static void ensureDownloaded(URI uri, Path modelPath) throws IOException, InterruptedException {
        ModelDownloader.ensureDownloaded(uri, modelPath);
    }

    public static void download(URI uri, Path modelPath) throws IOException, InterruptedException {
        ModelDownloader.download(uri, modelPath);
    }
}
