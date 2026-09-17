package io.virinchi.yatra;

import io.virinchi.yatra.Exception.ConflictException;
import io.virinchi.yatra.Model.Airline;
import io.virinchi.yatra.Model.Booking;
import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Model.Flight;
import io.virinchi.yatra.Model.Passenger;
import io.virinchi.yatra.Model.Payment;
import io.virinchi.yatra.Model.Seat;
import io.virinchi.yatra.Model.Ticket;
import io.virinchi.yatra.Model.User;
import io.virinchi.yatra.Repository.AirlineRepository;
import io.virinchi.yatra.Repository.BookingRepository;
import io.virinchi.yatra.Repository.DestinationRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import io.virinchi.yatra.Repository.PassengerRepository;
import io.virinchi.yatra.Repository.PaymentRepository;
import io.virinchi.yatra.Repository.SeatRepository;
import io.virinchi.yatra.Repository.TicketRepository;
import io.virinchi.yatra.Repository.UserRepository;
import io.virinchi.yatra.Service.BookingService;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The booking-delete policy, run against the real TiDB schema.
 *
 * <p>The original assumption was ("{@code @OneToOne(mappedBy = …, cascade = ALL)}
 * probably carries the payment and ticket") and a preference (soft cancel over
 * hard delete). This test replaces the assumption with behaviour:
 *
 * <ul>
 *   <li>a paid/ticketed booking is <b>refused</b> (409), never half-deleted;</li>
 *   <li>cancelling keeps every row — it is a status flip, not a delete;</li>
 *   <li>a booking with no sale records <b>can</b> be deleted cleanly, children
 *       first, with no orphans and no leaked BOOKED seats;</li>
 *   <li>and the database refuses the destructive delete even when the service is
 *       bypassed — so the rule is not just service discipline.</li>
 * </ul>
 *
 * <p>{@code @Transactional} at class level: everything rolls back, so the live
 * cloud database is never modified by a test run.
 */
@SpringBootTest
@Transactional
class BookingDeletionTest {

    @Autowired private BookingService bookingService;

    @Autowired private AirlineRepository airlines;
    @Autowired private DestinationRepository destinations;
    @Autowired private FlightRepository flights;
    @Autowired private UserRepository users;
    @Autowired private BookingRepository bookings;
    @Autowired private PassengerRepository passengers;
    @Autowired private PaymentRepository payments;
    @Autowired private TicketRepository tickets;
    @Autowired private SeatRepository seats;

    @PersistenceContext private EntityManager em;

    /* ---------- soft cancel ---------- */

    @Test
    void cancellingABookingKeepsEveryRowAndOnlyFlipsTheStatus() {
        Booking booking = seedBooking("C1");
        addPayment(booking, "C1");
        addTicket(booking, "C1");
        int id = booking.getId();

        Booking cancelled = bookingService.cancelBooking(id);

        assertThat(cancelled.getBookingStatus()).isEqualTo("CANCELLED");
        assertThat(bookings.findById(id)).as("the booking row survives").isPresent();
        assertThat(passengers.findByBookingId(id)).as("passengers survive").hasSize(2);
        assertThat(payments.findByBookingId(id)).as("payment record survives").isPresent();
        assertThat(tickets.findByBookingId(id)).as("ticket record survives").isPresent();
    }

    @Test
    void cancellingIsIdempotentAndDoesNotReleaseSeats() {
        Booking booking = seedBooking("C2");
        int id = booking.getId();
        String seatNumber = passengers.findByBookingId(id).get(0).getSeatNumber();

        bookingService.cancelBooking(id);
        assertThatCode(() -> bookingService.cancelBooking(id)).doesNotThrowAnyException();

        Seat seat = seats.findByFlightIdAndSeatNumber(booking.getFlight().getId(), seatNumber)
                .orElseThrow();
        assertThat(seat.getStatus())
                .as("the frontend promises the admin: seats stay counted on the flight")
                .isEqualTo("BOOKED");
    }

    /* ---------- the allowed hard delete ---------- */

    @Test
    void deletingABookingWithNoSaleRecordsRemovesItsChildrenAndFreesItsSeats() {
        Booking booking = seedBooking("D1");
        int id = booking.getId();
        int flightId = booking.getFlight().getId();

        assertThat(passengers.findByBookingId(id)).hasSize(2);

        bookingService.deleteBooking(id);
        em.flush();
        em.clear();

        assertThat(bookings.findById(id)).as("booking gone").isEmpty();
        assertThat(passengers.findByBookingId(id)).as("no orphaned passengers").isEmpty();
        assertThat(seats.findByFlightId(flightId))
                .as("its seats are sellable again — availability = capacity − BOOKED")
                .allSatisfy(seat -> assertThat(seat.getStatus()).isEqualTo("AVAILABLE"));
    }

    /* ---------- the refused hard delete ---------- */

