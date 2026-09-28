package dev.resumeplatform.resumeai.core.domain.exception;

/**
 * O {@code file_hash} já pertence a outro documento. Sinal interno da ingestão, não chega ao HTTP: quem grava
 * decide se isso é no-op (POST) ou conflito (PUT).
 */
public class DuplicateFileException extends RuntimeException {
    public DuplicateFileException(Throwable cause) {
        super(cause);
    }
}
