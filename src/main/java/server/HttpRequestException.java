package server;

public class HttpRequestException extends RuntimeException {
    private final int status;

    public HttpRequestException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
