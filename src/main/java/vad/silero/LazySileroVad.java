package vad.silero;

import java.nio.file.Path;
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
                delegate = new SileroVad(modelPath);
            } catch (OnnxModelException e) {
                throw new OnnxModelException(
                        "Silero VAD model is missing. Download it with: mvn -q -DskipTests compile exec:java -Dexec.mainClass=model.download.SileroVadModelDownloader",
                        e);
            }
        }
        return delegate.speechProbability(samples);
    }
}
