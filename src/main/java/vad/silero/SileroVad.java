package vad.silero;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import onnx.OnnxModelException;
import onnx.OnnxRuntimeSession;

public class SileroVad implements VoiceActivityDetector, AutoCloseable {
    public static final int SAMPLE_RATE = 16_000;
    public static final int CHUNK_SAMPLES = 512;
    public static final int CONTEXT_SAMPLES = 64;

    private final OnnxRuntimeSession session;
    private float[][][] state = new float[2][1][128];
    private final float[] context = new float[CONTEXT_SAMPLES];

    public SileroVad(Path modelPath) {
        this.session = new OnnxRuntimeSession(modelPath);
    }

    @Override
    public synchronized float speechProbability(float[] samples) {
        if (samples.length != CHUNK_SAMPLES) {
            throw new IllegalArgumentException("Silero VAD chunk must be " + CHUNK_SAMPLES + " samples");
        }
        float[] modelInput = new float[CONTEXT_SAMPLES + CHUNK_SAMPLES];
        System.arraycopy(context, 0, modelInput, 0, CONTEXT_SAMPLES);
        System.arraycopy(samples, 0, modelInput, CONTEXT_SAMPLES, CHUNK_SAMPLES);
        ChunkResult result = runChunk(modelInput, state);
        state = result.state();
        System.arraycopy(samples, CHUNK_SAMPLES - CONTEXT_SAMPLES, context, 0, CONTEXT_SAMPLES);
        return result.probability();
    }

    public synchronized void reset() {
        state = new float[2][1][128];
        for (int i = 0; i < context.length; i++) {
            context[i] = 0.0f;
        }
    }

    private ChunkResult runChunk(float[] samples, float[][][] currentState) {
        try (OnnxTensor input = OnnxTensor.createTensor(session.environment(), new float[][]{samples});
                OnnxTensor state = OnnxTensor.createTensor(session.environment(), currentState);
                OnnxTensor sampleRate = OnnxTensor.createTensor(session.environment(), (long) SAMPLE_RATE);
                OrtSession.Result result = session.run(inputs(input, state, sampleRate))) {
            float probability = probability(result.get(0).getValue());
            Object nextState = result.get(1).getValue();
            if (nextState instanceof float[][][] values) {
                return new ChunkResult(probability, values);
            }
            throw new OnnxModelException("unexpected Silero VAD state output type: " + nextState.getClass().getName());
        } catch (OrtException e) {
            throw new OnnxModelException("failed to run Silero VAD", e);
        }
    }

    private static float probability(Object value) {
        if (value instanceof float[][] values) {
            return values[0][0];
        }
        if (value instanceof float[] values) {
            return values[0];
        }
        throw new OnnxModelException("unexpected Silero VAD output type: " + value.getClass().getName());
    }

    private static Map<String, OnnxTensor> inputs(OnnxTensor input, OnnxTensor state, OnnxTensor sampleRate) {
        Map<String, OnnxTensor> inputs = new HashMap<>();
        inputs.put("input", input);
        inputs.put("state", state);
        inputs.put("sr", sampleRate);
        return inputs;
    }

    @Override
    public void close() {
        session.close();
    }

    private record ChunkResult(float probability, float[][][] state) {
    }
}
