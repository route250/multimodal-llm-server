package model.download;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class ModelDownloader {
    private ModelDownloader() {
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
        HttpResponse<Path> response = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()
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
