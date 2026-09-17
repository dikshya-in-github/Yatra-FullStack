package io.virinchi.yatra.Repository;

import io.virinchi.yatra.Model.Passenger;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface PassengerRepository extends JpaRepository<Passenger, Integer> {

    //Ek booking ka sabai passengers — eticket ko passenger table yahi bata render hunxa.
    List<Passenger> findByBookingId(int bookingId);

    //Booking confirm garda kati passenger ho — totalAmount verify garna kaam lagxa.
    long countByBookingId(int bookingId);

    /**
     * The passengers of <b>several</b> bookings in one query — the admin list's
     * batch fetch (Phase 9).
     *
     * <p>The repository's bookings query cannot {@code @EntityGraph}-fetch the
     * {@code passengers} collection: a fetched collection on a paged query makes
     * Hibernate paginate in memory, loading every matching row to return one page.
     * So the service reads the page first, then calls this with exactly those ids
     * and groups the result — one extra query per page instead of one per row.
     */
    List<Passenger> findByBookingIdIn(Collection<Integer> bookingIds);
}
