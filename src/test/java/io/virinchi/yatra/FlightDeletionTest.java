package io.virinchi.yatra;

import io.virinchi.yatra.Exception.ConflictException;
import io.virinchi.yatra.Model.Airline;
import io.virinchi.yatra.Model.Booking;
import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Model.Flight;
import io.virinchi.yatra.Model.Passenger;
import io.virinchi.yatra.Model.Seat;
import io.virinchi.yatra.Model.User;
import io.virinchi.yatra.Repository.AirlineRepository;
import io.virinchi.yatra.Repository.BookingRepository;
import io.virinchi.yatra.Repository.DestinationRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import io.virinchi.yatra.Repository.PassengerRepository;
import io.virinchi.yatra.Repository.SeatRepository;
import io.virinchi.yatra.Repository.UserRepository;
import io.virinchi.yatra.Service.FlightService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The flight half of the delete rule — the `admin-flights.html` Delete action.
 *
 * <p>The mock happily removes a flight locally because it has no concept of a
 * booking. The real API cannot: {@code booking.flight_id} is a real FK, and
 * deleting a sold flight would either fail at the database or, worse, orphan the
 * customer's booking. So the behaviour is split — refuse when sold, clean up
 * when not — and the probe showed the unsold case still needs explicit ordering
 * ({@code Seat} rows are children too, and deleting the flight with its seat map
 * already loaded trips Hibernate's pre-flush transient-reference check).
 */
@SpringBootTest
@Transactional
class FlightDeletionTest {

    @Autowired private FlightService flightService;

    @Autowired private AirlineRepository airlines;
    @Autowired private DestinationRepository destinations;
    @Autowired private FlightRepository flights;
    @Autowired private UserRepository users;
    @Autowired private BookingRepository bookings;
    @Autowired private PassengerRepository passengers;
    @Autowired private SeatRepository seats;

    @PersistenceContext private EntityManager em;

    @Test
    void deletingAFlightThatHasBookingsIsRefusedWith409() {
        Flight flight = seedFlight("F1", 3);
        seedBookingOn(flight, "F1");
        int flightId = flight.getId();

        assertThatThrownBy(() -> flightService.deleteFlight(flightId))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cannot be deleted");

        assertThat(flights.findById(flightId)).as("sold flight survives").isPresent();
        assertThat(seats.findByFlightId(flightId)).as("its seats survive too").hasSize(3);
        assertThat(passengers.findByBookingId(bookings.findByFlightId(flightId).get(0).getId()))
                .as("the customer's booking is untouched").hasSize(1);
    }

    @Test
    void deletingAnUnsoldFlightRemovesItAndItsSeatMap() {
        Flight flight = seedFlight("F2", 4);
        int flightId = flight.getId();

        flightService.deleteFlight(flightId);
        em.flush();
        em.clear();

        assertThat(flights.findById(flightId)).isEmpty();
        assertThat(seats.findByFlightId(flightId)).as("no orphaned seat rows").isEmpty();
    }

    /**
     * Backstop, same shape as the booking case: the FK has no delete cascade, so
     * even a bulk JPQL delete that skips the service is refused by the database.
     */
    @Test
    void theDatabaseItselfRefusesToOrphanABooking() {
        Flight flight = seedFlight("F3", 2);
        seedBookingOn(flight, "F3");
        int flightId = flight.getId();
        em.flush();

        assertThatThrownBy(() -> {
            em.createQuery("delete from Flight f where f.id = :id")
                    .setParameter("id", flightId)
                    .executeUpdate();
            em.flush();
        })
                .as("a raw FK violation here is the guarantee working, not a bug")
                .hasRootCauseInstanceOf(SQLIntegrityConstraintViolationException.class);
    }

    /* ---------- fixture ---------- */

    private Flight seedFlight(String tag, int seatRows) {
        Airline airline = new Airline();
        airline.setName("R4 Flight Air " + tag);
        airline.setIata("B" + tag);
        airline.setStatus("Active");
        airlines.saveAndFlush(airline);

        Flight flight = new Flight();
        flight.setFlightNo("DF " + tag);
        flight.setAirline(airline);
        flight.setOrigin(destination("X" + tag));
        flight.setDestination(destination("Y" + tag));
        flight.setFlightDate(LocalDate.now().plusDays(11));
        flight.setDepartTime(LocalTime.of(7, 0));
        flight.setArriveTime(LocalTime.of(7, 40));
        flight.setAircraft("ATR 72");
        flight.setFare(new BigDecimal("7000.00"));
        flight.setSeatCapacity(seatRows);
        flight.setStatus("Active");
        flights.saveAndFlush(flight);

        List<Seat> seatRowsToSave = new ArrayList<>();
        for (int i = 1; i <= seatRows; i++) {
            Seat seat = new Seat();
            seat.setFlight(flight);
            seat.setSeatNumber(i + "B");
            seat.setStatus("AVAILABLE");
            seatRowsToSave.add(seat);
        }
        seats.saveAllAndFlush(seatRowsToSave);
        return flight;
    }

    private Booking seedBookingOn(Flight flight, String tag) {
        User user = new User();
        user.setName("R4 Flight User " + tag);
        user.setEmail("r4f-" + tag.toLowerCase() + "@example.com");
        user.setPhone("9810" + tag);
        user.setRole("USER");
        user.setStatus("Active");
        user.setRegisteredAt(LocalDateTime.now());
        users.saveAndFlush(user);

        Booking booking = new Booking();
        booking.setUser(user);
        booking.setFlight(flight);
        booking.setContactName(user.getName());
        booking.setContactEmail(user.getEmail());
        booking.setContactPhone(user.getPhone());
        booking.setBookingStatus("CONFIRMED");
        booking.setPaymentStatus("Paid");
        booking.setFareClass("E Class");
        booking.setRefundable(false);
        booking.setTotalAmount(new BigDecimal("7000.00"));
        booking.setProductAmount(new BigDecimal("7000.00"));
        booking.setCreatedAt(LocalDateTime.now());
        bookings.saveAndFlush(booking);

        Passenger passenger = new Passenger();
        passenger.setBooking(booking);
        passenger.setTitle("Mr");
        passenger.setFirstName("R4");
        passenger.setLastName(tag);
        passenger.setPassengerType("ADT");
        passenger.setNationality("Nepali");
        passengers.saveAndFlush(passenger);
        return booking;
    }

    private Destination destination(String code) {
        Destination destination = new Destination();
        destination.setCity("R4 Flight City");
        destination.setCode("W" + code);
        destination.setAirport("R4 Flight Airport");
        destination.setStatus("Active");
        return destinations.saveAndFlush(destination);
    }
}