    @Test
    void deletingABookingWithAPaymentIsRefusedWith409() {
        Booking booking = seedBooking("D2");
        addPayment(booking, "D2");
        int id = booking.getId();

        assertThatThrownBy(() -> bookingService.deleteBooking(id))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("cannot be deleted");

        assertThat(bookings.findById(id)).as("nothing was deleted").isPresent();
        assertThat(payments.findByBookingId(id)).as("payment trail intact").isPresent();
        assertThat(passengers.findByBookingId(id)).as("children still there").hasSize(2);
    }

    @Test
    void deletingABookingWithATicketIsRefusedWith409() {
        Booking booking = seedBooking("D3");
        addTicket(booking, "D3");
        int id = booking.getId();

        assertThatThrownBy(() -> bookingService.deleteBooking(id))
                .isInstanceOf(ConflictException.class);
        assertThat(tickets.findByBookingId(id)).isPresent();
    }

    /**
     * The backstop: bypass the service entirely with a bulk JPQL delete and the
     * database still refuses, because {@code payment.booking_id} /
     * {@code ticket.booking_id} carry no DB-level delete cascade. Service
     * discipline can be forgotten by a later edit; the constraint cannot.
     */
    @Test
    void theDatabaseItselfRefusesToOrphanAPaymentRow() {
        Booking booking = seedBooking("D4");
        addPayment(booking, "D4");
        int id = booking.getId();
        em.flush();

        assertThatThrownBy(() -> {
            em.createQuery("delete from Booking b where b.id = :id")
                    .setParameter("id", id)
                    .executeUpdate();
            em.flush();
        })
                .as("a raw FK violation here is the guarantee working, not a bug")
                .hasRootCauseInstanceOf(SQLIntegrityConstraintViolationException.class);
    }

    /* ---------- fixture ---------- */

    /** A pending booking with 2 passengers, each holding a BOOKED seat. */
    private Booking seedBooking(String tag) {
        Airline airline = new Airline();
        airline.setName("R4 Air " + tag);
        airline.setIata("A" + tag);
        airline.setStatus("Active");
        airlines.saveAndFlush(airline);

        Flight flight = new Flight();
        flight.setFlightNo("DT " + tag);
        flight.setAirline(airline);
        flight.setOrigin(destination("O" + tag));
        flight.setDestination(destination("D" + tag));
        flight.setFlightDate(LocalDate.now().plusDays(10));
        flight.setDepartTime(LocalTime.of(9, 0));
        flight.setArriveTime(LocalTime.of(9, 45));
        flight.setAircraft("ATR 72");
        flight.setFare(new BigDecimal("8299.99"));
        flight.setSeatCapacity(70);
        flight.setStatus("Active");
        flights.saveAndFlush(flight);

        User user = new User();
        user.setName("R4 User " + tag);
        user.setEmail("r4-" + tag.toLowerCase() + "@example.com");
        user.setPhone("9800" + tag);
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
        booking.setBookingStatus("PENDING");
        booking.setPaymentStatus("Pending");
        booking.setFareClass("E Class");
        booking.setRefundable(false);
        booking.setTotalAmount(new BigDecimal("16599.98"));
        booking.setProductAmount(new BigDecimal("16599.98"));
        booking.setCreatedAt(LocalDateTime.now());
        bookings.saveAndFlush(booking);

        for (int i = 1; i <= 2; i++) {
            String seatNumber = i + "A";

            Passenger passenger = new Passenger();
            passenger.setBooking(booking);
            passenger.setTitle("Mr");
            passenger.setFirstName("R4");
            passenger.setLastName(tag + "-" + i);
            passenger.setPassengerType("ADT");
            passenger.setNationality("Nepali");
            passenger.setSeatNumber(seatNumber);
            passengers.save(passenger);

            Seat seat = new Seat();
            seat.setFlight(flight);
            seat.setSeatNumber(seatNumber);
            seat.setStatus("BOOKED");
            seats.save(seat);
        }
        passengers.flush();
        seats.flush();
        return booking;
    }

    private void addPayment(Booking booking, String tag) {
        Payment payment = new Payment();
        payment.setBooking(booking);
        payment.setMethod("eSewa");
        payment.setTxnId("R4TXN" + tag);
        payment.setAmount(booking.getTotalAmount());
        payment.setStatus("SUCCESS");
        payment.setPaidAt(LocalDateTime.now());
        payment.setCreatedAt(LocalDateTime.now());
        payments.saveAndFlush(payment);

        booking.setPayment(payment);   // both sides, as the real payment flow will
        bookings.saveAndFlush(booking);
    }

    private void addTicket(Booking booking, String tag) {
        Ticket ticket = new Ticket();
        ticket.setBooking(booking);
        ticket.setPnr("R4PNR" + tag);
        ticket.setTicketNo("R4NO" + tag);
        ticket.setStatus("ISSUED");
        ticket.setIssuedAt(LocalDateTime.now());
        tickets.saveAndFlush(ticket);

        booking.setTicket(ticket);
        bookings.saveAndFlush(booking);
    }

    private Destination destination(String code) {
        Destination destination = new Destination();
        destination.setCity("R4 City");
        destination.setCode("Z" + code);
        destination.setAirport("R4 Airport");
        destination.setStatus("Active");
        return destinations.saveAndFlush(destination);
    }
}
