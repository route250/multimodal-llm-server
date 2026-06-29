package vad.silero;

import java.io.IOException;
import java.nio.file.Path;
import model.download.SileroVadModelDownloader;
import onnx.OnnxModelException;

public class LazySileroVad implements VoiceActivityDetector {
    private final Path modelPath;
    private SileroVad delegate;

    public LazySileroVad(Path modelPath) {
        this.modelPath = modelPath;
    }

    @Override
    public synchronized float speechProbability(float[] samples) {
        if (delegate == null) {
            try {
                SileroVadModelDownloader.ensureDownloaded(SileroVadModelDownloader.MODEL_URI, modelPath);
                delegate = new SileroVad(modelPath);
            } catch (IOException e) {
                throw new OnnxModelException("failed to download Silero VAD model to " + modelPath, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OnnxModelException("interrupted while downloading Silero VAD model to " + modelPath, e);
            } catch (OnnxModelException e) {
                throw new OnnxModelException(
                        "failed to load Silero VAD model from " + modelPath,
                        e);
            }
        }
        return delegate.speechProbability(samples);
    }
}
