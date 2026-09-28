package dev.resumeplatform.resumeai.entrypoint.controller.sse;

public class ClientDisconnectedException extends RuntimeException {
    public ClientDisconnectedException(Throwable cause) {
        super(cause);
    }
}
