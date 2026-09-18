package io.virinchi.yatra.Repository;

import io.virinchi.yatra.Model.Booking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Integer> {

    //Customer ko booking history: GET /api/users/me/bookings (my-bookings.html).
    List<Booking> findByUserId(int userId);

    //Admin booking management + filter chips.
    List<Booking> findByBookingStatus(String bookingStatus);
    List<Booking> findByPaymentStatus(String paymentStatus);
    Page<Booking> findByBookingStatus(String bookingStatus, Pageable pageable);

    //flight_id FK bata — booked count / flight-wise bookings.
    long countByFlightId(int flightId);
    List<Booking> findByFlightId(int flightId);

    //The demo seeder's own rows (Phase 7) — what `POST /api/admin/reset` removes.
    List<Booking> findBySeededTrue();

    /**
     * The abandoned holds — every {@code PENDING} booking whose 15-minute window has
     * run out. The expiry sweep's candidate list ({@code BookingService.expireHolds}).
     *
     * <p><b>Three clauses, and each one is a deliberate exclusion:</b>
     * <ul>
     *   <li>{@code bookingStatus = PENDING} — the only state that <i>is</i> a hold. A
     *       confirmed booking owns its seats and a cancelled one has already been
     *       dealt with by the admin, so neither is a leak.</li>
     *   <li>{@code seeded = false} — the seeder's own rows belong to
     *       {@code POST /api/admin/reset}, which removes marked rows and reports what
     *       it kept (R14). Sweeping them here would make the demo dataset shrink on a
     *       timer, which is the opposite of what the seeder exists for.</li>
     *   <li>{@code createdAt &lt; cutoff} — the window itself. A {@code null}
     *       {@code created_at} never matches, so a legacy row without a timestamp is
     *       left alone rather than guessed at.</li>
     * </ul>
     *
     * <p><b>Ordered by id so a sweep is reproducible</b>, and because the caller
     * deletes as it walks: an unordered delete loop is the shortest path to a
     * non-deterministic test.
     *
     * <p>A derived query rather than {@code @Query}: there is nothing to join and no
     * nullable filter to fold, which is exactly where Spring Data's method names are
     * clearer than JPQL.
     */
    List<Booking> findByBookingStatusAndSeededFalseAndCreatedAtBeforeOrderByIdAsc(
            String bookingStatus, LocalDateTime cutoff);

    //Reset guard: has this user kept a booking of their own? A seeded account that
    //does is left in place, because deleting it would take that booking with it.
    long countByUserId(int userId);

    /**
     * The admin list — Roadmap Phase 9.
     *
     * <p><b>One nullable-parameter query, not 64 derived ones.</b> Six independent
     * filters would be sixty-four combinations, and the next filter would double
     * that again — so every clause is {@code :param is null or ...} and the service
     * passes {@code null} for "no filter", the same shape
     * {@code FlightRepository.searchAll} uses.
     *
     * <p><b>The search spans four entities, which is why it needs a query of its
     * own.</b> The admin search box promises "Search PNR, ID, customer or flight
     * no…" — the PNR lives on {@code ticket}, the flight number on {@code flight},
     * and the customer on the booking's own contact block, so this is a two-join
     * read rather than a derived method on one entity. Both joins are
     * {@code left}: a booking with no ticket yet is the normal draft state and must
     * still be listed.
     *
     * <p><b>A numeric search is matched against the id as well.</b> The page's search
     * treats {@code "10000001"} as an id; written as {@code cast(b.id as string)}
     * inside the LIKE that would be a dialect-dependent expression, and
     * {@code MockDB}'s string ids ({@code "BKG10000001"}) do not exist here anyway —
     * so the service passes the term as an {@code Integer} when it happens to be
     * numeric ({@code :idSearch}) and this clause is skipped otherwise.
     *
     * <p><b>That clause is OR'd with the text matches, not AND'd — and a test caught
     * it.</b> The first version wrote {@code … like :search) and (:idSearch is null or
     * b.id = :idSearch)}, which is wrong the moment the term is numeric: the service
     * sets <i>both</i> parameters (the LIKE sees {@code "%42%"} and the id clause sees
     * {@code 42}), so the row had to match a name <b>and</b> an id. Searching for the
     * id the list had just handed back returned zero rows. The text matches and the id
     * match are alternatives, so they belong in one disjunction; the leading
     * {@code (:search is null and :idSearch is null)} keeps "no search at all" meaning
     * every row.
     *
     * <p><b>Case is handled on both sides, deliberately.</b> {@code payment_status}
     * is stored as the wizard wrote it ({@code Paid}, {@code Refunded}, {@code Failed}),
     * while {@code booking_status} is stored upper-case and the admin page speaks
     * title-case — so {@code upper(...)} for the status and {@code lower(...)} for
     * the payment status, with the service normalising the incoming value to match.
     * Without that, {@code status=confirmed} would silently match nothing.
     *
     * <p><b>Its own query, not {@code Pageable.unpaged()}.</b> Unpaged discards the
     * {@code Sort} — the R6 lesson — so the list path keeps an explicitly ordered
     * query of its own.
     *
     * <p><b>{@code @EntityGraph} covers the to-one associations only.</b> The
     * response carries the flight, its airline and both airport codes, the payment
     * and the ticket, which would otherwise be six extra selects per row. The
     * {@code passengers} collection is deliberately <b>not</b> in the graph: a
     * fetched collection alongside a paged query makes Hibernate paginate in memory
     * (load every match, return one page), so the service batches passengers with a
     * second query over exactly the ids on the page instead.
     */
    @EntityGraph(attributePaths = {"flight", "flight.airline", "flight.origin",
            "flight.destination", "payment", "ticket"})
    @Query("""
            select b from Booking b
            left join b.flight f
            left join b.ticket t
            where ((:search is null and :idSearch is null)
                   or lower(b.contactName) like :search
                   or lower(b.contactEmail) like :search
                   or lower(b.contactPhone) like :search
                   or lower(f.flightNo) like :search
                   or lower(t.pnr) like :search
                   or lower(t.ticketNo) like :search
                   or (:idSearch is not null and b.id = :idSearch))
              and (:status is null or upper(b.bookingStatus) = :status)
              and (:paymentStatus is null or lower(b.paymentStatus) = :paymentStatus)
              and (:flightId is null or f.id = :flightId)
              and (:date is null or f.flightDate = :date)
              and (:createdFrom is null or (b.createdAt >= :createdFrom and b.createdAt < :createdTo))
            """)
    List<Booking> searchAll(@Param("search") String search,
                            @Param("idSearch") Integer idSearch,
                            @Param("status") String status,
                            @Param("paymentStatus") String paymentStatus,
                            @Param("flightId") Integer flightId,
                            @Param("date") LocalDate date,
                            @Param("createdFrom") LocalDateTime createdFrom,
                            @Param("createdTo") LocalDateTime createdTo,
                            Sort sort);

    /** The same filters, one page at a time. */
    @EntityGraph(attributePaths = {"flight", "flight.airline", "flight.origin",
            "flight.destination", "payment", "ticket"})
    @Query("""
            select b from Booking b
            left join b.flight f
            left join b.ticket t
            where ((:search is null and :idSearch is null)
                   or lower(b.contactName) like :search
                   or lower(b.contactEmail) like :search
                   or lower(b.contactPhone) like :search
                   or lower(f.flightNo) like :search
                   or lower(t.pnr) like :search
                   or lower(t.ticketNo) like :search
                   or (:idSearch is not null and b.id = :idSearch))
              and (:status is null or upper(b.bookingStatus) = :status)
              and (:paymentStatus is null or lower(b.paymentStatus) = :paymentStatus)
              and (:flightId is null or f.id = :flightId)
              and (:date is null or f.flightDate = :date)
              and (:createdFrom is null or (b.createdAt >= :createdFrom and b.createdAt < :createdTo))
            """)
    Page<Booking> searchPage(@Param("search") String search,
                             @Param("idSearch") Integer idSearch,
                             @Param("status") String status,
                             @Param("paymentStatus") String paymentStatus,
                             @Param("flightId") Integer flightId,
                             @Param("date") LocalDate date,
                             @Param("createdFrom") LocalDateTime createdFrom,
                             @Param("createdTo") LocalDateTime createdTo,
                             Pageable pageable);

    /* ------------------------------------------------------------------ *
     *  Dashboard aggregates (Roadmap Phase 13)                            *
     * ------------------------------------------------------------------ */

    /**
     * The dashboard's <b>Today's Bookings</b> card.
     *
     * <p><b>Counted in the database, not by loading the table.</b> The dashboard used
     * to derive every card from the booking rows it fetched for the recent-bookings
     * table; now that the response is trimmed to what the page actually renders, each
     * number is its own {@code count}/{@code sum} and the table's read stays a table's
     * read. A derived query rather than JPQL because there is nothing to join: it is
     * one column and a half-open range.
     *
     * <p><b>The range is half-open — {@code [from, to)} — and the service passes
     * midnight-to-midnight.</b> {@code between} would be closed at both ends and double
     * count a booking created at exactly midnight tomorrow, and comparing a date part in
     * SQL would be a dialect-dependent expression; two {@code LocalDateTime} bounds are
     * neither.
     */
    long countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(LocalDateTime from, LocalDateTime to);

    /**
     * The dashboard's <b>Revenue</b> card — the sum of {@code total_amount} over every
     * booking that is not cancelled.
     *
     * <p><b>Non-cancelled bookings, not successful payments.</b> That is the page's own
     * definition (its title says "Sum of confirmed (non-cancelled) bookings"), and a
     * money-first definition would silently renumber the card the first time a payment
     * row and its booking disagreed. Changing it is a decision, not a refactor.
     *
     * <p><b>A null booking status counts.</b> {@code booking_status} is not nullable in
     * practice, but the clause is written the way the old page-side filter behaved
     * ({@code b.status !== 'Cancelled'} is true for a missing value too) so this cannot
     * quietly drop a row the previous implementation counted.
     *
     * <p><b>{@code sum} answers {@code null} over an empty table, never 0</b> — the
     * service maps that to {@link java.math.BigDecimal#ZERO}. Spelling it
     * {@code coalesce(sum(...), 0)} here would push the literal's type into Hibernate's
     * inference for no gain.
     */
    @Query("""
            select sum(b.totalAmount) from Booking b
            where b.bookingStatus is null or upper(b.bookingStatus) <> 'CANCELLED'
            """)
    BigDecimal sumNonCancelledRevenue();

    /**
     * The dashboard's <b>Pending Payments</b> card — bookings whose
     * {@code payment_status} is {@code Pending}.
     *
     * <p><b>The booking's text, not the payment row's {@code PENDING}.</b> The card sits
     * beside the table's status badges, which render this exact column through
     * {@code AdminBookingResponse}; counting the payment table instead would let the card
     * and the rows disagree about the same booking. {@code IgnoreCase} because the column
     * is stored as the wizard wrote it.
     */
    long countByPaymentStatusIgnoreCase(String paymentStatus);
}
