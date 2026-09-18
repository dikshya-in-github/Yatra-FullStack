package io.virinchi.yatra.Repository;

import io.virinchi.yatra.Model.Ticket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TicketRepository extends JpaRepository<Ticket, Integer> {

    //Ek booking ko ek ticket (1:1 — booking_id unique).
    Optional<Ticket> findByBookingId(int bookingId);

    /**
     * The tickets of <b>several</b> bookings in one query — the hold sweep's batch fetch,
     * the same device {@code PassengerRepository.findByBookingIdIn} provides for the
     * admin list.
     *
     * <p>{@code ticket.booking_id} is UNIQUE, so one row per id at most. The sweep only
     * needs to know <i>which</i> of its candidates have a document, because a ticketed
     * booking is a sale the sweep must not touch — and asking per candidate is a round
     * trip per stale hold against a remote database.
     */
    List<Ticket> findByBookingIdIn(Collection<Integer> bookingIds);

    //Admin ticket search: PNR wa ticket number le. Duitai unique hunuparxa.
    Optional<Ticket> findByPnr(String pnr);
    Optional<Ticket> findByTicketNo(String ticketNo);
    boolean existsByPnr(String pnr);

    //Ticket minting (Phase 10) checks both halves before it saves: both columns are
    //UNIQUE, and the derivation is deterministic on the gateway's txn id, so two
    //transactions 27.8 h apart can produce the same digits.
    boolean existsByTicketNo(String ticketNo);

    List<Ticket> findByStatus(String status);

    /**
     * The admin ticket list — Roadmap Phase 12.
     *
     * <p><b>Queried from {@code Ticket}, not from {@code Booking}.</b> The endpoint
     * lists <i>documents</i>, so every row must have one: an {@code inner join} on
     * the 1:1 booking is the filter that says so, and a draft the storefront
     * abandoned at the gateway never appears. The alternative — the Phase 9 booking
     * query with a "has a ticket" flag bolted on — would have put a second,
     * contradictory meaning on a query the booking page already depends on.
     *
     * <p><b>Nullable parameters, one query, not a derived method per combination</b>
     * — the same shape {@code BookingRepository.searchAll} and
     * {@code PaymentRepository.searchAll} use, with {@code ALL} folded to
     * {@code null} by the service before it gets here.
     *
     * <p><b>The search spans three entities</b>, which is why it needs a query of
     * its own: the search box advertises "Search PNR, ticket no, passenger, flight no
     * or booking ID…" — the PNR and ticket number live on this table, the flight
     * number on {@code flight}, and the customer on the booking's contact block.
     * Passenger names are deliberately <b>not</b> searchable: joining the
     * {@code passengers} collection inside a paged query makes Hibernate paginate in
     * memory (load every match, return one page), the trap Phase 9 documented — the
     * page's client-side search still covers it while the list is unpaged.
     *
     * <p><b>A numeric search also matches the booking id</b> ({@code :idSearch},
     * {@code null} unless the term is numeric), OR'd with the text matches rather than
     * AND'd — the bug a Phase 9 test caught, not repeated: the service sets both
     * parameters for a numeric term, so an AND would demand the row match a name
     * <i>and</i> an id.
     *
     * <p><b>Two status filters, and they are not the same column.</b>
     * {@code bookingStatus} is the <i>booking's</i> state in the page's title-case
     * vocabulary, which is what {@code admin-tickets.js}'s dropdown sends and what its
     * "Issued (Confirmed) / Voided (Cancelled)" labels mean — filtering the ticket's
     * own column there would return zero rows and the page would look empty with no
     * error (R16). {@code ticketStatus} is the ticket row's own
     * {@code ISSUED}/{@code CANCELLED}, the state {@code SeedService} writes for a
     * refunded booking's ticket, so a caller can ask for exactly the voided documents.
     * Both are case-folded in the service, so either spelling works.
     *
     * <p><b>{@code @EntityGraph} covers the to-one associations only.</b> The shared
     * row mapper reads the booking, its flight with the airline and both airport
     * codes, its payment and its ticket, so those are fetched; the {@code passengers}
     * collection is deliberately absent and batched by the service instead.
     */
    @EntityGraph(attributePaths = {"booking", "booking.ticket", "booking.payment",
            "booking.flight", "booking.flight.airline", "booking.flight.origin",
            "booking.flight.destination"})
    @Query("""
            select t from Ticket t
            join t.booking b
            join b.flight f
            where ((:search is null and :idSearch is null)
                   or lower(t.pnr) like :search
                   or lower(t.ticketNo) like :search
                   or lower(b.contactName) like :search
                   or lower(b.contactEmail) like :search
                   or lower(b.contactPhone) like :search
                   or lower(f.flightNo) like :search
                   or (:idSearch is not null and b.id = :idSearch))
              and (:bookingStatus is null or upper(b.bookingStatus) = :bookingStatus)
              and (:ticketStatus is null or upper(t.status) = :ticketStatus)
            """)
    List<Ticket> searchAll(@Param("search") String search,
                           @Param("idSearch") Integer idSearch,
                           @Param("bookingStatus") String bookingStatus,
                           @Param("ticketStatus") String ticketStatus,
                           Sort sort);

    /** The same filters, one page at a time. */
    @EntityGraph(attributePaths = {"booking", "booking.ticket", "booking.payment",
            "booking.flight", "booking.flight.airline", "booking.flight.origin",
            "booking.flight.destination"})
    @Query("""
            select t from Ticket t
            join t.booking b
            join b.flight f
            where ((:search is null and :idSearch is null)
                   or lower(t.pnr) like :search
                   or lower(t.ticketNo) like :search
                   or lower(b.contactName) like :search
                   or lower(b.contactEmail) like :search
                   or lower(b.contactPhone) like :search
                   or lower(f.flightNo) like :search
                   or (:idSearch is not null and b.id = :idSearch))
              and (:bookingStatus is null or upper(b.bookingStatus) = :bookingStatus)
              and (:ticketStatus is null or upper(t.status) = :ticketStatus)
            """)
    Page<Ticket> searchPage(@Param("search") String search,
                            @Param("idSearch") Integer idSearch,
                            @Param("bookingStatus") String bookingStatus,
                            @Param("ticketStatus") String ticketStatus,
                            Pageable pageable);
}
