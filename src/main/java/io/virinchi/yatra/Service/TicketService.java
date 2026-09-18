package io.virinchi.yatra.Service;

import io.virinchi.yatra.Dto.AdminBookingResponse;
import io.virinchi.yatra.Exception.ResourceNotFoundException;
import io.virinchi.yatra.Model.Booking;
import io.virinchi.yatra.Model.Passenger;
import io.virinchi.yatra.Model.Ticket;
import io.virinchi.yatra.Repository.PassengerRepository;
import io.virinchi.yatra.Repository.TicketRepository;
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
import java.util.stream.Collectors;

/**
 * Ticket minting — the one thing Roadmap Phase 10 has to trigger on a successful
 * payment, kept in its own service so Phase 12 has a home to extend rather than a
 * private method to dig out.
 *
 * <h2>Where a PNR comes from</h2>
 * <p>The mock derives both identifiers from the gateway's transaction id
 * ({@code MockDB.derivePnrTicket}, {@code mock-data.js:362}) and the seeded demo
 * data follows the same shapes, so this mirrors that derivation instead of
 * inventing a second convention:
 *
 * <ul>
 *   <li>{@code pnr} = {@code "YTRA"} + two letters + two digits — six characters,
 *       exactly the seeded {@code YTRA26} / {@code YTRA48} shape. The letters come
 *       from the first two digits of the seed, mapped {@code A + digit % 26}.</li>
 *   <li>{@code ticketNo} = {@code "784-24"} + ten digits, the seeded
 *       {@code 784-244829135701} shape.</li>
 * </ul>
 *
 * <p><b>Deterministic — and therefore collision-checked, which the mock's version
 * is not.</b> Seeding from the transaction id means two transactions can derive
 * the same pair, and this is not hypothetical: the page mints its id as
 * {@code "9A" + Date.now().toString().slice(-8)}, so ids thirty-ish hours apart can
 * share those last eight digits, and {@code 9A…} against {@code 9B…} contributes
 * nothing to the derivation at all. Both columns are UNIQUE, so a collision would
 * surface as a duplicate-key 500 <i>inside the payment transaction</i> — rolling
 * back a payment that had already succeeded at the gateway. Hence the salt:
 * {@link #issue} derives, checks both columns, and on a hit re-derives with the
 * attempt number prefixed, up to a hundred times.
 *
 * <p><b>Idempotent by booking.</b> {@code ticket.booking_id} is UNIQUE and one
 * booking gets one ticket, so issuing twice returns the ticket that exists rather
 * than fighting the constraint. That is what makes re-verifying a payment (a
 * browser retry, a Postman re-run) harmless.
 *
 * <h2>Roadmap Phase 12 added the read half</h2>
 * <p>{@link #listTickets}, {@link #listTicketPage} and {@link #getTicket} serve the
 * admin ticket table. They live here rather than in a service of their own for the
 * same reason {@code BookingService} owns the admin booking reads and
 * {@code PaymentService} the ledger: a module's service owns its module's reads, and
 * the rows it must return are booking-shaped, so the mapping ({@code passengersFor}'s
 * batched second query, {@link AdminBookingResponse}) is already here.
 */
@Service
@Slf4j
public class TicketService {

    /**
     * The status a ticket is minted with — the only value {@link #issue} writes.
     *
     * <p>{@code CANCELLED} is the other value the column takes, and today exactly one
     * producer writes it: {@code SeedService} gives a refunded booking's ticket
     * {@code CANCELLED} so the demo carries a voided document. Nothing in the
     * <i>payment</i> path voids a ticket — a refund moves the payment row and the
     * booking's payment status and deliberately touches nothing else (R4's cancel
     * policy owns those), so voiding an issued ticket is not part of Phase 12's read
     * surface. The list's {@code ticketStatus} filter can still find the state,
     * because the seeder produces it.
     */
    public static final String ISSUED = "ISSUED";

    /** The ticket column's second value — see {@link #ISSUED}. */
    public static final String CANCELLED = "CANCELLED";

