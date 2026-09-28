package dev.resumeplatform.resumeai.core.domain.exception;

public class EmailAlreadyInUseException extends ConflictException {
    public EmailAlreadyInUseException(String email, Throwable cause) {
        super("O email '" + email + "' já pertence a outro candidato.", cause);
    }
}
