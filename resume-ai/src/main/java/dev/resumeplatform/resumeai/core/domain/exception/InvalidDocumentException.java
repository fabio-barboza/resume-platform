package dev.resumeplatform.resumeai.core.domain.exception;

public class InvalidDocumentException extends DomainException {
    public InvalidDocumentException(String message) {
        super(message);
    }

    public InvalidDocumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
