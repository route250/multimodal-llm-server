package audio.vad.smartturn;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import audio.Pcm16Le;
import audio.feature.LogMelFeatureExtractor;
import java.nio.file.Path;
import java.util.Map;
import onnx.OnnxModelException;
import onnx.OnnxRuntimeSession;

public class SmartTurnV3 implements AutoCloseable {
    public static final int SAMPLE_RATE = LogMelFeatureExtractor.SAMPLE_RATE;
    public static final int WINDOW_SECONDS = LogMelFeatureExtractor.CHUNK_SECONDS;
    public static final int WINDOW_SAMPLES = LogMelFeatureExtractor.CHUNK_SAMPLES;
    public static final float DEFAULT_COMPLETE_THRESHOLD = 0.5f;

    private final OnnxRuntimeSession session;
    private final LogMelFeatureExtractor featureExtractor;
    private final float completeThreshold;

    public SmartTurnV3(Path modelPath) {
        this(modelPath, DEFAULT_COMPLETE_THRESHOLD);
    }

    public SmartTurnV3(Path modelPath, float completeThreshold) {
        if (completeThreshold < 0.0f || completeThreshold > 1.0f) {
            throw new IllegalArgumentException("completeThreshold must be between 0.0 and 1.0");
        }
        this.session = new OnnxRuntimeSession(modelPath);
        this.featureExtractor = new LogMelFeatureExtractor();
        this.completeThreshold = completeThreshold;
    }

    public synchronized Prediction predict(float[] samples) {
        float probability = completionProbability(samples);
        return new Prediction(probability > completeThreshold, probability);
    }

    public synchronized Prediction predictPcm16Le(byte[] bytes) {
        return predict(toFloatSamples(Pcm16Le.decode(bytes)));
    }

    public synchronized float completionProbability(float[] samples) {
        float[][] inputFeatures = featureExtractor.extract(samples);
        try (OnnxTensor input = OnnxTensor.createTensor(session.environment(), new float[][][]{inputFeatures});
                OrtSession.Result result = session.run(Map.of("input_features", input))) {
            return probability(result.get(0).getValue());
        } catch (OrtException e) {
            throw new OnnxModelException("failed to run Smart Turn v3", e);
        }
    }

    public float completeThreshold() {
        return completeThreshold;
    }

    @Override
    public void close() {
        session.close();
    }

    private static float probability(Object value) {
        if (value instanceof float[][] values) {
            return values[0][0];
        }
        if (value instanceof float[] values) {
            return values[0];
        }
        if (value instanceof float[][][] values) {
            return values[0][0][0];
        }
        throw new OnnxModelException("unexpected Smart Turn v3 output type: " + value.getClass().getName());
    }

    private static float[] toFloatSamples(short[] samples) {
        float[] values = new float[samples.length];
        for (int i = 0; i < samples.length; i++) {
            values[i] = samples[i] / 32768.0f;
        }
        return values;
    }

    public record Prediction(boolean complete, float probability) {
        public int label() {
            return complete ? 1 : 0;
        }
    }
}
