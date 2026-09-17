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
 * `(flight_id, seat_number)` seat constraint) — but that path can
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

    /**
     * The airline module's duplicate: {@code airline.iata} is UNIQUE because a
     * flight number is built from it ("U4 951"), so two carriers sharing a code
     * would produce indistinguishable flights.
     *
     * <p>No page branches on {@code IATA_EXISTS} yet — the admin page checks the
     * code client-side today and is rewired in Phase 9 — so this code is new and
     * the toast simply shows the message.
     */
    public static DuplicateResourceException iataExists() {
        return new DuplicateResourceException(
                "IATA_EXISTS", "An airline with this IATA code already exists.");
    }

    /**
     * {@code flight.flight_no} is UNIQUE — two flights sharing a number would be
     * indistinguishable on every ticket and boarding pass built from it.
     *
     * <p><b>The constraint alone would not catch the realistic duplicate.</b> The
     * column is {@code utf8mb4_bin}, so {@code "u4 951"} and {@code "U4 951"} are
     * <i>different</i> strings to the database and both would insert. The service
     * therefore normalises the number (uppercase, collapsed spaces) before this
     * check — the same reasoning as the lowercase email in {@code UserService}.
     *
     * <p>No page branches on {@code FLIGHT_NO_EXISTS} yet: the admin page checks
     * for a duplicate client-side today and is rewired in Phase 9.
     */
    public static DuplicateResourceException flightNoExists(String flightNo) {
        return new DuplicateResourceException(
                "FLIGHT_NO_EXISTS", "Flight number " + flightNo + " already exists.");
    }

    /**
     * {@code destination.code} is UNIQUE — the storefront's search and a flight's
     * route both key on this three-letter code ({@code KTM → PKR}), so two rows
     * sharing one would make a search ambiguous.
     *
     * <p>Unlike {@code IATA_EXISTS}, this rule already exists in the page:
     * {@code admin-destinations.js} refuses a code in use ("This airport code is
     * already used"), so the API answers a code the UI has effectively been
     * promising. No page branches on the string yet — the check is client-side
     * today — which is why this is a duplicate-factory rather than a new shape.
     */
    public static DuplicateResourceException destinationCodeExists(String code) {
        return new DuplicateResourceException(
                "DESTINATION_CODE_EXISTS", "Airport code " + code + " is already used by another destination.");
    }

    /**
     * The page's other duplicate rule: two rows for the same city.
     *
     * <p>There is <b>no database constraint</b> behind this one —
     * {@code admin-destinations.js} enforces it in the browser ("A destination
     * with this city name already exists"), and the wizard's arrival list would
     * show the city twice if it slipped through. It is checked in
     * {@code DestinationService}, which is honest about what a check without a
     * constraint can and cannot promise.
     */
    public static DuplicateResourceException destinationCityExists(String city) {
        return new DuplicateResourceException(
                "DESTINATION_CITY_EXISTS", "A destination for " + city + " already exists.");
    }
}
