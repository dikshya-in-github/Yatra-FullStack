package io.virinchi.yatra.Repository;

import io.virinchi.yatra.Model.Booking;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Integer> {

    //Customer ko booking history: GET /api/users/me/bookings (my-bookings.html).
    List<Booking> findByUserId(int userId);

    //Admin booking management + filter chips (Phase 9).
    List<Booking> findByBookingStatus(String bookingStatus);
    List<Booking> findByPaymentStatus(String paymentStatus);
    Page<Booking> findByBookingStatus(String bookingStatus, Pageable pageable);

    //flight_id FK bata — booked count / flight-wise bookings.
    long countByFlightId(int flightId);
    List<Booking> findByFlightId(int flightId);
}
