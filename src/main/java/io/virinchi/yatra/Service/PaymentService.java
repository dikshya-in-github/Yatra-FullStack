package io.virinchi.yatra.Service;

import io.virinchi.yatra.Dto.AdminBookingResponse;
import io.virinchi.yatra.Dto.PaymentInitiateRequest;
import io.virinchi.yatra.Dto.PaymentInitiateResponse;
import io.virinchi.yatra.Dto.PaymentVerifyRequest;
import io.virinchi.yatra.Dto.PaymentVerifyResponse;
import io.virinchi.yatra.Exception.ConflictException;
import io.virinchi.yatra.Exception.ResourceNotFoundException;
import io.virinchi.yatra.Exception.ValidationException;
import io.virinchi.yatra.Model.Booking;
import io.virinchi.yatra.Model.Passenger;
import io.virinchi.yatra.Model.Payment;
import io.virinchi.yatra.Model.Ticket;
import io.virinchi.yatra.Repository.BookingRepository;
import io.virinchi.yatra.Repository.PassengerRepository;
import io.virinchi.yatra.Repository.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The eSewa payment flow — Roadmap Phase 10, and the backend half the frontend's
 * mock has been standing in for since Session 25.
 *
 * <h2>The flow, and where each step lives</h2>
 * <pre>
 * booking created (PENDING, seats held)        BookingService.createBooking
 *        ↓
 * POST /api/payments/initiate                  → a PENDING payment row + the transaction uuid
 *        ↓
 * one of two gateways:
 *   a) the mock page walks its own screens        (payment.js → esewaLogin → … → esewaConfirm)
 *      ↓ POST /api/payments/verify                  → the mock says what happened
 *   b) GET /api/payments/esewa/checkout/{id}       (fix-plan §10 — the real one)
 *      → a signed form that POSTs the customer to eSewa's own hosted page
 *      → eSewa redirects back to /api/payments/esewa/success|failure/{id}
 *        ↓ EsewaGatewayService: re-sign the payload, ask eSewa's status API, then settle
 *        ↓
 * settle()                                     → SUCCESS: payment SUCCESS, booking CONFIRMED,
 *                                                           ticket issued
 *                                              → FAILED:  payment FAILED, booking untouched
 * </pre>
 *
 * <p><b>Both gateways end in the same {@code settle}.</b> That is deliberate and it is
 * the point of the extraction §10 forced: "what a paid booking is" — payment
 * {@code SUCCESS} first, then the one confirm path, then the ticket — is one method, so
 * the mock callback, the real callback and the admin's own status button cannot drift
 * into three slightly different definitions of paid.
 *
 * <p><b>Initiate writes a row; verify is what the gateway's answer updates.</b> That
 * split is what makes {@code PAYMENT_NOT_INITIATED} a real refusal rather than a
 * formality: nothing can be paid for without passing through the step that
 * validated the booking, and the payment table's 1:1 {@code booking_id} is the
 * database agreeing that a booking holds one transaction.
 *
 * <h2>Money never comes from the request</h2>
 * <p>Both DTOs carry an {@code amount} and neither is read: {@link #initiate}
 * prices the transaction from the booking's stored {@code total_amount}, and
 * {@link #verify} re-asserts that same figure when it records the outcome. A
 * number that arrives in a JSON body is a number a browser can edit — the same rule
 * {@code BookingService} applies to its own request, applied one step later. The
 * one deliberate exception is <b>not</b> a money field: {@code outcome} says
 * whether the mock gateway approved or declined, which is the gateway's business
 * and is explained on {@link PaymentVerifyRequest}.
 *
 * <h2>Confirm goes through the one confirm path</h2>
 * <p>A successful verify does not set {@code CONFIRMED} itself. It calls
 * {@link BookingService#updateStatus(int, String)}, so the gate that already
 * exists — "a booking may be confirmed only from {@code PENDING} and only with a
 * {@code SUCCESS} payment row" (rule 6) — is the same code the admin's status
 * button uses. Two writers of that transition is exactly how the matrix would rot;
 * there is one, and this is a caller. The order matters and is the safe one: the
 * payment row is written {@code SUCCESS} <i>first</i>, then the confirm reads it
 * back out (Hibernate flushes before the query, so it sees this transaction's own
 * change), then the ticket is minted.
 *
 * <h2>What a failed payment leaves behind</h2>
 * <p>The payment row records the gateway's reference and {@code FAILED}, the
 * booking's {@code paymentStatus} becomes {@code Failed} (the vocabulary
 * {@code admin-bookings.html}'s filter uses), and nothing else moves: the booking
 * stays {@code PENDING}, its seats <b>stay held</b>, and no ticket exists. The hold
 * is deliberate — releasing it here would hand a seat back mid-checkout, and the
 * 15-minute expiry sweep is the documented (still homeless) mechanism for
 * abandoned holds. The seats are freed the moment the pending booking is deleted,
 * which is {@code BookingService}'s existing path.
 *
 * <h2>Refunds are a payment action, not a status flip</h2>
 * <p>{@link #refund} sets the payment row {@code REFUNDED} and the booking's
 * {@code paymentStatus} to {@code Refunded} — the two rows the payments page shows
 * — and touches nothing else. It does <b>not</b> cancel the booking and does not
 * free seats: cancelling is the admin's separate action (R4's policy, unchanged),
 * the page treats them as two buttons, and merging them would make "refund the
 * money" and "give up the trip" impossible to do independently.
 */
@Service
@Slf4j
public class PaymentService {

    /** The gateway the project integrates; the page posts it lower-cased. */
    private static final String ESEWA = "eSewa";

    /** eSewa's own bank-linked option — still inside the eSewa flow. */
    private static final String LINKED_BANK = "Linked Bank Account";

    private static final String PENDING = "PENDING";
    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED = "FAILED";
    private static final String REFUNDED = "REFUNDED";

    /** The booking's {@code paymentStatus} vocabulary — title case, as the pages show it. */
    private static final String FAILED_DISPLAY = "Failed";
    private static final String REFUNDED_DISPLAY = "Refunded";

    private static final String CONFIRMED = "CONFIRMED";
    private static final String CANCELLED = "CANCELLED";
    private static final String ALL = "ALL";

    /**
     * The payment-row statuses a ledger filter may name, in both vocabularies.
     *
     * <p>The page's dropdown sends {@code Paid}/{@code Pending}/{@code Refunded}/
     * {@code Failed} (it reads the booking's {@code paymentStatus}), while the
     * payment row stores {@code SUCCESS}/{@code PENDING}/{@code REFUNDED}/
     * {@code FAILED} — the same two-vocabulary meeting point the booking status
     * endpoint has, resolved the same way: accept both, normalise to the stored
     * form. {@code PAID → SUCCESS} is the only pair that is not a pure case fold.
     */
    private static final Map<String, String> PAYMENT_STATUSES = Map.of(
            "PAID", SUCCESS,
            "SUCCESS", SUCCESS,
            "PENDING", PENDING,
            "FAILED", FAILED,
            "REFUNDED", REFUNDED);

    /**
     * The sort properties the ledger may be ordered by, keyed by the name a caller
     * would use (R6: an explicit whitelist, and an unknown key falls back rather than
     * reaching the database as a column). Only the payment row's own columns are
     * listed — sorting by the customer would need the {@code booking} join in the
     * {@code ORDER BY}, which is not what a {@code Sort} handed to this query
     * produces.
     */
    private static final Map<String, String> SORTABLE = Map.of(
            "id", "id",
            "amount", "amount",
            "status", "status",
            "method", "method",
            "created", "createdAt",
            "paid", "paidAt");

    private static final String DEFAULT_SORT = "id";

    private final PaymentRepository payments;
    private final BookingRepository bookings;
    private final PassengerRepository passengers;
    private final BookingService bookingService;
    private final TicketService ticketService;

    public PaymentService(PaymentRepository payments,
                          BookingRepository bookings,
                          PassengerRepository passengers,
                          BookingService bookingService,
                          TicketService ticketService) {
        this.payments = payments;
        this.bookings = bookings;
        this.passengers = passengers;
        this.bookingService = bookingService;
        this.ticketService = ticketService;
    }

    /* ------------------------------------------------------------------ *
     *  The gateway (Roadmap Phase 10)                                     *
     * ------------------------------------------------------------------ */

    /**
     * Opens a transaction for a booking: a {@code PENDING} payment row and the
     * redirect the wizard leaves on.
     *
     * <p><b>Re-initiating an unfinished attempt reuses the row.</b>
     * {@code payment.booking_id} is UNIQUE, so a failed or abandoned attempt cannot
     * become a second row; the same row is refreshed (method, amount, back to
     * {@code PENDING}) and its old gateway reference cleared, because that reference
     * belonged to the attempt being replaced. A row that already
     * {@code SUCCESS}/{@code REFUNDED} is refused instead — that booking is sold, and
     * a second transaction against it is a new booking.
     *
     * @throws ResourceNotFoundException 404 — no such booking
     * @throws ValidationException       400 {@code METHOD_NOT_SUPPORTED} for a
     *                                   gateway the project does not integrate
     * @throws ConflictException         409 {@code BOOKING_CANCELLED} or
     *                                   {@code PAYMENT_ALREADY_COMPLETED}
     */
    @Transactional
    public PaymentInitiateResponse initiate(PaymentInitiateRequest request) {
        Booking booking = requireBooking(request.bookingId());
        String method = canonicalMethod(request.method());

        if (isCancelled(booking)) {
            throw ConflictException.bookingCancelled(booking.getId());
        }

        Optional<Payment> existing = payments.findByBookingId(booking.getId());
        if (existing.isPresent()) {
            Payment payment = existing.get();
            String state = upper(payment.getStatus());

            if (SUCCESS.equals(state) || REFUNDED.equals(state)) {
                throw ConflictException.paymentAlreadyCompleted(booking.getId(), state);
            }

            payment.setMethod(method);
            payment.setAmount(booking.getTotalAmount());
            payment.setStatus(PENDING);
            //A fresh transaction reference, because the previous attempt's is dead: a
            //callback carrying the old uuid must not settle this attempt (§10).
            payment.setTxnId(transactionUuid());
            payment.setPaidAt(null);

            //The roadmap's "payments processed" event (Phase 14). A re-initiated
            //abandoned attempt is worth a line of its own: it is the same row coming
            //back around, not a second transaction.
            log.info("Payment re-initiated: booking={} method={} amount={} (previous attempt replaced)",
                    booking.getId(), method, payment.getAmount());
            return PaymentInitiateResponse.of(payments.save(payment));
        }

        Payment payment = new Payment();
        payment.setBooking(booking);
        payment.setMethod(method);
        payment.setAmount(booking.getTotalAmount());
        payment.setStatus(PENDING);
        //Minted here, before the customer leaves for the gateway, and stored on the row:
        //it is the key the real callback is matched by, and the only handle that survives
        //the round trip through eSewa (§10).
        payment.setTxnId(transactionUuid());
        payment.setCreatedAt(LocalDateTime.now());

        log.info("Payment initiated: booking={} method={} amount={}",
                booking.getId(), method, payment.getAmount());
        return PaymentInitiateResponse.of(payments.save(payment));
    }

    /**
     * Records the gateway's answer and, when it approved, completes the sale:
     * payment {@code SUCCESS}, booking {@code CONFIRMED}, ticket issued.
     *
     * <p><b>Idempotent for the same transaction.</b> A browser retry (or a second
     * Postman run) with the same {@code txnId} against an already-{@code SUCCESS}
     * payment answers {@code SUCCESS} again with the ticket that already exists —
     * {@link TicketService#issue} hands back the existing one rather than minting a
     * second. A <i>different</i> {@code txnId} is refused: the booking holds one
     * transaction, and silently re-pointing it at another reference is how a payment
     * row stops matching what the gateway did.
     *
     * @param request the gateway's reference, the booking, and the mock's
     *                {@code outcome} switch
     * @throws ResourceNotFoundException 404 — no such booking
     * @throws ValidationException       400 — an unsupported method or a malformed
     *                                   outcome
     * @throws ConflictException         409 {@code PAYMENT_NOT_INITIATED},
     *                                   {@code PAYMENT_ALREADY_REFUNDED},
     *                                   {@code PAYMENT_ALREADY_COMPLETED},
     *                                   {@code TXN_ID_ALREADY_USED} or
     *                                   {@code BOOKING_CANCELLED}
     */
    @Transactional
    public PaymentVerifyResponse verify(PaymentVerifyRequest request) {
        Booking booking = requireBooking(request.bookingId());

        Payment payment = payments.findByBookingId(booking.getId())
                .orElseThrow(() -> ConflictException.paymentNotInitiated(booking.getId()));

        //The mock page names the gateway it walked; a blank one keeps the stored spelling.
        //Resolved here rather than in settle() because it is the *request's* vocabulary,
        //while everything after it is shared with the real gateway path.
        String method = blankToNull(request.method()) == null
                ? payment.getMethod()
                : canonicalMethod(request.method());

        return settle(booking, payment, request.txnId().trim(), method, request.failed(),
                LocalDateTime.now());
    }

    /**
     * Settles a payment the real eSewa callback has already verified — fix-plan §10.
     *
     * <p><b>The caller is {@code EsewaGatewayService}, and the verification is already
     * done.</b> By the time this runs the payload's signature has been re-computed and
     * matched, eSewa's own status API has answered {@code COMPLETE}, and the amount it
     * answered has been compared with the row's. So this method is not a second verifier
     * — it is the writer, and it is deliberately the <i>same</i> writer as the mock
     * path's: {@link #settle}. What it adds is the lookup: the callback carries a
     * transaction uuid and no booking, so the row is found by the reference this server
     * minted at initiate, which is also what makes a replayed callback harmless.
     *
     * <p>{@code paid} is the caller's verdict, and a {@code false} one travels the exact
     * failure path the mock's declined payment does: the payment row records the attempt
     * and the booking keeps its seats, its {@code PENDING} status and its absence of a
     * ticket. A callback for a transaction that already succeeded is not an error —
     * {@code settle}'s idempotency guard answers with the existing sale, so a customer
     * refreshing the success URL gets their e-ticket again instead of a 409.
     *
     * @param bookingId       the booking the callback named in its path
     * @param transactionUuid the uuid from the verified payload
     * @param paid            whether eSewa's ledger said the transaction is complete
     * @throws ResourceNotFoundException 404 — no such booking
     * @throws ConflictException         409 {@code ESEWA_TRANSACTION_UNKNOWN} when no row
     *                                   holds that reference, or
     *                                   {@code ESEWA_TRANSACTION_NOT_CURRENT} when the row
     *                                   belongs to a different booking
     */
    @Transactional
    public PaymentVerifyResponse settleFromGateway(int bookingId, String transactionUuid, boolean paid) {
        Booking booking = requireBooking(bookingId);

        Payment payment = payments.findByTxnId(transactionUuid)
                .orElseThrow(() -> ConflictException.esewaTransactionUnknown(transactionUuid));

        //Two independent keys have to agree: the path's booking id and the payload's
        //transaction uuid. Either one alone would be a single point of trust in a URL
        //anybody can craft.
        if (payment.getBooking() == null || payment.getBooking().getId() != bookingId) {
            throw ConflictException.esewaTransactionNotCurrent(bookingId, transactionUuid,
                    payment.getBooking() == null ? "no booking" : "booking " + payment.getBooking().getId());
        }

        //The customer chose the method, not the gateway — the row keeps its own.
        return settle(booking, payment, transactionUuid, payment.getMethod(), !paid, LocalDateTime.now());
    }

    /**
     * The one place a payment becomes settled — the shared second half of
     * {@link #verify} (the mock gateway) and {@link #settleFromGateway} (the real one).
     *
     * <p>The guards, in order, and why the order is what it is: a refunded payment is
     * refused first (undoing it is a finance action, not a retry); a successful one is
     * answered idempotently when the reference matches and refused when it does not (the
     * booking holds exactly one transaction); a cancelled booking is refused before
     * anything is written; and the reference is checked against every other row before the
     * write, so the UNIQUE column is a backstop rather than the error message.
     */
    private PaymentVerifyResponse settle(Booking booking, Payment payment, String txnId,
                                         String method, boolean failed, LocalDateTime answeredAt) {
        String state = upper(payment.getStatus());

        if (REFUNDED.equals(state)) {
            throw ConflictException.paymentAlreadyRefunded(booking.getId());
        }

        if (SUCCESS.equals(state)) {
            if (txnId.equalsIgnoreCase(payment.getTxnId())) {
                //Same transaction, same answer — no second ticket, no second confirm.
                return PaymentVerifyResponse.success(payment,
                        ticketService.find(booking.getId()).orElse(null), answeredAt);
            }
            throw ConflictException.paymentAlreadyCompleted(booking.getId(), state);
        }

        if (isCancelled(booking)) {
            throw ConflictException.bookingCancelled(booking.getId());
        }

        //Pre-checked for a clean 409: the UNIQUE column would refuse it too, but as a
        //driver-level duplicate that the handler can only report generically. On the real
        //gateway path the id IS this row's own, so the filter removes it and the check
        //passes — which is what keeps a callback for the current attempt idempotent
        //instead of flagging itself as a duplicate.
        payments.findByTxnId(txnId)
                .filter(other -> other.getId() != payment.getId())
                .ifPresent(other -> {
                    throw ConflictException.txnIdAlreadyUsed(txnId);
                });

        payment.setTxnId(txnId);
        payment.setMethod(method);
        payment.setAmount(booking.getTotalAmount());
        if (payment.getCreatedAt() == null) {
            payment.setCreatedAt(answeredAt);
        }

        if (failed) {
            payment.setStatus(FAILED);
            payment.setPaidAt(null);
            payments.save(payment);

            //Failed is a payment fact the booking shows; the booking itself stays
            //PENDING with its seats held (see the class comment).
            booking.setPaymentStatus(FAILED_DISPLAY);
            bookings.save(booking);

            //A decline is a business event, not an error: the booking stays PENDING
            //with its seats held, so this is WARN-level — visible without being read
            //as a system fault.
            log.warn("Payment declined at the gateway: booking={} txn={} method={} — booking left PENDING, seats still held",
                    booking.getId(), txnId, method);

            return PaymentVerifyResponse.failure(payment, answeredAt);
        }

        payment.setStatus(SUCCESS);
        payment.setPaidAt(answeredAt);
        payments.save(payment);

        //The one confirm path (rule 6). It re-reads this transaction's own payment row,
        //sets CONFIRMED + Paid, and its gate is what keeps a paid/ confirmed booking
        //honest for both this flow and the admin's status button.
        bookingService.updateStatus(booking.getId(), CONFIRMED);

        Ticket ticket = ticketService.issue(booking, txnId);

        //Money moving is the single most auditable event in the system, and the txn id
        //is the gateway's own reference — the one thing a reconciliation needs. No
        //customer name: the booking id and the txn id are enough to find the rest.
        log.info("Payment succeeded: booking={} txn={} method={} amount={} -> booking CONFIRMED, pnr={}",
                booking.getId(), txnId, method, payment.getAmount(), ticket.getPnr());

        return PaymentVerifyResponse.success(payment, ticket, answeredAt);
    }

    /**
     * Marks a successful payment refunded — the money movement the admin payments
     * page's one write stands for.
     *
     * <p><b>Two rows, deliberately.</b> The payment row becomes {@code REFUNDED}
     * (what the gateway did) and the booking's {@code paymentStatus} becomes
     * {@code Refunded} (what every page displays) — the same pairing
     * {@code PaymentVerifyRequest}'s success path keeps in step, in reverse. Seats,
     * booking status and ticket are untouched: R4's cancel policy still owns those,
     * and a refund that quietly released seats would inflate the flight's
     * availability behind the booking count.
     *
     * <p>Idempotent, because the page's button can be pressed twice and a refund
     * that "succeeded twice" is not a thing that can happen.
     *
     * @throws ResourceNotFoundException 404 — no such booking
     * @throws ConflictException         409 {@code PAYMENT_NOT_REFUNDABLE} when
     *                                   there is no payment row or it never
     *                                   succeeded
     */
    @Transactional
    public AdminBookingResponse refund(int bookingId) {
        Booking booking = requireBooking(bookingId);

        Payment payment = payments.findByBookingId(bookingId)
                .orElseThrow(() -> ConflictException.paymentNotRefundable(bookingId, "none on record"));

        String state = upper(payment.getStatus());

        if (REFUNDED.equals(state)) {
            return describe(booking);   //already refunded — no-op, not an error
        }

        if (!SUCCESS.equals(state)) {
            throw ConflictException.paymentNotRefundable(bookingId, state);
        }

        payment.setStatus(REFUNDED);
        payment.setAmount(booking.getTotalAmount());
        payments.save(payment);

        booking.setPaymentStatus(REFUNDED_DISPLAY);
        bookings.save(booking);

        log.info("Payment refunded: booking={} txn={} amount={} — booking status and ticket untouched",
                booking.getId(), payment.getTxnId(), payment.getAmount());

        return describe(booking);
    }

    /* ------------------------------------------------------------------ *
     *  The admin ledger (Roadmap Phase 10)                                *
     * ------------------------------------------------------------------ */

    /**
     * Every transaction, in a deterministic order — the admin payments table's read.
     *
     * <p>Returns booking-shaped rows because that is the record
     * {@code admin-payments.js} renders (it derives a transaction per booking and
     * ignores anything the response does not nest under {@code bookings}) — see
     * {@link io.virinchi.yatra.Dto.AdminPaymentListResponse} for the full reasoning.
     * {@code R6}'s lesson applies here as everywhere else: the list path gets its own
     * ordered query rather than {@code Pageable.unpaged()}, which would discard the
     * sort.
     *
     * @param search txn id, booking id, contact name/email/phone, flight number, PNR
     *               or ticket number — the fields the page's search box advertises
     * @param method {@code eSewa} / {@code Linked Bank Account}, any case;
     *               {@code ALL} or blank for no filter. An unrecognised value is a 400
     *               rather than an empty list, because the API knows exactly two
     *               methods and cannot honestly search for a third
     * @param status {@code Paid}/{@code SUCCESS}, {@code Pending}, {@code Failed},
     *               {@code Refunded} — either vocabulary, in any case; {@code ALL} or
     *               blank for no filter
     * @param sort   one of {@code id|amount|status|method|created|paid}; anything else
     *               falls back to {@code id}
     */
    @Transactional(readOnly = true)
    public List<AdminBookingResponse> listPayments(String search, String method, String status, String sort) {
        List<Payment> rows = payments.searchAll(
                like(search), idTerm(search), storedMethod(method), storedStatus(status), sortFor(sort));

        return responses(rows);
    }

    /** One page of transactions, plus the counts needed to walk the rest. */
    @Transactional(readOnly = true)
    public Page<AdminBookingResponse> listPaymentPage(String search, String method, String status,
                                                      String sort, int page, int size) {
        PageRequest request = Paging.request(page, size, sortFor(sort));

        Page<Payment> rows = payments.searchPage(
                like(search), idTerm(search), storedMethod(method), storedStatus(status), request);

        Map<Integer, List<Passenger>> byBooking = passengersFor(bookingsOf(rows.getContent()));
        return rows.map(payment -> AdminBookingResponse.of(
                payment.getBooking(),
                byBooking.getOrDefault(payment.getBooking().getId(), List.of())));
    }

    /* ------------------------------------------------------------------ *
     *  Mapping + filters                                                  *
     * ------------------------------------------------------------------ */

    /**
     * The ledger's payments as the page's booking records.
     *
     * <p><b>Why this batches passengers itself instead of asking
     * {@code BookingService}.</b> The mapping has to happen inside the read
     * transaction (an entity mapped after it closes throws
     * {@code LazyInitializationException}, and a collection cannot be
     * {@code @EntityGraph}-fetched alongside paging), and each module's service owns
     * its own reads — that is what Phase 9 did for the booking list, and Phase 8 for
     * destinations. The alternative is exposing a
     * {@code List<Booking> → List<DTO>} method across services, which would put raw
     * entities in one service's public API for the sake of eleven lines.
     */
    private List<AdminBookingResponse> responses(List<Payment> rows) {
        List<Booking> bookingRows = bookingsOf(rows);
        Map<Integer, List<Passenger>> byBooking = passengersFor(bookingRows);

        return bookingRows.stream()
                .map(booking -> AdminBookingResponse.of(
                        booking, byBooking.getOrDefault(booking.getId(), List.of())))
                .toList();
    }

    /** One booking, fully described — the refund endpoint's answer. */
    private AdminBookingResponse describe(Booking booking) {
        return AdminBookingResponse.of(booking, passengers.findByBookingId(booking.getId()));
    }

    private static List<Booking> bookingsOf(List<Payment> rows) {
        return rows.stream()
                .map(Payment::getBooking)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * The passengers of the listed bookings, in one query — the same batching
     * {@code BookingService} does for the booking list, for the same reason (a
     * fifty-row page costs three queries, not fifty).
     */
    private Map<Integer, List<Passenger>> passengersFor(List<Booking> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }

        List<Integer> ids = rows.stream().map(Booking::getId).toList();
        return passengers.findByBookingIdIn(ids).stream()
                .collect(Collectors.groupingBy(passenger -> passenger.getBooking().getId()));
    }

    /**
     * The gateway's canonical spelling, or a 400.
     *
     * <p>Two values are supported because the page offers two — eSewa's wallet and
     * its bank-linked account, which are the same integration. The others the
     * payment page shows (cards, Khalti, IME Pay, ConnectIPS) are blocked in the UI
     * and refused here too: accepting one would record a transaction with no gateway
     * behind it.
     */
    private static String canonicalMethod(String method) {
        String value = upper(method);
        if (value.isEmpty()) {
            return ESEWA;
        }

        return switch (value) {
            case "ESEWA", "ESEWA WALLET", "ESEWA WALLET PAYMENT" -> ESEWA;
            case "LINKED BANK ACCOUNT", "BANK", "BANK ACCOUNT" -> LINKED_BANK;
            default -> throw new ValidationException("METHOD_NOT_SUPPORTED",
                    "Only eSewa is available right now (\"" + method + "\" is not integrated). "
                            + "Select eSewa or a linked bank account.");
        };
    }

    /**
     * The ledger filter's method, or {@code null} for "no filter".
     *
     * <p>Unknown methods answer 400 for the same reason the write path does: this is
     * a filter over a known, closed set, and silently returning nothing for a typo
     * looks like "no transactions" instead of "that is not a method".
     */
    private static String storedMethod(String method) {
        String value = String.valueOf(method == null ? "" : method).trim();
        if (value.isEmpty() || ALL.equalsIgnoreCase(value)) {
            return null;
        }
        return canonicalMethod(value);   // both writers store this spelling
    }

    /**
     * The requested payment state in the row's own vocabulary, or {@code null}.
     *
     * <p>{@code ALL} folds to {@code null} as well as blank — the page's dropdowns
     * send {@code ALL} for "no filter", and passing it through would return zero
     * rows, the opposite of what was asked. An unrecognised value is passed through
     * uppercased and simply matches nothing, which is the behaviour the booking
     * list's own status filter has; the two list endpoints stay consistent.
     */
    private static String storedStatus(String status) {
        String value = upper(status);
        if (value.isEmpty() || ALL.equals(value)) {
            return null;
        }
        return PAYMENT_STATUSES.getOrDefault(value, value);
    }

    /** A {@code LIKE} pattern for the free-text search, or {@code null} for "no filter". */
    private static String like(String search) {
        String term = String.valueOf(search == null ? "" : search).trim().toLowerCase(Locale.ROOT);
        return term.isEmpty() ? null : "%" + term + "%";
    }

    /**
     * The same search term as a booking id when it is one, otherwise {@code null} —
     * the page's search box advertises "booking ID", and its ids are numeric here.
     * Same bounds and reasoning as {@code BookingService.idTerm}.
     */
    private static Integer idTerm(String search) {
        String term = String.valueOf(search == null ? "" : search).trim();
        if (term.isEmpty() || term.length() > 9) {
            return null;
        }
        return term.chars().allMatch(Character::isDigit) ? Integer.valueOf(term) : null;
    }

    /**
     * Resolves a requested sort to a whitelisted column, always finishing with
     * {@code id} so rows sharing a status still page in a stable order. Anything
     * unrecognised falls back to {@code id} — the value never reaches the database
     * (R6).
     */
    private static Sort sortFor(String requested) {
        String key = String.valueOf(requested == null ? "" : requested).trim().toLowerCase(Locale.ROOT);
        String property = SORTABLE.getOrDefault(key, DEFAULT_SORT);

        return property.equals(DEFAULT_SORT)
                ? Sort.by(Sort.Order.asc(property))
                : Sort.by(Sort.Order.asc(property), Sort.Order.asc(DEFAULT_SORT));
    }

    /* ------------------------------------------------------------------ *
     *  Small shared helpers                                               *
     * ------------------------------------------------------------------ */

    private Booking requireBooking(Integer bookingId) {
        if (bookingId == null) {
            throw new ValidationException("BOOKING_ID_REQUIRED", "A booking id is required.");
        }
        return bookings.findById(bookingId)
                .orElseThrow(() -> ResourceNotFoundException.of("Booking", bookingId));
    }

    private static boolean isCancelled(Booking booking) {
        return CANCELLED.equals(upper(booking.getBookingStatus()));
    }

    private static String upper(String value) {
        return String.valueOf(value == null ? "" : value).trim().toUpperCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        String trimmed = String.valueOf(value == null ? "" : value).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * The transaction reference this server sends to the gateway and stores on the row.
     *
     * <p>A UUID rather than a derived value, for two reasons. eSewa's {@code ePay} v2
     * rejects a {@code transaction_uuid} it has already seen for a completed transaction,
     * so a re-initiated attempt must mint a new one — which a uuid does by construction
     * where a {@code bookingId + timestamp} would eventually collide. And it is opaque on
     * purpose: nothing about the booking, the customer or the amount is readable from it,
     * so a transaction reference appearing in a log line or a support email leaks nothing.
     *
     * <p>The one cost is stated rather than hidden: the admin ledger shows this value as
     * the transaction id for a pending payment, where the mock path left that field blank
     * until the gateway answered. It is the honest value — there <i>is</i> a reference,
     * this server minted it, and it is what eSewa will be asked about.
     */
    private static String transactionUuid() {
        return UUID.randomUUID().toString();
    }
}
