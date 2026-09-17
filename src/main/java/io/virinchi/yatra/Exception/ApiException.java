package io.virinchi.yatra.Exception;

import org.springframework.http.HttpStatus;

/**
 * Base of every error the API throws on purpose.
 *
 * Carries the two things {@link GlobalExceptionHandler} needs to build the JSON
 * body: the HTTP status and the stable machine code. Subclasses just fix those
 * two values, so a service can throw `new ResourceNotFoundException(...)` and
 * the response is already correct — no per-controller try/catch, no
 * `ResponseEntity` juggling in business logic.
 *
 * Anything that is NOT an ApiException is a bug, and the handler answers 500
 * without leaking the stack trace.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