    private static final String ALL = "ALL";

    /**
     * The sort properties the ticket list may be ordered by, keyed by the name a
     * caller would use (R6: an explicit whitelist, and an unknown key falls back
     * rather than reaching the database as a column). Only this table's own columns
     * are listed — sorting by the customer or the flight would need those joins in
     * the {@code ORDER BY}, which is not what a {@code Sort} handed to this query
     * produces.
     */
    private static final Map<String, String> SORTABLE = Map.of(
            "id", "id",
            "pnr", "pnr",
            "ticket", "ticketNo",
            "issued", "issuedAt",
            "status", "status");

    private static final String DEFAULT_SORT = "id";

    private static final String PNR_PREFIX = "YTRA";
    private static final String TICKET_PREFIX = "784-24";
    private static final String FALLBACK_DIGITS = "00000000";
    private static final int SEED_LENGTH = 10;
    private static final int MAX_ATTEMPTS = 100;

    private final TicketRepository tickets;
    private final PassengerRepository passengers;

    public TicketService(TicketRepository tickets, PassengerRepository passengers) {
        this.tickets = tickets;
        this.passengers = passengers;
    }

    /**
     * Issues the booking's ticket, or returns the one it already has.
     *
     * <p>Runs in the caller's transaction: minting a ticket is part of completing
     * the sale, so if anything after it fails, the ticket goes back with the rest
     * rather than surviving as an orphan pointing at a booking that was never
     * confirmed.
     *
     * @param txnId the gateway's reference — the seed for both identifiers
     * @throws IllegalStateException when a hundred different salts all collide,
     *                               which cannot happen by accident and would mean
     *                               the check itself is broken
     */
    @Transactional
    public Ticket issue(Booking booking, String txnId) {
        Optional<Ticket> existing = tickets.findByBookingId(booking.getId());
        if (existing.isPresent()) {
            return existing.get();
        }

        LocalDateTime issuedAt = LocalDateTime.now();

        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            PnrTicket ids = derive(txnId, attempt);
            if (tickets.existsByPnr(ids.pnr()) || tickets.existsByTicketNo(ids.ticketNo())) {
                continue;
            }

            Ticket ticket = new Ticket();
            ticket.setBooking(booking);
            ticket.setPnr(ids.pnr());
            ticket.setTicketNo(ids.ticketNo());
            ticket.setStatus(ISSUED);
            ticket.setIssuedAt(issuedAt);

            Ticket saved = tickets.save(ticket);

            //Logged only when a document is really minted: the early return above is
            //the idempotent path (a browser retry re-verifying the same transaction),
            //and logging that as "issued" would make the audit trail claim a second
            //ticket every time somebody refreshed. The salt is worth recording too —
            //a non-zero attempt means the derived identifier had collided.
            log.info("Ticket issued: booking={} pnr={} ticketNo={} derivationAttempt={}",
                    booking.getId(), saved.getPnr(), saved.getTicketNo(), attempt);

            return saved;
        }

