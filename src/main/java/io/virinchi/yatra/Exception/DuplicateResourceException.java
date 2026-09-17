package io.virinchi.yatra.Exception;

import org.springframework.http.HttpStatus;

/**
 * 409 — the write breaks a unique constraint.
 *
 * The two factories exist because the codes they produce are **already in the
 * frontend**: `assets/js/profile.js` and `admin-profile.js` branch on
 * `EMAIL_EXISTS` / `PHONE_EXISTS` to paint the offending field and show the
 * duplicate toast. The mock API returns those exact codes today, so a service
 * must throw these factories (check-then-throw) rather than letting the
 * database blow up, or the real API would answer a different code from the
 * mock and the field painting would silently stop working.
 *
 * {@link GlobalExceptionHandler} still maps a raw
 * `DataIntegrityViolationException` to 409 as a safety net (for the
 * `(flight_id, seat_number)` seat constraint in Phase 6) — but that path can
 * only give the generic `DUPLICATE_RESOURCE` code, because MySQL's error text
 * does not say which endpoint or field was at fault.
 */
public class DuplicateResourceException extends ApiException {

    public DuplicateResourceException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }

    /** The message deliberately does not echo the address back — no need to
        put a user's email in a log line or a response body. */
    public static DuplicateResourceException emailExists() {
        return new DuplicateResourceException(
                "EMAIL_EXISTS", "An account with this email already exists.");
    }

    public static DuplicateResourceException phoneExists() {
        return new DuplicateResourceException(
                "PHONE_EXISTS", "An account with this mobile number already exists.");
    }
}
