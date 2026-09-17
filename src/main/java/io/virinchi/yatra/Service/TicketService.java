package io.virinchi.yatra.Service;

import io.virinchi.yatra.Model.Booking;
import io.virinchi.yatra.Model.Ticket;
import io.virinchi.yatra.Repository.TicketRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

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
 */
@Service
public class TicketService {

    /** The only status Phase 10 writes; a refunded booking's ticket is cancelled in Phase 12. */
    public static final String ISSUED = "ISSUED";

    private static final String PNR_PREFIX = "YTRA";
    private static final String TICKET_PREFIX = "784-24";
    private static final String FALLBACK_DIGITS = "00000000";
    private static final int SEED_LENGTH = 10;
    private static final int MAX_ATTEMPTS = 100;

    private final TicketRepository tickets;

    public TicketService(TicketRepository tickets) {
        this.tickets = tickets;
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
            return tickets.save(ticket);
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
