package com.example.reports_service.exception;

import com.example.reports_service.exception.ApiExceptions.ConflictException;
import com.example.reports_service.exception.ApiExceptions.ForbiddenException;
import com.example.reports_service.exception.ApiExceptions.NotFoundException;
import com.example.reports_service.exception.ApiExceptions.UnauthorizedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
// se encarga de manejar las excepciones globales
@RestControllerAdvice
public class GlobalExceptionHandler {
    // 
    public record ErrorResponse(int status, String message, Instant timestamp) {
        static ErrorResponse of(HttpStatus status, String message) {
            return new ErrorResponse(status.value(), message, Instant.now());
        }
    }

    private static ResponseEntity<ErrorResponse> respond(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ErrorResponse.of(status, message));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NotFoundException ex) {
        return respond(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> unauthorized(UnauthorizedException ex) {
        return respond(HttpStatus.UNAUTHORIZED, ex.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> forbidden(ForbiddenException ex) {
        return respond(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ErrorResponse> conflict(ConflictException ex) {
        return respond(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badRequest(IllegalArgumentException ex) {
        return respond(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ErrorResponse> unreadable(Exception ex) {
        return respond(HttpStatus.BAD_REQUEST, "Solicitud mal formada: revisa el cuerpo o los parametros");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> validation(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.put(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.badRequest().body(errors);
    }
}
