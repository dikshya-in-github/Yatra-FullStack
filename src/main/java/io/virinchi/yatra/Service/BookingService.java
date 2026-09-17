package io.virinchi.yatra.Service;

import io.virinchi.yatra.Dto.BookingRequest;
import io.virinchi.yatra.Exception.ConflictException;
import io.virinchi.yatra.Exception.ResourceNotFoundException;
import io.virinchi.yatra.Exception.ValidationException;
import io.virinchi.yatra.Model.Booking;
import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Model.Flight;
import io.virinchi.yatra.Model.Passenger;
import io.virinchi.yatra.Model.Seat;
import io.virinchi.yatra.Repository.BookingRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import io.virinchi.yatra.Repository.PassengerRepository;
import io.virinchi.yatra.Repository.PaymentRepository;
import io.virinchi.yatra.Repository.SeatRepository;
import io.virinchi.yatra.Repository.TicketRepository;
import io.virinchi.yatra.Repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Booking lifecycle — cancel and delete.
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
public class BookingService {

    private static final String CANCELLED = "CANCELLED";
    private static final String PENDING = "PENDING";
    private static final String PAYMENT_PENDING = "Pending";
    private static final String BOOKED = "BOOKED";
    private static final String AVAILABLE = "AVAILABLE";
    private static final String ADULT = "ADT";

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
        booking.setBookingStatus(CANCELLED);
        //paymentStatus jaanatan chhodeko: refund PaymentService le garda hunxa,
        //ani seat rows pani BOOKED nai rahanxan (frontend ko cancel message sanga match hunxa).
        return bookings.save(booking);
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
