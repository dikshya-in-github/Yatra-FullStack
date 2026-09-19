package io.virinchi.yatra.Repository;

import io.virinchi.yatra.Model.Payment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface PaymentRepository extends JpaRepository<Payment, Integer> {

    //Ek booking ko ek payment (1:1 — booking_id unique).
    Optional<Payment> findByBookingId(int bookingId);

    //Gateway reference unique — duplicate txn save hunna.
    Optional<Payment> findByTxnId(String txnId);

    /**
     * The payments of <b>several</b> bookings in one query — the hold sweep's batch
     * fetch, the same device {@code PassengerRepository.findByBookingIdIn} provides for
     * the admin list.
     *
     * <p>{@code payment.booking_id} is UNIQUE, so at most one row comes back per id and
     * the caller can key the result by booking id without worrying about collisions.
     * The sweep asks which of its candidates have a payment <i>at all</i> (the row must
     * survive — R4), and doing that one candidate at a time is a round trip per stale
     * hold against a remote database.
     */
    List<Payment> findByBookingIdIn(Collection<Integer> bookingIds);

    //Admin payments monitoring: GET /api/admin/payments.
    List<Payment> findByStatus(String status);
    List<Payment> findByMethod(String method);

    /**
     * The ledger's money, summed in the database — the payments page's two NPR tiles.
     *
     * <p><b>Why an aggregate rather than a sum over the rows.</b> The page holds ONE
     * page of the ledger, so summing the rows it has would show a "Collected" figure
     * that shrinks as the admin walks to page 2 — a number that is not the database's
     * answer to anything. {@code DashboardService} made the same call for its Revenue
     * card ("a dashboard that counts a table by loading it is doing the database's job
     * in Java"), and this is that rule applied to the one page whose tiles are money.
     *
     * <p>The status is matched case-insensitively like every other payment status in
     * this API: the row stores {@code SUCCESS}/{@code REFUNDED}, and the caller may
     * quote either that or the page's {@code Paid} — resolved by the service before it
     * gets here. {@code coalesce} answers an empty table with {@code 0} rather than SQL's
     * {@code null}, so the DTO never serialises a null amount.
     */
    @Query("select coalesce(sum(p.amount), 0) from Payment p where upper(p.status) = :status")
    BigDecimal sumAmountByStatus(@Param("status") String status);

    /** The Pending tile: how many transactions are still awaiting the gateway. */
    long countByStatusIgnoreCase(String status);

    /**
     * The admin payments ledger — Roadmap Phase 10.
     *
     * <p><b>One nullable-parameter query, not a derived method per combination</b>,
     * the same shape {@code BookingRepository.searchAll} uses: every clause is
     * {@code :param is null or ...} and the service passes {@code null} for "no
     * filter", with {@code ALL} folded to {@code null} before it gets here.
     *
     * <p><b>The search spans four entities, which is why it needs a query of its
     * own.</b> {@code admin-payments.js}'s search box promises "Search txn ID,
     * booking ID, PNR, customer…" — the transaction id lives on {@code payment},
     * the PNR on {@code ticket}, the flight number on {@code flight} and the
     * customer on the booking's contact block. Both joins are {@code left} so a
     * payment whose booking somehow lost its ticket (or has no flight) still lists.
     *
     * <p><b>A numeric search also matches the booking id</b> ({@code :idSearch},
     * {@code null} unless the term is numeric), OR'd with the text matches rather
     * than AND'd — the bug a Phase 9 test caught in the booking query, not repeated
     * here: the service sets both parameters for a numeric term, so an AND would
     * demand the row match a name <i>and</i> an id.
     *
     * <p><b>{@code method} and {@code status} filter the payment row's own
     * columns</b> — the ledger's data, not a joined table's. {@code status} is
     * matched case-insensitively against the stored {@code SUCCESS/PENDING/FAILED/
     * REFUNDED} because the page's filter dropdown speaks {@code Paid/Pending/
     * Refunded/Failed}; the service translates between the two vocabularies before
     * it gets here, exactly as it does on the booking status endpoint.
     *
     * <p><b>{@code @EntityGraph} covers the to-one associations only.</b> The row
     * the response renders needs the booking, its flight with the airline and both
     * airport codes, its ticket and its own payment (the inverse side is read by
     * the shared mapper, so it is fetched rather than lazily re-selected). The
     * {@code passengers} collection is deliberately absent for the reason Phase 9
     * documented: a fetched collection alongside a paged query makes Hibernate
     * paginate in memory.
     */
    @EntityGraph(attributePaths = {"booking", "booking.payment", "booking.ticket",
            "booking.flight", "booking.flight.airline", "booking.flight.origin",
            "booking.flight.destination"})
    @Query("""
            select p from Payment p
            left join p.booking b
            left join b.ticket t
            left join b.flight f
            where ((:search is null and :idSearch is null)
                   or lower(p.txnId) like :search
                   or lower(b.contactName) like :search
                   or lower(b.contactEmail) like :search
                   or lower(b.contactPhone) like :search
                   or lower(f.flightNo) like :search
                   or lower(t.pnr) like :search
                   or lower(t.ticketNo) like :search
                   or (:idSearch is not null and b.id = :idSearch))
              and (:method is null or p.method = :method)
              and (:status is null or upper(p.status) = :status)
            """)
    List<Payment> searchAll(@Param("search") String search,
                            @Param("idSearch") Integer idSearch,
                            @Param("method") String method,
                            @Param("status") String status,
                            Sort sort);

    /** The same filters, one page at a time. */
    @EntityGraph(attributePaths = {"booking", "booking.payment", "booking.ticket",
            "booking.flight", "booking.flight.airline", "booking.flight.origin",
            "booking.flight.destination"})
    @Query("""
            select p from Payment p
            left join p.booking b
            left join b.ticket t
            left join b.flight f
            where ((:search is null and :idSearch is null)
                   or lower(p.txnId) like :search
                   or lower(b.contactName) like :search
                   or lower(b.contactEmail) like :search
                   or lower(b.contactPhone) like :search
                   or lower(f.flightNo) like :search
                   or lower(t.pnr) like :search
                   or lower(t.ticketNo) like :search
                   or (:idSearch is not null and b.id = :idSearch))
              and (:method is null or p.method = :method)
              and (:status is null or upper(p.status) = :status)
            """)
    Page<Payment> searchPage(@Param("search") String search,
                             @Param("idSearch") Integer idSearch,
                             @Param("method") String method,
                             @Param("status") String status,
                             Pageable pageable);
}
