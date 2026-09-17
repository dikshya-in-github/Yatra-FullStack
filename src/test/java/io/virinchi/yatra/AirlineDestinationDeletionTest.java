package io.virinchi.yatra;

import io.virinchi.yatra.Exception.ConflictException;
import io.virinchi.yatra.Model.Airline;
import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Model.Flight;
import io.virinchi.yatra.Repository.AirlineRepository;
import io.virinchi.yatra.Repository.DestinationRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import io.virinchi.yatra.Service.AirlineService;
import io.virinchi.yatra.Service.DestinationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The same parent-delete pattern found on bookings, on the two other
 * parents the admin pages can delete: `Airline` (referenced by
 * `flight.airline_id`) and `Destination` (referenced by `flight.origin_id`
 * <i>and</i> `flight.destination_id`).
 *
 * <p>Both endpoints are exercised, because a guard that only checks the origin
 * half of a route passes every obvious test and still fails on the row a marker
 * happens to pick.
 *
 * <p>Everything is {@code @Transactional}, so the live database is untouched.
 */
@SpringBootTest
@Transactional
class AirlineDestinationDeletionTest {

    @Autowired private AirlineService airlineService;
    @Autowired private DestinationService destinationService;

    @Autowired private AirlineRepository airlines;
    @Autowired private DestinationRepository destinations;
    @Autowired private FlightRepository flights;

    @PersistenceContext private EntityManager em;

    /* ---------- airline ---------- */

    @Test
    void deletingAnAirlineThatOperatesFlightsIsRefusedWith409() {
        Airline airline = airline("N1", "Guarded Air");
        flight("N1", airline, destination("N1O"), destination("N1D"));
        int airlineId = airline.getId();

        assertThatThrownBy(() -> airlineService.deleteAirline(airlineId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("used by 1 flight(s)")
                .hasMessageContaining("disable it instead");

        assertThat(airlines.findById(airlineId)).as("the airline survives").isPresent();
        assertThat(flights.findByAirlineId(airlineId)).as("its flights survive").hasSize(1);
    }

    @Test
    void deletingAnAirlineWithNoFlightsRemovesIt() {
        Airline airline = airline("N2", "Idle Air");
        int airlineId = airline.getId();

        airlineService.deleteAirline(airlineId);
        em.flush();
        em.clear();

        assertThat(airlines.findById(airlineId)).isEmpty();
    }

    @Test
    void theDatabaseItselfRefusesToOrphanAFlightsAirline() {
        Airline airline = airline("N3", "Raw Delete Air");
        flight("N3", airline, destination("N3O"), destination("N3D"));
        int airlineId = airline.getId();
        em.flush();

        assertThatThrownBy(() -> {
            em.createQuery("delete from Airline a where a.id = :id")
                    .setParameter("id", airlineId)
                    .executeUpdate();
            em.flush();
        })
                .as("a raw FK violation here is the guarantee working, not a bug")
                .hasRootCauseInstanceOf(SQLIntegrityConstraintViolationException.class);
    }

    /* ---------- destination ---------- */

    @Test
    void deletingADestinationUsedAsARouteOriginIsRefusedWith409() {
        Airline airline = airline("N4", "Origin Air");
        flight("N4", airline, destination("N4O"), destination("N4D"));
        int originId = destinations.findByCode("ZN4O").orElseThrow().getId();

        assertThatThrownBy(() -> destinationService.deleteDestination(originId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("(ZN4O) is used by 1 flight(s)");

        assertThat(destinations.findById(originId)).isPresent();
    }

    @Test
    void deletingADestinationUsedAsARouteEndIsRefusedToo() {
        Airline airline = airline("N5", "End Air");
        flight("N5", airline, destination("N5O"), destination("N5D"));
        int endId = destinations.findByCode("ZN5D").orElseThrow().getId();

        assertThatThrownBy(() -> destinationService.deleteDestination(endId))
                .isInstanceOf(ConflictException.class);

        assertThat(destinations.findById(endId)).isPresent();
    }

    @Test
    void deletingADestinationNoRouteUsesRemovesIt() {
        Destination unused = destination("N6X");
        int destinationId = unused.getId();

        destinationService.deleteDestination(destinationId);
        em.flush();
        em.clear();

        assertThat(destinations.findById(destinationId)).isEmpty();
    }

    /* ---------- fixture ---------- */

    private Airline airline(String tag, String name) {
        Airline airline = new Airline();
        airline.setName(name);
        airline.setIata(tag);
        airline.setStatus("Active");
        return airlines.saveAndFlush(airline);
    }

    private Flight flight(String tag, Airline airline, Destination origin, Destination destination) {
        Flight flight = new Flight();
        flight.setFlightNo("ND " + tag);
        flight.setAirline(airline);
        flight.setOrigin(origin);
        flight.setDestination(destination);
        flight.setFlightDate(LocalDate.now().plusDays(15));
        flight.setDepartTime(LocalTime.of(10, 0));
        flight.setArriveTime(LocalTime.of(10, 40));
        flight.setAircraft("ATR 72");
        flight.setFare(new BigDecimal("6000.00"));
        flight.setSeatCapacity(50);
        flight.setStatus("Active");
        return flights.saveAndFlush(flight);
    }

    private Destination destination(String code) {
        Destination destination = new Destination();
        destination.setCity(code + " City");
        destination.setCode("Z" + code);
        destination.setAirport(code + " Airport");
        destination.setStatus("Active");
        return destinations.saveAndFlush(destination);
    }
}
