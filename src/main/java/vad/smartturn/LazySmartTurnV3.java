package vad.smartturn;

import java.io.IOException;
import java.nio.file.Path;
import model.download.ModelDownloader;
import model.download.SmartTurnV3ModelDownloader;
import onnx.OnnxModelException;
import vad.TurnDetector;

public class LazySmartTurnV3 implements TurnDetector, AutoCloseable {
    private final Path modelPath;
    private final float completeThreshold;
    private SmartTurnV3 delegate;

    public LazySmartTurnV3(Path modelPath) {
        this(modelPath, SmartTurnV3.DEFAULT_COMPLETE_THRESHOLD);
    }

    public LazySmartTurnV3(Path modelPath, float completeThreshold) {
        this.modelPath = modelPath;
        this.completeThreshold = completeThreshold;
    }

    public synchronized SmartTurnV3.Prediction predict(float[] samples) {
        return delegate().predict(samples);
    }

    public synchronized SmartTurnV3.Prediction predictPcm16Le(byte[] bytes) {
        return delegate().predictPcm16Le(bytes);
    }

    public synchronized float completionProbability(float[] samples) {
        return delegate().completionProbability(samples);
    }

    @Override
    public synchronized boolean isTurnComplete(float[] samples) {
        return predict(samples).complete();
    }

    private SmartTurnV3 delegate() {
        if (delegate == null) {
            try {
                ModelDownloader.ensureDownloaded(SmartTurnV3ModelDownloader.MODEL_URI, modelPath);
                delegate = new SmartTurnV3(modelPath, completeThreshold);
            } catch (IOException e) {
                throw new OnnxModelException("failed to download Smart Turn v3 model to " + modelPath, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new OnnxModelException("interrupted while downloading Smart Turn v3 model to " + modelPath, e);
            } catch (OnnxModelException e) {
                throw new OnnxModelException("failed to load Smart Turn v3 model from " + modelPath, e);
            }
        }
        return delegate;
    }

    @Override
    public synchronized void close() {
        if (delegate != null) {
            delegate.close();
        }
    }
}
