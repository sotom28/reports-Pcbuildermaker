package com.example.reports_service.exception;

/** Excepciones de dominio; GlobalExceptionHandler las traduce a HTTP. */
public final class ApiExceptions {

    private ApiExceptions() {
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public static class UnauthorizedException extends RuntimeException {
        public UnauthorizedException(String message) {
            super(message);
        }
    }

    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) {
            super(message);
        }
    }

    /** Estado no permitido para la operacion (409). */
    public static class ConflictException extends RuntimeException {
        public ConflictException(String message) {
            super(message);
        }
    }
}
