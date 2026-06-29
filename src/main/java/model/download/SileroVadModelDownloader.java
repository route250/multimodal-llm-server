package model.download;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class SileroVadModelDownloader {
    public static final URI MODEL_URI = URI.create(
            "https://raw.githubusercontent.com/snakers4/silero-vad/master/src/silero_vad/data/silero_vad.onnx");
    public static final Path MODEL_PATH = Path.of("models", "silero-vad.onnx");

    public static void main(String[] args) throws IOException, InterruptedException {
        ensureDownloaded(MODEL_URI, MODEL_PATH);
        System.out.println("Downloaded Silero VAD model: " + MODEL_PATH.toAbsolutePath().normalize());
    }

    public static void ensureDownloaded(URI uri, Path modelPath) throws IOException, InterruptedException {
        if (Files.isRegularFile(modelPath) && Files.size(modelPath) > 0) {
            return;
        }
        download(uri, modelPath);
    }

    public static void download(URI uri, Path modelPath) throws IOException, InterruptedException {
        Files.createDirectories(modelPath.toAbsolutePath().normalize().getParent());
        Path temporaryPath = modelPath.resolveSibling(modelPath.getFileName() + ".tmp");
        HttpRequest request = HttpRequest.newBuilder(uri).GET().build();
        HttpResponse<Path> response = HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofFile(temporaryPath));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            Files.deleteIfExists(temporaryPath);
            throw new IOException("failed to download model: HTTP " + response.statusCode());
        }
        if (Files.size(temporaryPath) == 0) {
            Files.deleteIfExists(temporaryPath);
            throw new IOException("downloaded model is empty");
        }
        Files.move(temporaryPath, modelPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
