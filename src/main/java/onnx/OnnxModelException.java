package onnx;

public class OnnxModelException extends RuntimeException {
    public OnnxModelException(String message) {
        super(message);
    }

    public OnnxModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
