package dev.resumeplatform.resumeai.service;

public class NotFoundException extends DomainException {
    public NotFoundException(String message) {
        super(message);
    }
}
