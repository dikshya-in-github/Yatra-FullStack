package io.virinchi.yatra.Exception;

import io.virinchi.yatra.Dto.ErrorResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLException;
import java.util.stream.Collectors;

/**
 * Turns every exception thrown by a REST controller into the project's one
 * error body — `{ "error": "CODE", "message": "…" }`.
 *
 * <p><b>Why built before any endpoint exists:</b> every module
 * throws {@link ApiException} subclasses. Doing this now means no controller
 * ever needs a try/catch, and no endpoint can ever answer Spring's default
 * error page. Retrofitting it later means revisiting every controller.
 *
 * <p><b>Scope — REST only, on purpose.</b> {@code annotations =
 * RestController.class} limits this advice to JSON controllers. The project is
 * hybrid: the 34 pages are Thymeleaf views served by plain {@code @Controller}
 * classes, and a browser hitting a broken page must get HTML, not this JSON.
 * Without the restriction, an unhandled error on e.g. {@code /booking} would
 * return a JSON body to a browser. Page-side errors therefore keep Spring's
 * normal handling (and a later {@code @ControllerAdvice} can add friendly HTML
 * error pages) — only the {@code /api/**} layer that {@code api.js} talks to
 * gets this shape.
 *
 * <p>One thing this advice deliberately does NOT handle:
 * {@code AccessDeniedException} thrown by the security <i>filter chain</i>
 * (unauthenticated requests) never reaches a controller, so
 * {@code SecurityConfig} owns the 401 there via an {@code AuthenticationEntryPoint}.
 * The handler below only covers a 403 raised by {@code @PreAuthorize} <i>inside</i>
 * a controller method.
 */
@RestControllerAdvice(annotations = RestController.class)
@Slf4j
public class GlobalExceptionHandler {

    /* ---------- 1. Our own errors — the normal path ---------- */

    /** Every {@link ApiException} already knows its status and code. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ErrorResponse> handleApiException(ApiException ex) {
        log.warn("API error [{}] -> HTTP {}: {}", ex.getCode(), ex.getStatus().value(), ex.getMessage());
        return ResponseEntity.status(ex.getStatus())
                .body(new ErrorResponse(ex.getCode(), ex.getMessage()));
    }

    /* ---------- 2. Bean Validation ---------- */

    /**
     * {@code @Valid} on a request body failed. Field errors are joined so the
     * caller sees everything wrong at once instead of one field per request.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::describe)
                .collect(Collectors.joining("; "));

        if (message.isBlank()) {
            message = ex.getBindingResult().getGlobalErrors().stream()
                    .map(error -> String.valueOf(error.getDefaultMessage()))
                    .collect(Collectors.joining("; "));
        }
        if (message.isBlank()) {
            message = "Request body failed validation.";
        }

        log.warn("Body validation failed -> {}", message);
        return badRequest(message);
    }

    /**
     * {@code @Validated} on path/query parameters failed, e.g.
     * {@code @PathVariable @Min(1) int id}.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleParameterValidation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(GlobalExceptionHandler::describe)
                .collect(Collectors.joining("; "));

        if (message.isBlank()) {
            // A ConstraintViolationException built without violation objects
            // still carries a usable message — never answer an empty one.
            message = ex.getMessage() != null ? ex.getMessage() : "Request failed validation.";
        }

        log.warn("Parameter validation failed -> {}", message);
        return badRequest(message);
    }

    /**
     * The body was absent, not JSON, or not coercible (e.g. `"id": "abc"`).
     * Postman testing hits this constantly, and without this handler the
     * catch-all below would turn a 400 into a confusing 500.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("Unreadable request body: {}", ex.getMessage());
        return badRequest("Request body is missing or is not valid JSON.");
    }

    /* ---------- 3. Database constraints ---------- */

    /* MySQL/TiDB driver error codes (measured, see the method docs). */
    private static final int DUPLICATE_ENTRY = 1062;    // unique key violated
    private static final int ROW_IS_REFERENCED = 1451;  // ...and something still points at this row
    private static final int NO_REFERENCED_ROW = 1452;  // ...and the row it points at does not exist

