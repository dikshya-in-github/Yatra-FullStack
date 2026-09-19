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

    /**
     * A booking cannot be confirmed without a successful payment — the business rule
     * Roadmap Phase 9 names explicitly.
     *
     * <p>This is the rule that keeps {@code CONFIRMED} meaning something: a booking
     * is created {@code PENDING} by the wizard <i>before</i> the gateway is called
     * (Phase 6's hold decision), so without this gate an admin — or a caller who
     * found the endpoint — could mark an unpaid booking as sold and generate nothing
     * but a contradiction. The check reads the {@code payment} row's own status
     * ({@code SUCCESS}), not the booking's {@code paymentStatus} text, because the
     * payment row is the record of what the gateway actually answered.
     *
     * <p>The message names the payment status it saw rather than the booking id
     * alone, so a Postman caller can tell "no payment row at all" from "the payment
     * failed" without a second request.
     */
    public static ConflictException confirmationRequiresPayment(int bookingId, String paymentStatus) {
        return new ConflictException(
                "PAYMENT_NOT_SUCCESSFUL",
                "Booking " + bookingId + " cannot be confirmed — its payment is \"" + paymentStatus
                        + "\". Only a successful payment may confirm a booking; leave it PENDING "
                        + "until the gateway has been paid.");
    }

    /**
     * The requested status is well formed but the booking's current state forbids the
     * move — Phase 9's transition matrix.
     *
     * <p>The matrix, stated where the refusal lives:
     * <ul>
     *   <li>{@code PENDING → CANCELLED}, {@code CONFIRMED → CANCELLED} — the soft
     *       cancel the admin page offers, seats and payment left alone (R4).</li>
     *   <li>{@code PENDING → CONFIRMED} — allowed only with a successful payment.</li>
     *   <li><b>{@code CONFIRMED → PENDING} and {@code CANCELLED → PENDING} are
     *       refused.</b> A confirmed booking has a paid payment and usually a ticket;
     *       demoting it would leave a ticket pointing at a booking the admin list
     *       calls unpaid. A cancelled one has no seats to hand back here either —
     *       rebooking is a new booking, not a status flip. Setting the status it
     *       already has is a no-op, not an error.</li>
     * </ul>
     */
    /**
     * A payment was never initiated for this booking — Roadmap Phase 10's gate.
     *
     * <p>{@code POST /api/payments/verify} is the mock gateway's callback: it says
     * what the gateway answered about a transaction that already exists. Arriving
     * without a payment row is therefore not a missing record but a call out of
     * order — there is no transaction for the gateway to have answered, and
     * creating one here would let a caller mark any booking paid while skipping the
     * step that validated it. {@code POST /api/payments/initiate} is that step, and
     * the message says so.
     */
    public static ConflictException paymentNotInitiated(int bookingId) {
        return new ConflictException(
                "PAYMENT_NOT_INITIATED",
                "No payment has been initiated for booking " + bookingId
                        + ". Call POST /api/payments/initiate first — the gateway cannot verify a "
                        + "transaction that was never started.");
    }

    /**
     * The booking already holds a completed (or refunded) payment.
     *
     * <p>The payment table is <b>1:1 with a booking</b> ({@code payment.booking_id} is
     * UNIQUE), so a second transaction cannot be attached to it even if the caller
     * means well. Raised for two cases that are the same refusal: initiating a
     * second payment for a booking that is paid, and verifying a <i>different</i>
     * transaction id against a payment row that already succeeded.
     */
    public static ConflictException paymentAlreadyCompleted(int bookingId, String state) {
        return new ConflictException(
                "PAYMENT_ALREADY_COMPLETED",
                "Booking " + bookingId + " already has a payment in state \"" + state
                        + "\", and a booking holds exactly one transaction. "
                        + "A second payment needs a new booking.");
    }

    /**
     * A refunded transaction cannot be paid again.
     *
     * <p>Distinct from {@link #paymentAlreadyCompleted} because the remedy differs:
     * that one says "this booking is already sold", this one says "this money has
     * been given back" — undoing a refund is a finance action, not a retry.
     */
    public static ConflictException paymentAlreadyRefunded(int bookingId) {
        return new ConflictException(
                "PAYMENT_ALREADY_REFUNDED",
                "Booking " + bookingId + " has a refunded payment. A refunded transaction cannot be "
                        + "paid again — rebooking creates a new one.");
    }

    /**
     * There is nothing to refund: either no payment row at all, or one that never
     * succeeded (a pending or failed attempt moved no money).
     */
    public static ConflictException paymentNotRefundable(int bookingId, String state) {
        return new ConflictException(
                "PAYMENT_NOT_REFUNDABLE",
                "Booking " + bookingId + " has no successful payment to refund (payment state: \""
                        + state + "\"). Only a successful payment can be refunded.");
    }

    /**
     * The gateway reference is already attached to another booking.
     *
     * <p>{@code payment.txn_id} is UNIQUE, so the database would refuse this too —
     * but as a driver-level duplicate-key error ({@code 1062}) that the handler can
     * only report generically. Pre-checking turns "you may not reuse a transaction
     * id" into a message that names the id.
     */
    public static ConflictException txnIdAlreadyUsed(String txnId) {
        return new ConflictException(
                "TXN_ID_ALREADY_USED",
                "Transaction " + txnId + " is already recorded against another booking.");
    }

    /**
     * The booking is cancelled, so it can neither be paid for nor confirmed.
     *
     * <p>A cancelled booking has been surrendered: R4's policy keeps its seats
     * counted and leaves the money to the refund flow, so there is nothing left to
     * charge. Raised by {@code PaymentService} <i>before</i> it writes anything, so
     * the refusal reads as "this booking is cancelled" rather than as a status
     * transition failure two layers down.
     */
    public static ConflictException bookingCancelled(int bookingId) {
        return new ConflictException(
                "BOOKING_CANCELLED",
                "Booking " + bookingId + " is cancelled and can no longer be paid for. "
                        + "Book the trip again to get a new booking.");
    }

    /**
     * An {@code ADMIN} account may not be demoted, deactivated, deleted, or given a
     * new password — Roadmap Phase 11's protection rule.
     *
     * <p><b>The rule comes from the page, and the page's reasoning is the reason:</b>
     * {@code admin-users.js} locks the row actions for an {@code ADMIN} row with the
     * comment "a disabled admin could lock everyone out of the panel". The API keeps
     * that promise and closes the gap the mock left open — the mock's <i>form</i> still
     * lets you demote an admin to {@code USER} ({@code Object.assign(u, payload)}),
     * after which the row's protection is gone and no account with panel access may be
     * left at all. One admin demoting themselves is not recoverable through the API,
     * so it is refused rather than trusted to good intentions.
     *
     * <p><b>What is <i>not</i> refused:</b> an admin's name, email and phone are still
     * editable — the page calls admin accounts "editable, never disabled or deleted" —
     * and a request that sends the admin's <i>current</i> role/status back unchanged
     * (which the edit modal does, from the values it loaded) is a no-op, not a
     * conflict. Only an actual change is refused, and the message names the action so
     * the caller can tell which of the four it hit.
     *
     * @param action a verb phrase, e.g. {@code "demoted to USER"} or {@code "deactivated"}
     */
    public static ConflictException adminAccountProtected(String name, String action) {
        return new ConflictException(
                "ADMIN_ACCOUNT_PROTECTED",
                "ADMIN account " + name + " cannot be " + action
                        + " — an account that can disable the only administrator could lock "
                        + "everyone out of the panel. Name, email and phone stay editable.");
    }

    /**
     * A user whose bookings are still on record cannot be hard-deleted.
     *
     * <p>{@code booking.user_id} references {@code users.id}, and it is the only
     * reference to a user in the schema. The delete is refused in the service rather
     * than left to the database for the reason every parent-row guard in this project
     * is: the driver would answer {@code 1451}, which the handler can only report as
     * the generic {@code RECORD_IN_USE}. "Deactivate instead" is the same advice
     * {@code admin-users.js} already implies — its delete dialog promises the admin
     * that past bookings are kept — and deactivating really does keep them, because it
     * changes nothing but {@code status}.
     *
     * <p>Worth naming: the page's own comment claims bookings are unaffected because
     * they snapshot the customer's name/email instead of referencing the account. That
     * is true of the <i>contact block</i> a booking carries, but not of the
     * {@code user_id} link, which is why a user with bookings cannot simply be removed.
     */
    public static ConflictException userHasBookings(String name, long bookingCount) {
        return new ConflictException(
                "USER_HAS_BOOKINGS",
                name + " has " + bookingCount + " booking(s) on record and cannot be deleted — "
                        + "set the account to Inactive instead, which keeps the booking history intact.");
    }

    public static ConflictException invalidStatusTransition(int bookingId, String from, String to) {
        return new ConflictException(
                "INVALID_STATUS_TRANSITION",
                "Booking " + bookingId + " is " + from + " and cannot be set to " + to + ". "
                        + "A booking may be cancelled from any state, and only a PENDING booking with a "
                        + "successful payment may be confirmed.");
    }

    /* ------------------------------------------------------------------ *
     *  The real eSewa callback (fix-plan §10)                             *
     *
     *  Four refusals, and the first two are the pair that makes a public
     *  callback safe: a transaction reference that no row holds, and one that
     *  belongs to a different booking. Both are about *attribution* rather than
     *  about money — a callback that cannot be tied to exactly one pending
     *  transaction must be refused before anything is written, because the
     *  alternative is confirming a booking nobody paid for.
     * ------------------------------------------------------------------ */

    /**
     * The callback named a transaction this server has no record of.
     *
     * <p>Not a 404: the endpoint exists and the booking in its path may well be real —
     * what is missing is the transaction. The causes worth knowing about are a
     * reference minted for another environment (a UAT callback pointing at a
     * production-shaped database), a booking whose attempt was re-initiated after the
     * callback was issued, and a made-up uuid. All three are refused identically, and
     * the message names the reference so a support ticket can quote it.
     */
    public static ConflictException esewaTransactionUnknown(String transactionUuid) {
        return new ConflictException(
                "ESEWA_TRANSACTION_UNKNOWN",
                "Transaction \"" + transactionUuid + "\" is not recorded against any payment, so "
                        + "nothing was settled. If a payment was made, quote this reference to "
                        + "support — the gateway may hold it under a different booking.");
    }

    /**
     * The transaction exists but belongs to another booking.
     *
     * <p>The callback's path carries a booking id and its payload carries a transaction
     * reference, and this is what happens when the two disagree — a pasted URL, a
     * tampered one, or a customer who has several bookings open. The refusal is not
     * about the money (the transaction may be perfectly valid); it is that settling it
     * against <i>this</i> booking would confirm the wrong trip.
     */
    public static ConflictException esewaTransactionNotCurrent(int bookingId, String claimed, String stored) {
        return new ConflictException(
                "ESEWA_TRANSACTION_NOT_CURRENT",
                "Booking " + bookingId + " is not the booking transaction \"" + claimed + "\" belongs "
                        + "to (" + stored + " does). This is usually a stale response from an earlier "
                        + "attempt — the current attempt's own callback is the one that settles it.");
    }

    /**
     * eSewa's ledger holds a different figure than the booking owes.
     *
     * <p>This is the "paid one rupee, confirmed a 16,599.98 booking" case seen from the
     * far end. The status API is asked about the payment row's own amount, so a mismatch
     * here means eSewa's answer is about another transaction entirely — and a booking is
     * never confirmed on an answer that is not demonstrably about its own money.
     */
    public static ConflictException esewaAmountMismatch(int bookingId, String expected, String paid) {
        return new ConflictException(
                "ESEWA_AMOUNT_MISMATCH",
                "Booking " + bookingId + " is owed " + expected + " but eSewa's ledger holds " + paid
                        + " for this transaction, so nothing was settled. Check the gateway's own "
                        + "dashboard before retrying — if money moved, it moved against another "
                        + "transaction.");
    }

    /**
     * The callback claimed success and eSewa's own ledger disagreed.
     *
     * <p>Distinct from the mismatch above because of what it implies: the payload passed
     * its signature check and still told a different story than the gateway's server-side
     * answer, which in production is the signature of a compromised or misconfigured
     * signing key. Nothing is settled in either direction — failing the booking would act
     * on the same untrusted claim, merely negatively — and the case is logged at WARN.
     */
    public static ConflictException esewaPaymentNotConfirmed(int bookingId, String gatewayStatus) {
        return new ConflictException(
                "ESEWA_PAYMENT_NOT_CONFIRMED",
                "Booking " + bookingId + " was reported as paid but eSewa's own records say \""
                        + gatewayStatus + "\", so it was not confirmed. The booking is unchanged; "
                        + "verify the transaction in eSewa before contacting the customer.");
    }
}
