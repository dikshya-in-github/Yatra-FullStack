package io.virinchi.yatra.Repository;

import io.virinchi.yatra.Model.Flight;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface FlightRepository extends JpaRepository<Flight, Integer> {

    //Display flight number ("U4 951") unique hunuparxa.
    boolean existsByFlightNo(String flightNo);
    Optional<Flight> findByFlightNo(String flightNo);

    //Route + date — the wizard's search: GET /api/flights/search?origin=&destination=&date=
    //(origin/destination ko code bata traversal hunxa: OriginCode = flight.origin.code).
    List<Flight> findByOriginCodeAndDestinationCodeAndFlightDate(String originCode, String destinationCode, LocalDate flightDate);

    //Admin flight list + airline-wise filter (Phase 5: airline/route/date/status).
    List<Flight> findByAirlineId(int airlineId);

    //R11 guard: "yo row kahin use hudai xa?" — airline/destination delete garna aghi.
    //Count query (not findByAirlineId(...).size()) so the guard does not load rows it
    //is only going to count.
    long countByAirlineId(int airlineId);
    long countByOriginIdOrDestinationId(int originId, int destinationId);
    List<Flight> findByFlightDate(LocalDate flightDate);
    List<Flight> findByStatus(String status);
    Page<Flight> findByStatus(String status, Pageable pageable);
}
