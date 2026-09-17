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
}
