package io.virinchi.yatra.Exception;

import org.springframework.http.HttpStatus;

/**
 * 404 — the row the caller asked for does not exist.
 *
 * Use {@link #of(String, Object)} for the normal case: it derives the machine
 * code from the resource name, so `of("Booking", id)` answers
 * `BOOKING_NOT_FOUND` — which is exactly the code the frontend's mock layer
 * already returns for a missing booking (`api.js`'s mock GET route), so the UI
 * needs no change when the real API takes over.
 *
 * Only needed when the resource name does not map cleanly — otherwise prefer
 * the factory, so every "not found" in the project speaks one code style.
 */
public class ResourceNotFoundException extends ApiException {

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public ResourceNotFoundException(String code, String message) {
        super(HttpStatus.NOT_FOUND, code, message);
    }

    /**
     * `of("Booking", 7)` → code `BOOKING_NOT_FOUND`, message
     * "No Booking exists with id 7.".
     */
    public static ResourceNotFoundException of(String resource, Object id) {
        return new ResourceNotFoundException(
                resource.toUpperCase().replaceAll("[^A-Z0-9]+", "_") + "_NOT_FOUND",
                "No " + resource + " exists with id " + id + ".");
    }
}
