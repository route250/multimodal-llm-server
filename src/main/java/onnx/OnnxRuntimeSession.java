package onnx;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

public class OnnxRuntimeSession implements AutoCloseable {
    private final OrtEnvironment environment;
    private final OrtSession session;

    public OnnxRuntimeSession(Path modelPath) {
        if (!Files.isRegularFile(modelPath)) {
            throw new OnnxModelException("ONNX model is not found: " + modelPath);
        }
        try {
            this.environment = OrtEnvironment.getEnvironment();
            OrtSession.SessionOptions options = new OrtSession.SessionOptions();
            options.setInterOpNumThreads(1);
            options.setIntraOpNumThreads(1);
            this.session = environment.createSession(modelPath.toString(), options);
        } catch (OrtException e) {
            throw new OnnxModelException("failed to create ONNX Runtime session", e);
        }
    }

    public OrtSession.Result run(Map<String, OnnxTensor> inputs) {
        try {
            return session.run(inputs);
        } catch (OrtException e) {
            throw new OnnxModelException("ONNX inference failed", e);
        }
    }

    public OrtEnvironment environment() {
        return environment;
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            throw new OnnxModelException("failed to close ONNX Runtime session", e);
        }
    }
}
