package io.virinchi.yatra.Repository;

import io.virinchi.yatra.Model.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SeatRepository extends JpaRepository<Seat, Integer> {

    //Seat map for one flight: GET /api/flights/{id}/seats.
    List<Seat> findByFlightId(int flightId);
    List<Seat> findByFlightIdAndStatus(int flightId, String status);

    //Booking attempt — seat already booked xa ki nai check garna.
    Optional<Seat> findByFlightIdAndSeatNumber(int flightId, String seatNumber);
    boolean existsByFlightIdAndSeatNumber(int flightId, String seatNumber);

    //Auto-assignment candidates: the free seats, lowest first. Ordered by id
    //because the seat map is GENERATED in seat-number order (FlightService), so
    //id order IS seat-number order — "1A" before "1B" before "2A".
    List<Seat> findByFlightIdAndStatusOrderByIdAsc(int flightId, String status);

    /**
     * Claims one seat, atomically — <b>the real double-booking guard</b>.
     *
     * <p>A single {@code UPDATE … WHERE status = 'AVAILABLE'} is a compare-and-set:
     * the database decides the winner, so of two concurrent booking transactions
     * exactly one gets {@code 1} back and the other gets {@code 0}. That is what
     * the {@code (flight_id, seat_number)} unique key <b>cannot</b> do — the key
     * stops two <i>rows</i> existing for one seat, not two bookings claiming the
     * same existing row (see {@code Service/BookingService} for the full note).
     *
     * <p>Callers must not mutate the entity they read the id from: this statement
     * changes the row without updating any managed copy, so a later {@code setStatus}
     * would write the stale value back. Claiming by id only is the intended use.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Seat s
               set s.status = :booked
             where s.id = :seatId
               and s.status = :available
            """)
    int claim(@Param("seatId") int seatId,
              @Param("booked") String booked,
              @Param("available") String available);

    /**
     * Frees one seat, atomically — the exact mirror of {@link #claim}, and it exists
     * for the same reason.
     *
     * <p>The obvious implementation (load the seat, check its status, call
     * {@code setStatus("AVAILABLE")}) is wrong here, and it took a failing test to
     * show it: {@link #claim} changes the row underneath the entity Hibernate has
     * already cached, so a later read in the <b>same transaction</b> still says
     * {@code AVAILABLE} — the status check fails, no release happens, and the seat is
     * leaked for good while the code looks correct. Acting on the row instead of on a
     * copy makes the release independent of what any persistence context believes.
     *
     * <p>Returns the number of rows changed: {@code 1} released it, {@code 0} means it
     * was not booked (already free, or another transaction is holding it), which makes
     * a repeated release harmless rather than a double-write.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            update Seat s
               set s.status = :available
             where s.id = :seatId
               and s.status = :booked
            """)
    int release(@Param("seatId") int seatId,
                @Param("available") String available,
                @Param("booked") String booked);

    //Available seats = seatCapacity − COUNT(BOOKED seats). Yo count kabhi store hudaina,
    //sadhai yahi bata compute hunxa (teacher-flagged rule).
    long countByFlightIdAndStatus(int flightId, String status);

    /** One flight's booked-seat count, shaped for a list response. */
    interface BookedCount {
        int getFlightId();
        long getTotal();
    }

    /**
     * Booked seats for many flights in <b>one</b> query.
     *
     * <p>Without this the flight list is N+1: every row needs its booked count,
     * so a 50-flight page would fire 50 extra {@code COUNT} queries. Grouped, it
     * is a single scan of the seat table.
     *
     * <p>Callers must skip the call for an empty id list — {@code in ()} is not
     * valid SQL.
     */
    @Query("""
            select s.flight.id as flightId, count(s) as total
            from Seat s
            where s.flight.id in :flightIds and s.status = :status
            group by s.flight.id
            """)
    List<BookedCount> countByFlightIdInAndStatus(@Param("flightIds") Collection<Integer> flightIds,
                                                 @Param("status") String status);
}
