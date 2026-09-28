package dev.resumeplatform.resumeai.service;

public class InvalidDocumentException extends DomainException {
    public InvalidDocumentException(String message) {
        super(message);
    }

    public InvalidDocumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
