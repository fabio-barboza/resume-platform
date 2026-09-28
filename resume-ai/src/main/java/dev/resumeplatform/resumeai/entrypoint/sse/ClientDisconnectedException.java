package dev.resumeplatform.resumeai.entrypoint.sse;

public class ClientDisconnectedException extends RuntimeException {
    public ClientDisconnectedException(Throwable cause) {
        super(cause);
    }
}
