package io.virinchi.yatra.Repository;

import io.virinchi.yatra.Model.Seat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SeatRepository extends JpaRepository<Seat, Integer> {

    //Seat map for one flight: GET /api/flights/{id}/seats (Phase 6).
    List<Seat> findByFlightId(int flightId);
    List<Seat> findByFlightIdAndStatus(int flightId, String status);

    //Booking attempt — seat already booked xa ki nai check garna.
    Optional<Seat> findByFlightIdAndSeatNumber(int flightId, String seatNumber);
    boolean existsByFlightIdAndSeatNumber(int flightId, String seatNumber);

    //Available seats = seatCapacity − COUNT(BOOKED seats). Yo count kabhi store hudaina,
    //sadhai yahi bata compute hunxa (teacher-flagged rule, Phase 6).
    long countByFlightIdAndStatus(int flightId, String status);
}
