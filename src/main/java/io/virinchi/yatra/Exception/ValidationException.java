package io.virinchi.yatra.Exception;

import org.springframework.http.HttpStatus;

/**
 * 400 — the request was understood but the values are not acceptable.
 *
 * This is the exception for business-rule failures that Bean Validation cannot
 * express, e.g. "return date cannot be before the departure date", "same origin
 * and destination", "cannot confirm a booking that has no successful payment".
 * Bean Validation failures (`@Valid` on a DTO, `@Validated` path variables) are
 * handled separately in {@link GlobalExceptionHandler} but answer the same
 * `VALIDATION_FAILED` code, so the frontend has one thing to check.
 */
public class ValidationException extends ApiException {

    public ValidationException(String message) {
        super(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    public ValidationException(String code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }
}
