package io.virinchi.yatra.Service;

import io.virinchi.yatra.Dto.AdminBookingResponse;
import io.virinchi.yatra.Dto.BookingRequest;
import io.virinchi.yatra.Exception.ConflictException;
import io.virinchi.yatra.Exception.ResourceNotFoundException;
import io.virinchi.yatra.Exception.ValidationException;
import io.virinchi.yatra.Model.Booking;
import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Model.Flight;
import io.virinchi.yatra.Model.Passenger;
import io.virinchi.yatra.Model.Payment;
import io.virinchi.yatra.Model.Seat;
import io.virinchi.yatra.Repository.BookingRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import io.virinchi.yatra.Repository.PassengerRepository;
import io.virinchi.yatra.Repository.PaymentRepository;
import io.virinchi.yatra.Repository.SeatRepository;
import io.virinchi.yatra.Repository.TicketRepository;
import io.virinchi.yatra.Repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Booking lifecycle — create, cancel, delete, and (Phase 9) the admin management
 * surface: list, filter, page, detail and status changes.
 *
 * <p><b>One service per domain, so the admin reads live here too.</b> The admin
 * surface is not a second domain: the transition matrix below has to reach the same
 * private {@code cancel(...)} helper the R4 delete policy already uses, or the two
 * ways to cancel a booking would eventually disagree. The class therefore owns the
 * whole booking lifecycle and the controller stays a translation layer.
 *
 * <p><b>Why a hard delete needs an owner.</b> A booking is the parent of
 * passengers, a payment and a ticket. Probed against the real TiDB schema,
 * {@code bookingRepository.delete(booking)} behaves differently depending on
 * what is already in the persistence context:
 *
 * <ul>
 *   <li>with the children <i>managed</i> (any service that validates before it
 *       deletes will have loaded them) it fails with
 *       {@code TransientPropertyValueException} — Hibernate's pre-flush
 *       transient-reference check ({@code CascadingActions.isChildTransient})
 *       treats a <b>deleted</b> parent as an unsaved transient one whenever the
 *       child's FK is not DB-level delete-cascaded, so
 *       {@code cascade = ALL} on the collection does <b>not</b> rescue it;</li>
 *   <li>with a clean session it happens to succeed.</li>
 * </ul>
 *
 * <p>So "delete the parent and trust the cascade" is order-dependent luck. This
 * service removes the children first, in dependency order, which is the ordering
 * the probe proved works in both situations.
 *
 * <h2>Double booking — why the guard is a conditional UPDATE, not the unique key</h2>
 *
 * <p>Rule 2 says the <code>(flight_id, seat_number)</code> unique constraint plus a
 * transaction catches the second booking. Measured against this schema, the
 * constraint <b>cannot</b> be that guard, and it is worth being exact about why:
 * the seat map is pre-generated ({@code FlightService}), so seat {@code 1A} is one
 * long-lived row that two bookings both {@code UPDATE} to {@code BOOKED}. A unique
 * key refuses a duplicate <i>row</i>; it has no opinion about two transactions
 * writing the same row, and the second UPDATE silently overwrites the first — no
 * exception, no 409, a seat sold twice, which is exactly the failure the rule
 * exists to prevent.
 *
 * <p>So the guard is {@link SeatRepository#claim}: a single
 * {@code UPDATE … WHERE status = 'AVAILABLE'} whose <b>row count</b> is the
 * verdict. The database serialises the two statements, one sees a row changed and
 * the other sees zero, and only then does the loser get 409
 * {@code SEAT_ALREADY_BOOKED}. The unique key stays valuable as the constraint
 * that makes "one row per seat" true — it is just not the concurrency guard, and
 * {@code GlobalExceptionHandler}'s {@code 1062} branch remains the safety net for
 * any other unique violation.
 *
 * <p><b>Why the hold happens at booking time</b> rather than at payment. The
 * wizard gives the customer a 15-minute hold and creates the booking before it
 * asks for money, so a seat released only on payment would let two customers fill
 * in every passenger detail and have the second one fail at the gateway. Holding
 * here costs one {@code UPDATE} per passenger and is undone by
 * {@link #deleteBooking(int)}; {@link #cancelBooking(int)} deliberately keeps the
 * seats counted (R4's policy, and what the admin page's dialog promises).
 *
 * <h2>The pending row</h2>
 *
 * <p>{@link #createBooking} writes exactly what the wizard has collected:
 * {@code PENDING} / payment {@code Pending}, one {@link Passenger} per traveller
 * with its seat number, and a total that is <b>computed here</b> — the request's
 * {@code amount} is accepted and ignored, because a price that arrives from the
 * browser is a price the browser can change.
 *
 * <p><b>The policy on top of the mechanism:</b> {@link #deleteBooking(int)} is
 * deliberately restricted to bookings that have <i>no</i> payment and no ticket
 * (an abandoned/pending attempt). A booking that was paid for is a sale record —
 * the correct admin action is {@link #cancelBooking(int)}, exactly as the
 * frontend's own confirm dialog says ("Seats stay counted on the flight; the
 * refund itself is completed by the payments flow"). The payment/ticket FKs keep
 * no DB-level cascade, so the database refuses that delete too: the rule is
 * enforced twice, and the service check only exists to turn it into a clean 409.
 */
@Service
@Slf4j
public class BookingService {

    private static final String CANCELLED = "CANCELLED";
    private static final String CONFIRMED = "CONFIRMED";
    private static final String PENDING = "PENDING";
    private static final String PAYMENT_PENDING = "Pending";
    private static final String PAYMENT_PAID = "Paid";
    /** The <i>payment row's</i> status once the gateway has been paid — not the booking's text. */
    private static final String SUCCESS = "SUCCESS";
    private static final String BOOKED = "BOOKED";
    private static final String AVAILABLE = "AVAILABLE";
    private static final String ADULT = "ADT";

    /**
     * The sort properties the admin booking list may be ordered by, keyed by the
     * name a caller would use — anything not listed falls back to {@code id}, so no
     * request can order by a column that does not exist (R6, the rule
     * {@code AirlineService} and {@code FlightService} already enforce). The names
     * are the <i>booking's own</i> columns, not a joined table's: sorting by flight
     * number would need the join alias in the {@code ORDER BY}, which is not what a
     * {@code Sort} passed to a JPA query produces.
     */
    private static final Map<String, String> SORTABLE = Map.of(
            "id", "id",
            "customer", "contactName",
            "status", "bookingStatus",
            "payment", "paymentStatus",
            "amount", "totalAmount",
            "created", "createdAt");

    private static final String DEFAULT_SORT = "id";

    private final BookingRepository bookings;
    private final PassengerRepository passengers;
    private final PaymentRepository payments;
    private final TicketRepository tickets;
    private final SeatRepository seats;
    private final FlightRepository flights;
    private final UserRepository users;

    public BookingService(BookingRepository bookings,
                          PassengerRepository passengers,
                          PaymentRepository payments,
                          TicketRepository tickets,
                          SeatRepository seats,
                          FlightRepository flights,
                          UserRepository users) {
        this.bookings = bookings;
        this.passengers = passengers;
        this.payments = payments;
        this.tickets = tickets;
        this.seats = seats;
        this.flights = flights;
        this.users = users;
    }

    /**
     * Soft cancel — the only "delete" a customer-facing booking ever gets.
     *
     * <p>Flips {@code bookingStatus} to {@code CANCELLED} and touches nothing
     * else: the payment row is left for the refund flow to update, and
     * the seats stay {@code BOOKED} because the frontend tells the admin exactly
     * that. Idempotent, so a double-click is harmless.
     */
    @Transactional
    public Booking cancelBooking(int bookingId) {
        Booking booking = require(bookingId);
        if (CANCELLED.equalsIgnoreCase(booking.getBookingStatus())) {
            return booking;   // already cancelled — no-op, not an error
        }
        cancel(booking);
        return bookings.save(booking);
    }

    /**
     * The soft cancel itself, shared by {@link #cancelBooking(int)} and the admin
     * status endpoint so the two can never drift.
     *
     * <p>Exactly one field changes. {@code paymentStatus} is deliberately untouched
     * — the refund is {@code PaymentService}'s job (Phase 10), and writing
     * "Refunded" here would claim a gateway action that never happened. The seat rows
     * stay {@code BOOKED} too: the admin page's own dialog promises the admin that
     * "seats stay counted on the flight", and releasing them here would silently
     * increase the flight's availability without anybody changing the booking count.
     */
    private static void cancel(Booking booking) {
        booking.setBookingStatus(CANCELLED);
    }

    /* ------------------------------------------------------------------ *
     *  Admin management (Roadmap Phase 9)                                  *
     * ------------------------------------------------------------------ */

    /**
     * Every matching booking, in a deterministic order — the admin table's read.
     *
     * <p><b>Why this returns DTOs and not entities.</b> Every other module's service
     * hands the controller entities and the DTO mapping happens in the controller
     * ({@code FlightResponse.of(flight, bookedSeats)}). That cannot work here: the
     * response carries the passenger list, the payment and the ticket, and the
     * passengers are fetched as a <i>second batched query</i> after the page query
     * (a collection cannot be graph-fetched alongside pagination). Mapping in the
     * controller would therefore either fire a query per row or throw a
     * {@code LazyInitializationException} once the transaction had closed. So the
     * read transaction owns the mapping, and the controller stays a one-line
     * translation — the same shape {@code DestinationService} already took for its
     * list.
     *
     * <p><b>What each filter means.</b> {@code search} spans the PNR, ticket number,
     * contact name/email/phone and the flight number (what the page's search box
     * promises); a purely numeric term is matched against the booking id too.
     * {@code date} is the <b>flight's travel date</b> — "which departures are on this
     * day" — while {@code created} is the <b>booking's creation date</b>, the
     * daily-sales view. They are separate parameters on purpose: collapsing them
     * would make one of the two questions unanswerable, and a caller that wants both
     * simply sends both.
     *
     * @param status        {@code PENDING} / {@code CONFIRMED} / {@code CANCELLED}, any
     *                      case, or {@code ALL}/blank for no filter
     * @param paymentStatus {@code Pending} / {@code Paid} / {@code Failed} /
     *                      {@code Refunded}, any case, or {@code ALL}/blank
     * @param sort          one of {@code id|customer|status|payment|amount|created} —
     *                      anything else falls back to {@code id}
     */
    @Transactional(readOnly = true)
    public List<AdminBookingResponse> listBookings(String search, String status, String paymentStatus,
                                                   Integer flightId, LocalDate date, LocalDate created,
                                                   String sort) {
        List<Booking> rows = bookings.searchAll(
                like(search), idTerm(search), storedStatus(status), storedPaymentStatus(paymentStatus),
                flightId, date, startOfDay(created), endOfDay(created), sortFor(sort));

        return responses(rows);
    }

    /** One page of matching bookings, plus the counts needed to walk the rest. */
    @Transactional(readOnly = true)
    public Page<AdminBookingResponse> listBookingPage(String search, String status, String paymentStatus,
                                                      Integer flightId, LocalDate date, LocalDate created,
                                                      String sort, int page, int size) {
        PageRequest request = Paging.request(page, size, sortFor(sort));

        Page<Booking> rows = bookings.searchPage(
                like(search), idTerm(search), storedStatus(status), storedPaymentStatus(paymentStatus),
                flightId, date, startOfDay(created), endOfDay(created), request);

        Map<Integer, List<Passenger>> byBooking = passengersFor(rows.getContent());
        return rows.map(booking -> AdminBookingResponse.of(
                booking, byBooking.getOrDefault(booking.getId(), List.of())));
    }

    /**
     * Every booking owned by one account — {@code GET /api/admin/users/{id}/bookings},
     * Roadmap Phase 11.
     *
     * <p><b>Why this lives here and not in {@code UserService}.</b> The rows it returns are
     * bookings, in the admin booking shape, and getting them right needs
     * {@link #passengersFor(List)} — the batched second query that stops a page of rows
     * firing a query per row. A user-management service would have to reach into the
     * booking tables (or duplicate that batching) to produce the same records; this is
     * simply a narrower filter over the read that already exists, so the mapper stays in
     * one place.
     *
     * <p><b>It answers the question the delete guard raises.</b> {@code deleteUser} refuses
     * with "N bookings on record — set the account to Inactive instead", and this is where
     * the admin can see which bookings those are before deciding. Unpaged on purpose:
     * it is one account's history, not a table the panel pages through.
     */
    @Transactional(readOnly = true)
    public List<AdminBookingResponse> listBookingsForUser(int userId) {
        return responses(bookings.findByUserId(userId));
    }

    /**
     * One booking with everything the detail modal shows: the contact block, every
     * passenger, the flight, the payment and the ticket.
     *
     * <p>No entity graph is needed for a single row — the flight and its airline and
     * airports are {@code @ManyToOne} (eager), and the two {@code mappedBy} children
     * are at most two lazy loads inside this transaction.
     *
     * @throws ResourceNotFoundException 404 {@code BOOKING_NOT_FOUND}
     */
    @Transactional(readOnly = true)
    public AdminBookingResponse getBooking(int bookingId) {
        Booking booking = require(bookingId);
        return AdminBookingResponse.of(booking, passengers.findByBookingId(bookingId));
    }

    /**
     * Moves a booking's status, enforcing Phase 9's transition matrix.
     *
     * <h2>The matrix, and why it is enforced here rather than in a controller</h2>
     * <ul>
     *   <li><b>{@code → CANCELLED} from any live state</b> — the same soft cancel the
     *       admin page offers, through the same private helper, so the two entry
     *       points cannot diverge. Idempotent: setting the status it already has is a
     *       no-op, not an error, so a double-click is harmless.</li>
     *   <li><b>{@code PENDING → CONFIRMED} only with a successful payment.</b> The gate
     *       reads the <b>payment row's</b> {@code status} ({@code SUCCESS}), not the
     *       booking's {@code paymentStatus} text, because the payment row is the
     *       record of what the gateway actually answered. On success the booking's
     *       {@code paymentStatus} is set to {@code Paid} as well, so the row cannot
     *       end up {@code CONFIRMED} while displaying "Pending" — the two fields are
     *       on the same row and the admin list shows both.</li>
     *   <li><b>{@code CONFIRMED → PENDING} and {@code CANCELLED → PENDING} are
     *       refused</b> ({@code INVALID_STATUS_TRANSITION}). A confirmed booking has a
     *       paid payment and usually a ticket; demoting it would leave a ticket
     *       pointing at a booking the admin list calls unpaid. A cancelled booking
     *       has no seats to hand back here either — rebooking is a new booking, not a
     *       status flip.</li>
     * </ul>
     *
     * <p><b>Nothing else moves.</b> No ticket is minted (Phase 12 owns that), no
     * payment is written, and cancelling does not free seats (R4's policy). This
     * endpoint changes a status and nothing else — which is also why it is a
     * {@code PUT} on {@code /status} rather than a general booking {@code PUT}.
     *
     * @throws ResourceNotFoundException 404 — no such booking
     * @throws ValidationException       400 — a status that is not one of the three
     * @throws ConflictException         409 {@code PAYMENT_NOT_SUCCESSFUL} or
     *                                   {@code INVALID_STATUS_TRANSITION}
     */
    @Transactional
    public AdminBookingResponse updateStatus(int bookingId, String requestedStatus) {
        Booking booking = require(bookingId);

        String target = normalizeStatus(requestedStatus);
        if (target == null) {
            throw new ValidationException("UNKNOWN_BOOKING_STATUS",
                    "Status must be one of PENDING, CONFIRMED or CANCELLED.");
        }

        String current = normalizeStatus(booking.getBookingStatus());
        if (target.equals(current)) {
            //Already tyahi status — no-op, error hoina (double-click safe).
            return AdminBookingResponse.of(booking, passengers.findByBookingId(bookingId));
        }

        if (CANCELLED.equals(target)) {
            cancel(booking);
        } else if (CONFIRMED.equals(target)) {
            confirm(booking, current);
        } else {
            throw ConflictException.invalidStatusTransition(
                    bookingId, AdminBookingResponse.displayStatus(current),
                    AdminBookingResponse.displayStatus(target));
        }

        Booking saved = bookings.save(booking);
        return AdminBookingResponse.of(saved, passengers.findByBookingId(bookingId));
    }

    /**
     * Confirms a pending booking whose payment really succeeded.
     *
     * <p>Two refusals, deliberately different: a booking that is not PENDING cannot
     * be confirmed at all (a transition problem), and a PENDING booking whose
     * payment is missing or not {@code SUCCESS} is refused by the payment gate —
     * which is the rule the roadmap names, and the reason "confirmation" is not just
     * a text field an admin can type into.
     */
    private void confirm(Booking booking, String current) {
        if (!PENDING.equals(current)) {
            throw ConflictException.invalidStatusTransition(
                    booking.getId(), AdminBookingResponse.displayStatus(current),
                    AdminBookingResponse.displayStatus(CONFIRMED));
        }

        //One read, two questions: is there a payment row, and did it succeed?
        String paymentStatus = payments.findByBookingId(booking.getId())
                .map(Payment::getStatus)
                .orElse(null);

        if (paymentStatus == null || !SUCCESS.equalsIgnoreCase(paymentStatus)) {
            throw ConflictException.confirmationRequiresPayment(
                    booking.getId(),
                    paymentStatus == null ? "none on record" : paymentStatus);
        }

        booking.setBookingStatus(CONFIRMED);
        booking.setPaymentStatus(PAYMENT_PAID);
    }

    /* ------------------------------------------------------------------ *
     *  Admin read helpers                                                 *
     * ------------------------------------------------------------------ */

    /** Maps rows to responses, batching the passengers of the whole set in one query. */
    private List<AdminBookingResponse> responses(List<Booking> rows) {
        Map<Integer, List<Passenger>> byBooking = passengersFor(rows);
        return rows.stream()
                .map(booking -> AdminBookingResponse.of(
                        booking, byBooking.getOrDefault(booking.getId(), List.of())))
                .toList();
    }

    /**
     * The passengers of the listed bookings, grouped by booking id.
     *
     * <p>{@code Passenger.booking} is {@code @ManyToOne} and the booking rows are
     * already in the persistence context, so grouping by
     * {@code passenger.getBooking().getId()} costs no extra select — Hibernate
     * answers it from the first-level cache. This is the one place the admin list
     * touches the {@code passenger} table, which is what keeps a 50-row page from
     * firing 50 queries.
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
     * The stored form of a requested status, or {@code null} for "no filter".
     *
     * <p>{@code ALL} is folded to {@code null} as well as blank: the page's filter
     * dropdowns send {@code ALL} for "no filter", and a caller that passes it
     * straight through would otherwise get zero rows — the exact opposite of what it
     * asked for.
     */
    private static String normalizeStatus(String status) {
        String value = String.valueOf(status == null ? "" : status).trim();
        return value.isEmpty() ? null : value.toUpperCase(Locale.ROOT);
    }

    private static String storedStatus(String status) {
        String value = normalizeStatus(status);
        return "ALL".equals(value) ? null : value;
    }

    /**
     * The booking's {@code paymentStatus} is stored in the page's own
     * title-case vocabulary ({@code Paid}), so the incoming value is only case-folded
     * — there is no second vocabulary to translate here.
     */
    private static String storedPaymentStatus(String paymentStatus) {
        String value = String.valueOf(paymentStatus == null ? "" : paymentStatus).trim();
        if (value.isEmpty() || "ALL".equalsIgnoreCase(value)) {
            return null;
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /** A {@code LIKE} pattern for the free-text search, or {@code null} for "no filter". */
    private static String like(String search) {
        String term = String.valueOf(search == null ? "" : search).trim().toLowerCase(Locale.ROOT);
        return term.isEmpty() ? null : "%" + term + "%";
    }

    /**
     * The same search term as a booking id, when it is one — otherwise {@code null}.
     *
     * <p>Kept out of the JPQL on purpose. {@code cast(b.id as string)} inside a
     * {@code LIKE} is a dialect-dependent expression that would also try to match a
     * booking id against a name, and the mock's own ids ({@code "BKG10000001"}) are
     * strings that do not exist in this schema — so the only case worth supporting is
     * a caller pasting the id the list just gave it back.
     */
    private static Integer idTerm(String search) {
        String term = String.valueOf(search == null ? "" : search).trim();
        if (term.isEmpty() || term.length() > 9) {
            return null;
        }
        return term.chars().allMatch(Character::isDigit) ? Integer.valueOf(term) : null;
    }

    /** The start of the requested creation day, or {@code null} for "any date". */
    private static LocalDateTime startOfDay(LocalDate created) {
        return created == null ? null : created.atStartOfDay();
    }

    /**
     * The exclusive upper bound of the requested creation day.
     *
     * <p>Half-open ({@code >= day 00:00} and {@code < next day 00:00}) rather than
     * {@code between day 00:00 and day 23:59:59}: a stored timestamp carries
     * fractional seconds, so an inclusive upper bound would silently drop anything
     * written in the last second of the day — the classic date-filter bug.
     */
    private static LocalDateTime endOfDay(LocalDate created) {
        return created == null ? null : created.plusDays(1).atStartOfDay();
    }

    /**
     * Resolves a requested sort to a whitelisted column, always finishing with
     * {@code id} so bookings sharing a status or a customer still page in a stable
     * order. Anything unrecognised falls back to {@code id} — the value never reaches
     * the database (R6).
     */
    private static Sort sortFor(String requested) {
        String key = String.valueOf(requested == null ? "" : requested).trim().toLowerCase(Locale.ROOT);
        String property = SORTABLE.getOrDefault(key, DEFAULT_SORT);

        return property.equals(DEFAULT_SORT)
                ? Sort.by(Sort.Order.asc(property))
                : Sort.by(Sort.Order.asc(property), Sort.Order.asc(DEFAULT_SORT));
    }

    /**
     * Hard delete, restricted to bookings with no sale records — demo/abandoned
     * data only. Refuses with 409 otherwise (see the class docs for why).
     */
    @Transactional
    public void deleteBooking(int bookingId) {
        Booking booking = require(bookingId);

        if (payments.findByBookingId(bookingId).isPresent()
                || tickets.findByBookingId(bookingId).isPresent()) {
            throw ConflictException.bookingHasSaleRecords(bookingId);
        }

        List<Passenger> bookingPassengers = passengers.findByBookingId(bookingId);
        releaseSeats(booking.getFlight(), bookingPassengers);

        //Delete order: children pahile, parent paxi — natra Hibernate ko
        //pre-flush transient-reference check le TransientPropertyValueException
        //throw garxa (collection ma cascade = ALL hunu le pani bachdaina).
        passengers.deleteAll(bookingPassengers);
        passengers.flush();          // FK-safe order: passenger rows before the booking row
        bookings.delete(booking);
        bookings.flush();
    }

    /* ------------------------------------------------------------------ *
     *  Creating a booking (Roadmap Phase 6)                                *
     * ------------------------------------------------------------------ */

    /**
     * Creates a {@code PENDING} booking, holding one seat per passenger.
     *
     * <p>One transaction for the seats and the booking rows: if any seat in the
     * request is taken, or the flight runs out mid-assignment, the whole thing
     * rolls back and <b>no</b> seat is left held by a booking that does not exist.
     *
     * @param userId the signed-in user's id, or {@code null} for a guest — the
     *               wizard is public, and {@code booking.user_id} is nullable for
     *               exactly this reason
     * @throws ResourceNotFoundException 404 {@code FLIGHT_NOT_FOUND} — no flight
     *                                   with that number
     * @throws ValidationException       400 — a route that does not match the
     *                                   flight, or a seat number the flight does
     *                                   not have
     * @throws ConflictException         409 {@code SEAT_ALREADY_BOOKED} /
     *                                   {@code FLIGHT_SOLD_OUT}
     */
    @Transactional
    public Booking createBooking(BookingRequest request, Integer userId) {
        Flight flight = resolveFlight(request.flight());

        //Seats pahile: kunai pani seat milena vane tala ko kunai row save hudaina
        //(transaction rollback), so hold gareko seat orphan rahanna.
        List<String> held = holdSeats(flight, request.passengers());

        Booking booking = new Booking();
        booking.setUser(userId == null ? null : users.findById(userId).orElse(null));
        booking.setFlight(flight);
        booking.setContactName(contactName(request.contact()));
        booking.setContactEmail(blankToNull(request.contact().email()));
        booking.setContactPhone(normalizePhone(request.contact().phone()));
        booking.setBookingStatus(PENDING);
        booking.setPaymentStatus(PAYMENT_PENDING);
        booking.setFareClass(blankToNull(request.flight().flightClass()));
        booking.setRefundable(Boolean.TRUE.equals(request.flight().refundable()));

        //Money server-side: fare × paying passengers, scale 2. The request's own
        //`amount` is deliberately not read — see Dto/BookingRequest.
        BigDecimal payable = payable(flight, request.passengers().size());
        booking.setProductAmount(payable);
        booking.setTotalAmount(payable);
        booking.setCreatedAt(LocalDateTime.now());

        Booking saved = bookings.save(booking);

        List<Passenger> rows = new ArrayList<>(request.passengers().size());
        for (int index = 0; index < request.passengers().size(); index++) {
            rows.add(passenger(request.passengers().get(index), saved, held.get(index)));
        }
        passengers.saveAll(rows);
        passengers.flush();

        //The roadmap's "bookings created" event (Phase 14). Deliberately no contact
        //name/email/phone: the id, the flight, the money and the seats are what an
        //audit trail needs, and the contact block is the customer's personal data
        //sitting in a log file forever. See the logging rule in the standards doc.
        log.info("Booking created: id={} flight={} passengers={} seats=[{}] total={} status={}",
                saved.getId(), flight.getFlightNo(), rows.size(), String.join(",", held),
                saved.getTotalAmount(), saved.getBookingStatus());

        return saved;
    }

    /**
     * Resolves the wizard's selected flight by its number.
     *
     * <p>{@code flight_no} is UNIQUE, so the number alone is the key — the page
     * carries no id for the selected flight.
     *
     * <p><b>Then the route is checked.</b> {@code booking.js} posts whatever is in
     * {@code sessionStorage.yatra_selected_flight}; a stale record from an earlier
     * search would otherwise book a real flight while the page shows a different
     * route and a different price. A mismatch is a 400 telling the caller to search
     * again, not a silent booking on the wrong route. A request with no route at all
     * (Postman, a future API client) skips the check rather than failing it.
     */
    private Flight resolveFlight(BookingRequest.SelectedFlight selection) {
        String flightNo = normalizeFlightNo(selection.flightNo());

        Flight flight = flights.findByFlightNo(flightNo)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "FLIGHT_NOT_FOUND", "No flight exists with number " + flightNo + "."));

        String origin = code(flight.getOrigin());
        String destination = code(flight.getDestination());
        String requestedFrom = blankToNull(selection.from());
        String requestedTo = blankToNull(selection.to());

        boolean fromMismatch = requestedFrom != null
                && !requestedFrom.equalsIgnoreCase(origin);
        boolean toMismatch = requestedTo != null
                && !requestedTo.equalsIgnoreCase(destination);

        if (fromMismatch || toMismatch) {
            throw new ValidationException("ROUTE_MISMATCH",
                    "Flight " + flightNo + " flies " + origin + " → " + destination
                            + ", but this booking is for " + requestedFrom + " → " + requestedTo
                            + ". Search again and select the flight you want.");
        }

        return flight;
    }

    /**
     * Holds one seat per passenger, in passenger order.
     *
     * <p><b>Named seats are honoured exactly or refused.</b> A request that asks for
     * {@code 1A} gets {@code 1A} or a 409 — never a quiet substitute, which would
     * hand the customer a seat they did not choose.
     *
     * <p><b>Unnamed seats are assigned lowest-first.</b> The wizard has no seat
     * picker yet, so this is today's normal path: without it a booking created from
     * the real page would leave {@code bookedSeats} at zero and the cabin looking
     * empty while a booking exists — the {@code seat} table contradicting the
     * flight. Lowest-first is also deterministic, so a repeated request sees a
     * stable map rather than a random one.
     *
     * <p>Runs in the caller's transaction, so a refusal here takes the earlier
     * claims back with it.
     */
    private List<String> holdSeats(Flight flight, List<BookingRequest.Passenger> requested) {
        List<String> held = new ArrayList<>(Collections.nCopies(requested.size(), null));
        int outstanding = 0;

        for (int index = 0; index < requested.size(); index++) {
            String seatNumber = normalizeSeat(requested.get(index).seatNumber());
            if (seatNumber == null) {
                outstanding++;
                continue;
            }
            claimSeat(flight, seatNumber);
            held.set(index, seatNumber);
        }

        if (outstanding == 0) {
            return held;
        }

        //Candidates are read once; each is then claimed one by one, so a seat lost
        //to a concurrent booking between the read and the claim is simply skipped.
        List<Seat> free = seats.findByFlightIdAndStatusOrderByIdAsc(flight.getId(), AVAILABLE);
        int candidate = 0;

        for (int index = 0; index < held.size() && outstanding > 0; index++) {
            if (held.get(index) != null) {
                continue;
            }

            String claimed = null;
            while (claimed == null && candidate < free.size()) {
                Seat seat = free.get(candidate++);
                if (seats.claim(seat.getId(), BOOKED, AVAILABLE) == 1) {
                    claimed = seat.getSeatNumber();
                }
            }

            if (claimed == null) {
                throw ConflictException.notEnoughSeats(flight.getFlightNo(), requested.size(),
                        seats.countByFlightIdAndStatus(flight.getId(), AVAILABLE));
            }

            held.set(index, claimed);
            outstanding--;
        }

        return held;
    }

    /**
     * Claims one named seat, or refuses with 409.
     *
     * <p><b>Nothing here calls {@code setStatus}.</b> {@link SeatRepository#claim}
     * changes the row behind the entity's back, so mutating the entity would write
     * the stale {@code AVAILABLE} straight back over the claim at flush time. The
     * conditional UPDATE's result is the only thing consulted.
     */
    private void claimSeat(Flight flight, String seatNumber) {
        Seat seat = seats.findByFlightIdAndSeatNumber(flight.getId(), seatNumber)
                .orElseThrow(() -> new ValidationException("UNKNOWN_SEAT",
                        "Flight " + flight.getFlightNo() + " has no seat " + seatNumber + "."));

        if (seats.claim(seat.getId(), BOOKED, AVAILABLE) == 0) {
            throw ConflictException.seatAlreadyBooked(flight.getFlightNo(), seatNumber);
        }
    }

    /**
     * The amount the gateway will be asked for: the flight's own fare × the paying
     * passengers, at currency scale.
     *
     * <p>{@code BigDecimal} throughout (R5): {@code BigDecimal.valueOf(...)} for the
     * count and {@code setScale(2, HALF_UP)} for the currency, never a {@code double}
     * multiply.
     */
    private static BigDecimal payable(Flight flight, int payingPassengers) {
        return flight.getFare()
                .multiply(BigDecimal.valueOf(payingPassengers))
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * One passenger row.
     *
     * <p><b>The middle name is folded into the first name, not dropped.</b> The
     * {@code passenger} table has no middle-name column, and an e-ticket has to show
     * the name as it was entered — so {@code "Hari Prasad"} lands in
     * {@code first_name} rather than being discarded. Same call {@code UserService}
     * makes when it assembles {@code User.name}; splitting it out later is a schema
     * change (entity → {@code db/schema.sql} → apply), which belongs to whichever
     * phase needs it.
     */
    private static Passenger passenger(BookingRequest.Passenger requested, Booking booking, String seatNumber) {
        Passenger row = new Passenger();
        row.setBooking(booking);
        row.setTitle(blankToNull(requested.title()));
        row.setFirstName(join(requested.firstName(), requested.middleName()));
        row.setLastName(blankToNull(requested.lastName()));
        row.setPassengerType(passengerType(requested.type()));
        row.setNationality(blankToNull(requested.nationality()));
        row.setSeatNumber(seatNumber);
        return row;
    }

    /** Two name parts, blanks dropped — {@code "Hari"} + blank stays {@code "Hari"}. */
    private static String join(String first, String middle) {
        return Arrays.asList(blankToNull(first), blankToNull(middle)).stream()
                .filter(part -> part != null)
                .collect(Collectors.joining(" "));
    }

    /** The wizard's {@code type}; blank means an adult, which is the form's default. */
    private static String passengerType(String type) {
        String value = blankToNull(type);
        return value == null ? ADULT : value.toUpperCase(Locale.ROOT);
    }

    /**
     * The contact name, built like {@code UserService.fullName}: title, first, middle
     * and last, blanks dropped — so the field is never the empty string for a form
     * that left the title alone.
     */
    private static String contactName(BookingRequest.Contact contact) {
        return Arrays.asList(contact.title(), contact.firstName(), contact.middleName(), contact.lastName())
                .stream()
                .map(BookingService::blankToNull)
                .filter(part -> part != null)
                .collect(Collectors.joining(" "));
    }

    /** Canonical flight number, exactly as {@code FlightService} normalises it. */
    private static String normalizeFlightNo(String flightNo) {
        return String.valueOf(flightNo == null ? "" : flightNo)
                .trim()
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
    }

    /** {@code "1a"} → {@code "1A"}; blank means "assign one for me". */
    private static String normalizeSeat(String seatNumber) {
        String value = blankToNull(seatNumber);
        return value == null ? null : value.toUpperCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /**
     * Mirrors {@code MockDB.normalizePhone} and {@code UserService.normalizePhone}:
     * digits only, a {@code +977} country code stripped — so the contact number is
     * stored in the same 10-digit form the account roster uses.
     */
    private static String normalizePhone(String phone) {
        String digits = String.valueOf(phone == null ? "" : phone).replaceAll("\\D", "");
        return digits.length() == 13 && digits.startsWith("977") ? digits.substring(3) : digits;
    }

    private static String blankToNull(String value) {
        String trimmed = String.valueOf(value == null ? "" : value).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** An airport code, upper-case, {@code ""} when the flight has no such endpoint. */
    private static String code(Destination destination) {
        return destination == null ? "" : String.valueOf(destination.getCode() == null
                ? "" : destination.getCode()).toUpperCase(Locale.ROOT);
    }

    /**
     * Frees the seat rows this booking held, so availability stays honest.
     *
     * <p>Availability is computed as {@code seatCapacity − COUNT(BOOKED
     * seats)}, so a seat left {@code BOOKED} by a deleted booking is a seat that
     * can never be sold again. Only seats that a passenger actually holds are
     * touched, and only cancel-free deletes reach here.
     *
     * <p><b>The release is a conditional UPDATE, not a {@code setStatus}.</b> The
     * read-then-write version is a silent no-op whenever the seat was claimed
     * earlier in the same transaction: {@link SeatRepository#claim} changes the row
     * without touching the cached entity, so the status check sees a stale
     * {@code AVAILABLE} and skips the release. A test caught exactly that, which is
     * why both directions now go through the database's own verdict
     * ({@link SeatRepository#release}).
     */
    private void releaseSeats(Flight flight, List<Passenger> bookingPassengers) {
        if (flight == null) {
            return;
        }
        Set<String> seatNumbers = bookingPassengers.stream()
                .map(Passenger::getSeatNumber)
                .filter(Objects::nonNull)
                .filter(seatNumber -> !seatNumber.isBlank())
                .collect(Collectors.toSet());

        for (String seatNumber : seatNumbers) {
            //Find the row for its id; the status decision is the UPDATE's.
            seats.findByFlightIdAndSeatNumber(flight.getId(), seatNumber)
                    .ifPresent(seat -> seats.release(seat.getId(), AVAILABLE, BOOKED));
        }
    }

    private Booking require(int bookingId) {
        return bookings.findById(bookingId)
                .orElseThrow(() -> ResourceNotFoundException.of("Booking", bookingId));
    }
}
