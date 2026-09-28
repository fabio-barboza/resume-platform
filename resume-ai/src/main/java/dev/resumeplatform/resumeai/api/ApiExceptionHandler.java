package dev.resumeplatform.resumeai.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import dev.resumeplatform.resumeai.api.dto.ErrorResponse;
import dev.resumeplatform.resumeai.service.ConflictException;
import dev.resumeplatform.resumeai.service.InvalidDocumentException;
import dev.resumeplatform.resumeai.service.NotFoundException;
import jakarta.validation.ConstraintViolationException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(ConflictException ex) {
        return error(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(InvalidDocumentException.class)
    public ResponseEntity<ErrorResponse> invalidDocument(InvalidDocumentException ex) {
        return error(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage());
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HandlerMethodValidationException.class,
            ConstraintViolationException.class, HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class, MissingServletRequestPartException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> invalidRequest(Exception ex) {
        return error(HttpStatus.UNPROCESSABLE_CONTENT, "Requisição inválida: " + ex.getMessage());
    }

    private static ResponseEntity<ErrorResponse> error(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(new ErrorResponse(detail));
    }
}