        throw new IllegalStateException("Could not mint a unique PNR for booking "
                + booking.getId() + " from transaction " + txnId + " after "
                + MAX_ATTEMPTS + " attempts.");
    }

    /**
     * The booking's ticket, if it has one.
     *
     * <p>Read-only, and deliberately separate from {@link #issue}: the payment
     * flow's idempotent path has to report the existing ticket <b>without</b>
     * minting one, because a booking can be confirmed and then cancelled, and a
     * re-verified payment must not hand a cancelled booking a fresh ticket.
     */
    @Transactional(readOnly = true)
    public Optional<Ticket> find(int bookingId) {
        return tickets.findByBookingId(bookingId);
    }

    /* ------------------------------------------------------------------ *
     *  The admin ticket table (Roadmap Phase 12)                          *
     * ------------------------------------------------------------------ */

    /**
     * Every issued ticket, in a deterministic order — the admin tickets table's read.
     *
     * <p>Returns booking-shaped rows because that is the record
     * {@code admin-tickets.js} renders (it derives a document per booking and ignores
     * anything the response does not nest under {@code bookings}) — see
     * {@link io.virinchi.yatra.Dto.AdminTicketListResponse} for the full reasoning.
     * R6's lesson applies here as everywhere else: the list path gets its own ordered
     * query rather than {@code Pageable.unpaged()}, which would discard the sort.
     *
     * @param search       PNR, ticket number, booking id, contact name/email/phone or
     *                     flight number — the fields the page's search box advertises
     *                     (passenger names are the one term it accepts that this does
     *                     not; see the query's own note)
     * @param status       the <b>booking's</b> status in the page's title-case
     *                     vocabulary — {@code Confirmed} (the page's "Issued"),
     *                     {@code Cancelled} (its "Voided"), {@code Pending} — in any
     *                     case; {@code ALL} or blank for no filter
     * @param ticketStatus the <b>ticket row's own</b> {@code ISSUED}/{@code CANCELLED},
     *                     in any case; {@code ALL} or blank for no filter. This is the
     *                     filter that makes the endpoint a ticket read rather than a
     *                     booking read
     * @param sort         one of {@code id|pnr|ticket|issued|status}; anything else
     *                     falls back to {@code id}
     */
    @Transactional(readOnly = true)
    public List<AdminBookingResponse> listTickets(String search, String status, String ticketStatus,
                                                 String sort) {
        List<Ticket> rows = tickets.searchAll(
                like(search), idTerm(search), noFilterIfAll(status), noFilterIfAll(ticketStatus),
                sortFor(sort));

        return responses(rows);
    }

    /** One page of issued tickets, plus the counts needed to walk the rest. */
    @Transactional(readOnly = true)
    public Page<AdminBookingResponse> listTicketPage(String search, String status, String ticketStatus,
                                                     String sort, int page, int size) {
        PageRequest request = Paging.request(page, size, sortFor(sort));

        Page<Ticket> rows = tickets.searchPage(
                like(search), idTerm(search), noFilterIfAll(status), noFilterIfAll(ticketStatus),
                request);

        Map<Integer, List<Passenger>> byBooking = passengersFor(bookingsOf(rows.getContent()));
        return rows.map(ticket -> AdminBookingResponse.of(
                ticket.getBooking(),
                byBooking.getOrDefault(ticket.getBooking().getId(), List.of())));
    }

    /**
     * One ticket in full — the detail read.
     *
     * <p><b>Addressed by booking id</b>, not by ticket id, because that is the key the
     * page holds: {@code admin-tickets.js} builds each row's {@code data-view} from
     * {@code b.id}, which the shared row mapper renders from the booking's primary
     * key. A ticket is 1:1 with its booking, so naming either one names the same
     * document — the same key choice {@code AdminPaymentController}'s refund makes, and
     * for the same reason.
     *
     * @throws ResourceNotFoundException 404 {@code TICKET_NOT_FOUND} — the booking has
     *                                   no ticket (a draft that never reached the
     *                                   gateway, or a cancelled one that was never paid)
     */
    @Transactional(readOnly = true)
    public AdminBookingResponse getTicket(int bookingId) {
        Ticket ticket = tickets.findByBookingId(bookingId)
                .orElseThrow(() -> ResourceNotFoundException.of("Ticket", bookingId));

        Booking booking = ticket.getBooking();
        return AdminBookingResponse.of(booking, passengers.findByBookingId(booking.getId()));
    }

    /** Maps rows to responses, batching the passengers of the whole set in one query. */
    private List<AdminBookingResponse> responses(List<Ticket> rows) {
        List<Booking> bookingRows = bookingsOf(rows);
        Map<Integer, List<Passenger>> byBooking = passengersFor(bookingRows);

        return bookingRows.stream()
                .map(booking -> AdminBookingResponse.of(
                        booking, byBooking.getOrDefault(booking.getId(), List.of())))
                .toList();
    }

    private static List<Booking> bookingsOf(List<Ticket> rows) {
        return rows.stream()
                .map(Ticket::getBooking)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * The passengers of the listed bookings, in one query — the same batching
     * {@code BookingService} and {@code PaymentService} do for their lists, for the
     * same reason: a fifty-row page costs three queries, not fifty. A fetched
     * collection alongside a paged query would make Hibernate paginate in memory.
     */
    private Map<Integer, List<Passenger>> passengersFor(List<Booking> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }

        List<Integer> ids = rows.stream().map(Booking::getId).toList();
        return passengers.findByBookingIdIn(ids).stream()
                .collect(Collectors.groupingBy(passenger -> passenger.getBooking().getId()));
    }

    /** A {@code LIKE} pattern for the free-text search, or {@code null} for "no filter". */
    private static String like(String search) {
        String term = String.valueOf(search == null ? "" : search).trim().toLowerCase(Locale.ROOT);
        return term.isEmpty() ? null : "%" + term + "%";
    }

    /**
     * The same term as a booking id, when it is one — otherwise {@code null}.
     *
     * <p>Kept out of the JPQL for the reason Phase 9 documented:
     * {@code cast(b.id as string)} is dialect-dependent and would also try to match an
     * id against a name, and the mock's ids ({@code "BKG10000001"}) are strings this
     * schema does not hold. The only case worth supporting is a caller pasting back
     * the id the list just handed it.
     */
    private static Integer idTerm(String search) {
        String term = String.valueOf(search == null ? "" : search).trim();
        if (term.isEmpty() || term.length() > 9) {
            return null;
        }
        return term.chars().allMatch(Character::isDigit) ? Integer.valueOf(term) : null;
    }

    /**
     * A status filter's stored form, or {@code null} for "no filter".
     *
     * <p>{@code ALL} is folded to {@code null} as well as blank: the page's filter
     * dropdowns send {@code ALL} for "no filter", and a caller that passed it straight
     * through would get zero rows — the exact opposite of what it asked for. The value
     * is upper-cased because {@code booking_status} is stored upper-case and the page
     * speaks title-case, while {@code ticket.status} is already upper-case.
     */
    private static String noFilterIfAll(String status) {
        String value = String.valueOf(status == null ? "" : status).trim().toUpperCase(Locale.ROOT);
        return value.isEmpty() || ALL.equals(value) ? null : value;
    }

    /**
     * Resolves a requested sort to a whitelisted column, always finishing with
     * {@code id} so tickets sharing a status still page in a stable order. Anything
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

    /** The two identifiers a transaction id derives. */
    public record PnrTicket(String pnr, String ticketNo) {
    }

    /**
     * The mock's derivation, with a stable shape and an optional salt.
     *
     * <p>The seed is the transaction id's digits, right-padded (or trimmed) to ten
     * so every PNR and ticket number comes out the same length as the seeded ones —
     * a deliberately small deviation from {@code MockDB.derivePnrTicket}, which
     * slices whatever digits it finds and would mint a four-character PNR from a
     * short reference. {@code attempt == 0} is unsalted and is therefore exactly
     * the mock's answer for the common {@code "9A" + 8 digits} id.
     *
     * @param attempt {@code 0} for the first try; anything higher prefixes the seed,
     *                which changes both letters and the digit pair, so repeated
     *                attempts explore different identifiers
     */
    public static PnrTicket derive(String txnId, int attempt) {
        String digits = String.valueOf(txnId == null ? "" : txnId).replaceAll("\\D", "");
        if (digits.isEmpty()) {
            digits = FALLBACK_DIGITS;
        }
        if (attempt > 0) {
            digits = attempt + digits;
        }

        String seed = (digits + "0000000000").substring(0, SEED_LENGTH);
        String pnr = PNR_PREFIX
                + letter(seed.charAt(0))
                + letter(seed.charAt(1))
                + seed.substring(2, 4);

        return new PnrTicket(pnr, TICKET_PREFIX + seed);
    }

    /** {@code '0'} → {@code 'A'} … {@code '9'} → {@code 'J'}, the mock's own mapping. */
    private static char letter(char digit) {
        return (char) ('A' + (digit - '0') % 26);
    }
}