    /**
     * Safety net for a constraint no service pre-checked (the schema has several:
     * user email/phone, airline IATA, destination code, flight number, payment
     * txn, ticket PNR, and the `(flight_id, seat_number)` seat constraint
     * the whole double-booking rule rests on).
     *
     * <p><b>Why the classification below exists.</b> This handler used to
     * call <i>every</i> integrity failure a duplicate and answer "That record
     * already exists — it breaks a unique constraint." Measured on this stack
     * (Hibernate 7 + Spring Data + TiDB Cloud), that was wrong for one of the
     * most common failures in the API — deleting a row that something still
     * references. A unique violation, a "still referenced" FK violation and a
     * "missing reference" FK violation arrive as the **same exception type**, a
     * plain {@code DataIntegrityViolationException} (measured — deliberately
     * *not* {@code DuplicateKeyException}, so a type-based split would mislabel
     * them just as badly), and differ only in the driver's error code:
     * {@code 1062} vs {@code 1451} vs {@code 1452}.
     *
     * <p>So the discriminator is the vendor error code, with an honest generic
     * fallback for anything unrecognised. Those are MySQL/TiDB codes — the engine
     * this project is pinned to — and on another engine the response would
     * fall through to {@code CONSTRAINT_VIOLATION} rather than lie.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(DataIntegrityViolationException ex) {
        return integrityViolation(ex.getMostSpecificCause());
    }

    /**
     * The same failures, caught one layer lower: a service that uses
     * {@code EntityManager} directly (a bulk JPQL delete, for instance) never
     * passes through a repository proxy, so Spring's exception translation does
     * not run and the raw Hibernate exception surfaces — measured.
     *
     * <p>Without this, that path would reach the catch-all and answer 500 for
     * something that is a perfectly ordinary 409.
     */
    @ExceptionHandler(org.hibernate.exception.ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleHibernateConstraintViolation(
            org.hibernate.exception.ConstraintViolationException ex) {
        return integrityViolation(ex);
    }

    /* ---------- 4. Security ---------- */

    /**
     * 403 from a method-level {@code @PreAuthorize} denial. Returning our JSON
     * here keeps the error shape consistent, and — just as importantly — stops
     * the catch-all below from turning a 403 into a 500.
     *
     * <p>This is only the <i>method</i>-security path. An unauthenticated
     * request is rejected by the filter chain, long before a controller runs,
     * so that 401/403 stays with {@code SecurityConfig}.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        log.warn("Access denied (method security): {}", ex.getMessage());
        return ResponseEntity.status(403).body(
                new ErrorResponse("FORBIDDEN", "You do not have permission to perform this action."));
    }

    /* ---------- 5. Spring's own status-bearing exceptions ---------- */

    /**
     * Anything thrown that already carries a status (`ResponseStatusException`
     * and friends) keeps that status instead of decaying to 500 — only the body
     * is replaced with the project's shape.
     */
    @ExceptionHandler(ErrorResponseException.class)
    public ResponseEntity<ErrorResponse> handleStatusBearing(ErrorResponseException ex) {
        int status = ex.getStatusCode().value();
        String detail = ex.getBody().getDetail();
        if (detail == null || detail.isBlank()) {
            detail = "Request failed with status " + status + ".";
        }

        log.warn("Spring web error -> HTTP {}: {}", status, detail);
        return ResponseEntity.status(status).body(new ErrorResponse("HTTP_" + status, detail));
    }

    /* ---------- 6. Last resort ---------- */

    /**
     * A non-ApiException is a bug, not a user error: log it with the stack
     * trace and answer a generic 500. The message is deliberately fixed — an
     * exception message can carry SQL, file paths, or a password, and this body
     * goes straight to the browser.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex) {
        log.error("Unhandled exception (this is a bug, not a bad request)", ex);
        return ResponseEntity.status(500).body(new ErrorResponse(
                "INTERNAL_SERVER_ERROR", "Something went wrong on the server. Please try again."));
    }

    /* ---------- helpers ---------- */

    /**
     * One integrity failure, three honest answers: duplicate, still-in-use, or a
     * reference that does not exist — never the wrong one.
     */
    private ResponseEntity<ErrorResponse> integrityViolation(Throwable cause) {
        SQLException sql = findSqlException(cause);
        int errorCode = sql != null ? sql.getErrorCode() : 0;
        String sqlState = sql != null ? sql.getSQLState() : null;

        // The code is for the log, never for the response: it names the table or
        // constraint, and this body goes straight to the browser.
        log.warn("Database integrity violation (errorCode={}, sqlState={}): {}",
                errorCode, sqlState, cause.getMessage());

        if (errorCode == DUPLICATE_ENTRY) {
            return conflict("DUPLICATE_RESOURCE",
                    "That record already exists — it breaks a unique constraint.");
        }
        if (errorCode == ROW_IS_REFERENCED) {
            return conflict("RECORD_IN_USE",
                    "This record is still referenced by other records, so it cannot be removed — disable it instead.");
        }
        if (errorCode == NO_REFERENCED_ROW) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "INVALID_REFERENCE", "A linked record does not exist."));
        }
        return conflict("CONSTRAINT_VIOLATION",
                "The database refused this change — a value is missing, duplicated or out of range.");
    }

    /** Finds the driver exception at the bottom of the cause chain, if any. */
    private static SQLException findSqlException(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof SQLException sqlException) {
                return sqlException;
            }
        }
        return null;
    }

    private static ResponseEntity<ErrorResponse> conflict(String code, String message) {
        return ResponseEntity.status(409).body(new ErrorResponse(code, message));
    }

    private static String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private static String describe(ConstraintViolation<?> violation) {
        return violation.getPropertyPath() + ": " + violation.getMessage();
    }

    private static ResponseEntity<ErrorResponse> badRequest(String message) {
        return ResponseEntity.badRequest().body(new ErrorResponse("VALIDATION_FAILED", message));
    }
}
