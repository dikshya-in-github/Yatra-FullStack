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
 * no page depends on them yet — they exist so the admin UI can show a
 * useful message instead of a generic failure.
 *
 * <p>Both factories below replace what would otherwise be a raw database FK
 * error surfacing as HTTP 500. A delete of a parent row is
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
     * An airline that has flights is a parent row for real FK purposes.
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

    /**
     * A flight cannot be shrunk below the seats already sold on it.
     *
     * <p>The capacity figure is what the available-seat sum is computed from
     * ({@code available = seatCapacity − booked}), so reducing it under the booked
     * count would make the flight report a negative number of free seats — and
     * the database would not object, because the {@code seat} rows it contradicts
     * are already there.
     */
    public static ConflictException capacityBelowBookedSeats(String flightNo, long booked, int requested) {
        return new ConflictException(
                "CAPACITY_BELOW_BOOKED_SEATS",
                "Flight " + flightNo + " already has " + booked + " booked seat(s), so its capacity "
                        + "cannot be set to " + requested + ".");
    }

    /**
     * The seat is taken — the refusal rule 2 exists for ({@code SEAT_ALREADY_BOOKED}).
     *
     * <p>Raised by the atomic claim in {@code BookingService}, not by a raw
     * constraint violation: measured on this stack the seat's
     * {@code (flight_id, seat_number)} unique key cannot catch a second booking of
     * an already-generated row, so the guard has to be a conditional
     * {@code UPDATE} that the database arbitrates. The message names the flight and
     * the seat because that is what the customer has to act on — pick another one.
     */
    public static ConflictException seatAlreadyBooked(String flightNo, String seatNumber) {
        return new ConflictException(
                "SEAT_ALREADY_BOOKED",
                "Seat " + seatNumber + " on flight " + flightNo
                        + " is already booked. Choose a different seat.");
    }

    /**
     * The flight has fewer free seats than the booking needs.
     *
     * <p>Distinct from {@link #seatAlreadyBooked} on purpose: one means "that seat
     * went to somebody else", the other means "this flight is full" — and the
     * booking page can offer a different flight for the second but not the first.
     */
    public static ConflictException notEnoughSeats(String flightNo, int requested, long available) {
        return new ConflictException(
                "FLIGHT_SOLD_OUT",
                "Flight " + flightNo + " has " + available + " seat(s) left, but this booking needs "
                        + requested + ".");
    }

    /** A destination is referenced by a flight as its origin and/or destination. */
    public static ConflictException destinationHasFlights(String city, String code, long flightCount) {
        return new ConflictException(
                "DESTINATION_HAS_FLIGHTS",
                city + " (" + code + ") is used by " + flightCount + " flight(s) — disable it instead.");
    }
}
