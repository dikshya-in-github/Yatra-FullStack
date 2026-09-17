package io.virinchi.yatra.Exception;

import org.springframework.http.HttpStatus;

/**
 * 409 — the request is well formed, but the current state of the data forbids it.
 *
 * <p>{@link DuplicateResourceException} also answers 409, so the split is
 * deliberate and worth keeping: that one means "this row breaks a **unique
 * constraint**", this one means "the row exists but something **depends on it**
 * (or its state is wrong for the operation)". The frontend branches on the codes
 * of the first kind (`EMAIL_EXISTS`, `PHONE_EXISTS`); the codes here are new, so
 * no page depends on them yet — they exist so Phase 9's admin UI can show a
 * useful message instead of a generic failure.
 *
 * <p>Both factories below replace what would otherwise be a raw database FK
 * error surfacing as HTTP 500. R4's finding is that a delete of a parent row is
 * refused by design, and a refusal should read like a refusal.
 */
public class ConflictException extends ApiException {

    public ConflictException(String code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }

    /**
     * A booking with a payment or a ticket is a sale record, not a draft — hard
     * deleting it would take the gateway transaction id and the PNR with it
     * (both are {@code unique NOT NULL} child rows, and the payments page reads
     * them). Cancelling keeps every row and only flips the status.
     */
    public static ConflictException bookingHasSaleRecords(int bookingId) {
        return new ConflictException(
                "BOOKING_HAS_SALE_RECORDS",
                "Booking " + bookingId + " has a payment or a ticket on record, so it cannot be deleted. "
                        + "Cancel it instead — that keeps the payment trail the refund flow needs.");
    }

    /** A sold flight must not disappear from under its bookings. */
    public static ConflictException flightHasBookings(String flightNo, long bookingCount) {
        return new ConflictException(
                "FLIGHT_HAS_BOOKINGS",
                "Flight " + flightNo + " has " + bookingCount + " booking(s) and cannot be deleted. "
                        + "Set its status to Inactive instead, so existing bookings stay valid.");
    }

    /**
     * R11 — an airline that has flights is a parent row for real FK purposes.
     *
     * <p>Wording is deliberately "disable it instead": the admin page already
     * has a status toggle, and it is the same phrasing `admin-destinations.js`
     * uses for this exact situation, so the real API and the mock tell the user
     * the same thing.
     */
    public static ConflictException airlineHasFlights(String airlineName, long flightCount) {
        return new ConflictException(
                "AIRLINE_HAS_FLIGHTS",
                airlineName + " is used by " + flightCount + " flight(s) — disable it instead, "
                        + "so those flights stay valid.");
    }

    /** R11 — a destination is referenced by a flight as its origin and/or destination. */
    public static ConflictException destinationHasFlights(String city, String code, long flightCount) {
        return new ConflictException(
                "DESTINATION_HAS_FLIGHTS",
                city + " (" + code + ") is used by " + flightCount + " flight(s) — disable it instead.");
    }
}
