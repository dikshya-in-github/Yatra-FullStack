package io.virinchi.yatra;

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
import io.virinchi.yatra.Security.JwtUtil;
import io.virinchi.yatra.Service.BookingService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Roadmap Phase 6 — booking creation, the seat hold, and the public seat map.
 *
 * <p>DB-backed and {@code @Transactional} like {@code FlightApiTest}, so every row
 * is rolled back and the live database is never modified; each test builds its own
 * airline, airports and flights from a per-run tag.
 *
 * <h2>What is actually verified here</h2>
 *
 * <p>The roadmap checkpoint, literally: two booking requests for the same seat —
 * one 200, one 409 — plus the live seat map showing that seat as unavailable.
 *
 * <p>And the mechanism underneath it, because the checkpoint alone would pass even
 * if the guard were the pre-check read rather than the claim. The
 * {@code (flight_id, seat_number)} unique key that rule 2 names <b>cannot</b> stop
 * a second booking of an already-generated row (the seat map is pre-created, so
 * both transactions UPDATE the same row instead of inserting a duplicate one). The
 * guard is the conditional {@code UPDATE} in {@code SeatRepository.claim()}, and
 * {@link #theSeatClaimIsAtomicSoTheSecondClaimGetsNothing()} tests that directly:
 * it is the only assertion here that would still hold under real concurrency.
 *
 * <h2>One trap this suite has to work around, and it is not a test detail</h2>
 *
 * <p>The claim changes the row through a bulk JPQL {@code UPDATE}, which does not
 * touch the copy Hibernate already holds in the persistence context. Inside a
 * single transaction the test's own service call therefore leaves a stale
 * {@code AVAILABLE} entity cached, so a naive {@code seats.findByFlightId(...)}
 * assertion would read the stale object. Every test that inspects seat state calls
 * {@link #refresh()} (flush + clear) first, and where only a number is needed it
 * uses the count query, which the database answers. In production this is a
 * non-issue — each HTTP request gets its own persistence context — but it is the
 * reason this class asserts the way it does rather than a comment that says
 * "should be fine".
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BookingApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private AirlineRepository airlines;
    @Autowired private DestinationRepository destinations;
    @Autowired private FlightRepository flights;
    @Autowired private BookingRepository bookings;
    @Autowired private PassengerRepository passengers;
    @Autowired private SeatRepository seats;
    @Autowired private UserRepository users;
    @Autowired private BookingService bookingService;
    @Autowired private JwtUtil jwtUtil;

    /* ------------------------------------------------------------------ *
     *  the roadmap checkpoint                                             *
     * ------------------------------------------------------------------ */

    /**
     * The checkpoint, item by item: the first request wins, the second is refused
     * with 409 and the project's own code, exactly one booking exists, and the
     * seat map reports the seat as booked.
     *
     * <p>Two calls "back-to-back" is what the roadmap asks for; the concurrency
     * guarantee itself is proven separately (see the class doc), because a
     * sequential test cannot distinguish a real claim from a read-then-write that
     * merely looks right when nothing overlaps.
     */
    @Test
    void twoRequestsForTheSameSeatGiveOne200AndOne409() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");

        mockMvc.perform(book(bookingBody(flightNo, fixture, "16599.98", "1A", "1B")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        mockMvc.perform(book(bookingBody(flightNo, fixture, "16599.98", "1A")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SEAT_ALREADY_BOOKED"));

        int flightId = flightId(flightNo);
        refresh();

        assertThat(bookings.findByFlightId(flightId)).as("only the winner's booking exists").hasSize(1);
        assertThat(seats.countByFlightIdAndStatus(flightId, "BOOKED")).isEqualTo(2);

        // ...and the seat map the booking page would render agrees.
        mockMvc.perform(get("/api/flights/" + flightId + "/seats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats").value(8))
                .andExpect(jsonPath("$.bookedSeats").value(2))
                .andExpect(jsonPath("$.available").value(6))
                .andExpect(jsonPath("$.seatMap[0].number").value("1A"))
                .andExpect(jsonPath("$.seatMap[0].status").value("BOOKED"));
    }

    /**
     * The mechanism behind the checkpoint, tested where it actually lives.
     *
     * <p>Two claims on the same seat: the first changes a row, the second changes
     * nothing — which is the verdict the service turns into a 409. A read-then-write
     * guard (or a pre-check plus {@code setStatus}) would report success twice here,
     * which is the failure mode the whole phase exists to prevent.
     */
    @Test
    void theSeatClaimIsAtomicSoTheSecondClaimGetsNothing() {
        Fixture fixture = fixture();
        int flightId = createFlightId(fixture, 4, "8299.99");

        Seat seat = seats.findByFlightIdAndSeatNumber(flightId, "1A").orElseThrow();
        Seat other = seats.findByFlightIdAndSeatNumber(flightId, "1B").orElseThrow();

        assertThat(seats.claim(seat.getId(), "BOOKED", "AVAILABLE"))
                .as("first claim wins")
                .isEqualTo(1);
        assertThat(seats.claim(seat.getId(), "BOOKED", "AVAILABLE"))
                .as("second claim changes no row — this is the 409")
                .isZero();

        // A different seat is unaffected: the guard is per row, not per flight.
        assertThat(seats.claim(other.getId(), "BOOKED", "AVAILABLE")).isEqualTo(1);

        refresh();
        assertThat(seats.countByFlightIdAndStatus(flightId, "BOOKED")).isEqualTo(2);
    }

    /* ------------------------------------------------------------------ *
     *  the booking write                                                  *
     * ------------------------------------------------------------------ */

    /**
     * A guest (no token) books: the endpoint is public by design, the price comes
     * from the flight row rather than the request, and the seats are held at
     * booking time — before any money moves.
     */
    @Test
    void aGuestBookingIsPendingHoldsItsSeatsAndIgnoresTheClientPrice() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");

        // The client claims a total of 1 — deliberately wrong.
        mockMvc.perform(book(bookingBody(flightNo, fixture, "1", null, null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookingId").isNumber())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.amount").value(16599.98));

        int flightId = flightId(flightNo);
        List<Booking> saved = bookings.findByFlightId(flightId);
        assertThat(saved).hasSize(1);

        Booking booking = saved.get(0);
        assertThat(booking.getBookingStatus()).isEqualTo("PENDING");
        assertThat(booking.getPaymentStatus()).isEqualTo("Pending");
        assertThat(booking.getTotalAmount()).isEqualByComparingTo("16599.98");
        assertThat(booking.getProductAmount()).isEqualByComparingTo("16599.98");
        assertThat(booking.getContactPhone()).as("stored in the roster's 10-digit form").isEqualTo("9812345678");
        assertThat(booking.getContactName()).isEqualTo("Mr Hari Prasad Sharma");
        assertThat(booking.getUser()).as("a guest booking has no user — the column is nullable for this").isNull();

        List<Passenger> rows = passengers.findByBookingId(booking.getId());
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> assertThat(row.getSeatNumber()).isNotBlank());
        assertThat(rows.stream().map(Passenger::getSeatNumber).collect(Collectors.toSet()))
                .as("two passengers never share a seat")
                .hasSize(2);

        refresh();
        assertThat(seats.countByFlightIdAndStatus(flightId, "BOOKED")).as("held before payment").isEqualTo(2);
    }

    /** The wizard sends no seat numbers today, so this is the normal path, not an edge. */
    @Test
    void unnamedSeatsAreAssignedLowestFirst() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");

        mockMvc.perform(book(bookingBody(flightNo, fixture, "24899.97", null, null, null)))
                .andExpect(status().isOk());

        int flightId = flightId(flightNo);
        Booking booking = bookings.findByFlightId(flightId).get(0);

        assertThat(passengers.findByBookingId(booking.getId()).stream()
                .map(Passenger::getSeatNumber).toList())
                .containsExactly("1A", "1B", "1C");
    }

    /**
     * A full flight is refused whole: the seats claimed before the refusal must not
     * stay held, or a failed booking would leak seats out of a sold-out cabin.
     *
     * <p><b>Why this one test is not transactional.</b> A rollback only exists if the
     * request has a transaction boundary of its own. Inside the suite's shared test
     * transaction the earlier claims would still be visible afterwards — nothing rolls
     * back until the test ends — so the assertion would be measuring the harness rather
     * than the service. This method therefore suspends the test transaction, makes one
     * real request, and deletes the fixture rows it created (the newest rows in the
     * schema, removed in FK order). The other tests here are rolled back as usual; this
     * is the one that needs a real boundary.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void aBookingLargerThanTheFlightIs409AndRollsBackEverySeatItHeld() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 2, "8299.99");
        int flightId = flightId(flightNo);

        try {
            mockMvc.perform(book(bookingBody(flightNo, fixture, "24899.97", null, null, null)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").value("FLIGHT_SOLD_OUT"));

            assertThat(bookings.findByFlightId(flightId)).as("no half-written booking").isEmpty();
            assertThat(seats.countByFlightIdAndStatus(flightId, "BOOKED"))
                    .as("the seats claimed before the refusal were rolled back with it")
                    .isZero();
        } finally {
            deleteFixture(fixture, flightNo);
        }
    }

    /**
     * A seat the flight does not have is a 400, never a silent substitute. The seat
     * has a valid shape ({@code 9F}) but is off a 4-seat map — a malformed one would
     * be stopped by Bean Validation instead and never reach the service.
     */
    @Test
    void anUnknownSeatNumberIs400() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 4, "8299.99");

        mockMvc.perform(book(bookingBody(flightNo, fixture, "8299.99", "9F")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNKNOWN_SEAT"));
    }

    @Test
    void anUnknownFlightNumberIs404() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(book(bookingBody("ZZ 9999", fixture, "8299.99", new String[]{null})))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("FLIGHT_NOT_FOUND"));
    }

    /**
     * The staleness guard: {@code sessionStorage.yatra_selected_flight} can outlive
     * the search that produced it, so a body whose route disagrees with the flight
     * row is refused rather than booked — the alternative is a real booking on a
     * route the page is not showing.
     */
    @Test
    void aRouteThatDisagreesWithTheFlightIs400() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");

        String body = bookingBody(flightNo, "XXX", fixture.to().getCode(), "8299.99", (String) null);

        mockMvc.perform(book(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ROUTE_MISMATCH"));

        refresh();
        assertThat(bookings.findByFlightId(flightId(flightNo))).isEmpty();
    }

    /**
     * The unique key is {@code (flight_id, seat_number)}, so the same seat label on
     * two different flights is two different seats. Worth a test because it is the
     * one thing a global "is this seat taken?" check would get wrong.
     */
    @Test
    void theSameSeatNumberOnAnotherFlightIsFree() throws Exception {
        Fixture fixture = fixture();
        String first = createFlight(fixture, 4, "8299.99");
        String second = createFlight(fixture, 4, "8999.99");

        mockMvc.perform(book(bookingBody(first, fixture, "8299.99", "1A")))
                .andExpect(status().isOk());
        mockMvc.perform(book(bookingBody(second, fixture, "8999.99", "1A")))
                .andExpect(status().isOk());

        assertThat(bookings.findByFlightId(flightId(second))).hasSize(1);
    }

    /** A signed-in booking is attributed to its user; a guest's is not. */
    @Test
    void aSignedInBookingIsAttributedToItsUser() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 4, "8299.99");
        User user = seedUser();

        mockMvc.perform(book(bookingBody(flightNo, fixture, "8299.99", new String[]{null}))
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtUtil.generate(
                                user.getId(), user.getName(), user.getEmail(), "USER")))
                .andExpect(status().isOk());

        Booking booking = bookings.findByFlightId(flightId(flightNo)).get(0);
        assertThat(booking.getUser()).isNotNull();
        assertThat(booking.getUser().getId()).isEqualTo(user.getId());
    }

    /* ------------------------------------------------------------------ *
     *  the public seat map                                                *
     * ------------------------------------------------------------------ */

    /**
     * The endpoint is public — no token — and its contents are read from the seat
     * table rather than derived from a stored total, which is what makes it live.
     */
    @Test
    void theSeatMapIsPublicAndReportsTheLiveStatus() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 4, "8299.99");
        int flightId = flightId(flightNo);

        mockMvc.perform(book(bookingBody(flightNo, fixture, "8299.99", "1B")))
                .andExpect(status().isOk());

        refresh();

        mockMvc.perform(get("/api/flights/" + flightId + "/seats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flightId").value(flightId))
                .andExpect(jsonPath("$.no").value(flightNo))
                .andExpect(jsonPath("$.seats").value(4))
                .andExpect(jsonPath("$.bookedSeats").value(1))
                .andExpect(jsonPath("$.available").value(3))
                .andExpect(jsonPath("$.seatMap.length()").value(4))
                .andExpect(jsonPath("$.seatMap[0].number").value("1A"))
                .andExpect(jsonPath("$.seatMap[0].status").value("AVAILABLE"))
                .andExpect(jsonPath("$.seatMap[1].number").value("1B"))
                .andExpect(jsonPath("$.seatMap[1].status").value("BOOKED"));
    }

    @Test
    void theSeatMapOfAnUnknownFlightIs404() throws Exception {
        mockMvc.perform(get("/api/flights/99999999/seats"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("FLIGHT_NOT_FOUND"));
    }

    /* ------------------------------------------------------------------ *
     *  the R4 tie-in: a pending booking releases the seats it held        *
     * ------------------------------------------------------------------ */

    /**
     * A booking with no payment and no ticket is an abandoned draft, and deleting it
     * must give its seats back — otherwise the hold this phase introduces would leak
     * a seat out of the cabin permanently. This is the other half of the seat
     * mechanism working: the same {@code passenger.seat_number} the hold writes is
     * what {@code BookingService.releaseSeats} reads when the draft goes.
     */
    @Test
    void deletingAPendingBookingFreesTheSeatsItHeld() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 4, "8299.99");
        int flightId = flightId(flightNo);

        mockMvc.perform(book(bookingBody(flightNo, fixture, "16599.98", null, null)))
                .andExpect(status().isOk());

        Booking booking = bookings.findByFlightId(flightId).get(0);
        assertThat(seats.countByFlightIdAndStatus(flightId, "BOOKED")).isEqualTo(2);

        bookingService.deleteBooking(booking.getId());
        refresh();

        assertThat(bookings.findById(booking.getId())).isEmpty();
        assertThat(passengers.findByBookingId(booking.getId())).isEmpty();
        assertThat(seats.countByFlightIdAndStatus(flightId, "BOOKED"))
                .as("the seat is sellable again")
                .isZero();
        assertThat(seats.findByFlightIdAndStatus(flightId, "BOOKED")).isEmpty();
    }

    /* ------------------------------------------------------------------ *
     *  fixtures                                                          *
     * ------------------------------------------------------------------ */

    /**
     * Removes a non-transactional test's own rows — the only test here that commits,
     * so the only one that has to clean up after itself. FK order: passengers and
     * bookings, then the seat map, then the flight, then the route and the airline.
     */
    private void deleteFixture(Fixture fixture, String flightNo) {
        Flight flight = flights.findByFlightNo(flightNo).orElse(null);
        if (flight != null) {
            for (Booking booking : bookings.findByFlightId(flight.getId())) {
                passengers.deleteAll(passengers.findByBookingId(booking.getId()));
                bookings.delete(booking);
            }
            seats.deleteAll(seats.findByFlightId(flight.getId()));
            flights.delete(flight);
        }
        destinations.deleteById(fixture.from().getId());
        destinations.deleteById(fixture.to().getId());
        airlines.deleteById(fixture.airline().getId());
    }

    /**
     * Drops the persistence context so the next read comes from the database.
     *
     * <p>Necessary because {@code SeatRepository.claim} is a bulk {@code UPDATE}: the
     * row changes underneath an entity Hibernate has already cached in this
     * transaction, and a cached read would report the old status. See the class doc.
     */
    private void refresh() {
        entityManager.flush();
        entityManager.clear();
    }

    private record Fixture(Airline airline, Destination from, Destination to) {
    }

    private Fixture fixture() {
        String tag = tag();
        return new Fixture(seedAirline(tag), seedDestination(tag), seedDestination(tag));
    }

    private Airline seedAirline(String tag) {
        Airline airline = new Airline();
        airline.setName(tag + " Air");
        airline.setIata(uniqueIata());
        airline.setStatus("Active");
        return airlines.save(airline);
    }

    private Destination seedDestination(String tag) {
        Destination destination = new Destination();
        destination.setCity("City " + tag);
        destination.setCode(uniqueAirportCode());
        destination.setAirport(tag + " Airport");
        destination.setStatus("Active");
        return destinations.save(destination);
    }

    private User seedUser() {
        String tag = tag();
        User user = new User();
        user.setName("Customer " + tag);
        user.setEmail("customer-" + tag.toLowerCase() + "@example.com");
        user.setPhone(uniquePhone());
        user.setPassword("not-a-real-hash");
        user.setRole("USER");
        user.setStatus("Active");
        user.setRegisteredAt(LocalDateTime.now());
        return users.save(user);
    }

    /** Creates a flight through the real admin endpoint and returns its number. */
    private String createFlight(Fixture fixture, int capacity, String fare) {
        String no = flightNo();
        try {
            mockMvc.perform(post("/api/admin/flights")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(flightBody(no, fixture, capacity, fare)))
                    .andExpect(status().isOk());
        } catch (Exception ex) {
            throw new IllegalStateException("could not create the fixture flight " + no, ex);
        }
        return no;
    }

    private int createFlightId(Fixture fixture, int capacity, String fare) {
        return flightId(createFlight(fixture, capacity, fare));
    }

    private int flightId(String flightNo) {
        Flight flight = flights.findByFlightNo(flightNo).orElseThrow();
        return flight.getId();
    }

    private static String flightBody(String no, Fixture fixture, int seats, String fare) {
        return """
                {"no":"%s","airlineId":%d,"from":"%s","to":"%s",
                 "dep":"06:50","arr":"07:35","aircraft":"ATR 72","fare":%s,"seats":%d,"status":"Active"}
                """.formatted(no, fixture.airline().getId(), fixture.from().getCode(),
                fixture.to().getCode(), fare, seats);
    }

    /** A booking POST with no token — the guest path, which is the normal one. */
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder book(String body) {
        return post("/api/bookings").contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private static String bookingBody(String flightNo, Fixture fixture, String amount, String... seatNumbers) {
        return bookingBody(flightNo, fixture.from().getCode(), fixture.to().getCode(), amount, seatNumbers);
    }

    /**
     * The page's own payload: contact block, one passenger per entry, and the
     * selected-flight record {@code booking.js} writes from
     * {@code sessionStorage.yatra_selected_flight}. A {@code null} seat is omitted
     * entirely — that is what the wizard sends today (it has no seat picker), so it
     * must exercise the auto-assignment path rather than a null that never occurs.
     */
    private static String bookingBody(String flightNo, String from, String to,
                                      String amount, String... seatNumbers) {
        List<String> rows = new ArrayList<>();
        for (String seatNumber : seatNumbers) {
            String seatKey = seatNumber == null ? "" : ",\"seatNumber\":\"" + seatNumber + "\"";
            rows.add("""
                    {"title":"Mr","firstName":"Hari","middleName":"Prasad","lastName":"Sharma","nationality":"Nepal","type":"ADT"%s}"""
                    .formatted(seatKey));
        }

        return """
                {"contact":{"title":"Mr","firstName":"Hari","middleName":"Prasad","lastName":"Sharma",
                 "email":"hari@example.com","phone":"+977 9812345678","invoiceParty":"Self","panNo":"","isPassenger":true},
                 "passengers":[%s],
                 "flight":{"from":"%s","to":"%s","date":"%s","depart":"06:50","arrive":"07:35","flightNo":"%s",
                 "airline":"Air","flightClass":"E Class","refundable":true,"pricePerPassenger":8299.99,
                 "passengerCount":%d,"totalPrice":%s},
                 "amount":%s}"""
                .formatted(String.join(",", rows), from, to, LocalDate.now(), flightNo,
                        seatNumbers.length, amount, amount);
    }

    private String adminToken() {
        return jwtUtil.generate(1, "Dikshya Ghising", "admin@gmail.com", "ADMIN");
    }

    /** Isolates each test's rows from other tests and from any future seed data. */
    private static String tag() {
        return "T" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String flightNo() {
        for (int attempt = 0; attempt < 50; attempt++) {
            String digits = UUID.randomUUID().toString().replaceAll("[^0-9]", "");
            String candidate = "T7 " + digits.substring(0, 3 + (int) (Math.random() * 2));
            if (flights.findByFlightNo(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not find an unused flight number");
    }

    private String uniqueIata() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int attempt = 0; attempt < 500; attempt++) {
            java.util.Random random = new java.util.Random();
            String candidate = "" + alphabet.charAt(random.nextInt(alphabet.length()))
                    + alphabet.charAt(random.nextInt(alphabet.length()));
            if (airlines.findByIata(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not find an unused IATA code");
    }

    private String uniqueAirportCode() {
        for (int attempt = 0; attempt < 50; attempt++) {
            String candidate = UUID.randomUUID().toString().replaceAll("[^a-z]", "")
                    .substring(0, 3).toUpperCase();
            if (destinations.findByCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not find an unused airport code");
    }

    /** 10 digits, unused in the roster — {@code users.phone} is UNIQUE. */
    private String uniquePhone() {
        for (int attempt = 0; attempt < 50; attempt++) {
            String candidate = "98" + UUID.randomUUID().toString().replaceAll("\\D", "")
                    .substring(0, 8);
            if (users.findByPhone(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not find an unused phone number");
    }
}
