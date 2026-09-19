package io.virinchi.yatra;

import io.virinchi.yatra.Dto.HoldExpiryResponse;
import io.virinchi.yatra.Model.Airline;
import io.virinchi.yatra.Model.Booking;
import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Model.Payment;
import io.virinchi.yatra.Repository.AirlineRepository;
import io.virinchi.yatra.Repository.BookingRepository;
import io.virinchi.yatra.Repository.DestinationRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import io.virinchi.yatra.Repository.PassengerRepository;
import io.virinchi.yatra.Repository.PaymentRepository;
import io.virinchi.yatra.Repository.SeatRepository;
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
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The abandoned-hold sweep: a {@code PENDING} booking older than its window loses its
 * seats, and the flight can sell them again.
 *
 * <h2>The hole this closes</h2>
 *
 * <p>Phase 6 holds a seat the moment the booking is created, before the gateway is
 * called, and the wizard's timer promises the customer fifteen minutes. Nothing
 * enforced the other end of that promise: {@code SeatRepository.release} had exactly
 * one caller ({@code BookingService.deleteBooking}, an admin action), so a customer who
 * closed the tab at the payment page left a row holding its seats with no path back
 * short of an admin deleting the draft — and availability, which is
 * {@code seatCapacity − COUNT(BOOKED seats)}, kept reporting those seats as sold
 * <b>forever</b>. The Postman walk creates exactly such rows (the spare booking used for
 * {@code PAYMENT_NOT_INITIATED}, and the declined-payment case), so this was never
 * theoretical.
 *
 * <p><b>Every test here therefore asserts two things</b>, because either alone can pass
 * while the bug is present: what happened to the <i>row</i> (deleted, or cancelled and
 * kept), and that the seat is genuinely sellable again — read through the same seat-map
 * endpoint the booking page uses, which is where rule 1's computed number becomes
 * visible.
 *
 * <h2>Why the assertions are scoped to this test's own rows</h2>
 *
 * <p>The suite runs {@code @Transactional} against the <b>live</b> schema, so this test
 * rolls its own writes back — but it cannot know what {@code PENDING} rows already sit
 * in that database (the walk leaves its own behind on purpose). A sweep has no
 * per-fixture scope: it operates on every stale hold there is. So the absolute counts a
 * sweep returns are asserted only where they are genuinely invariant
 * ({@code expired == deleted + cancelled}, and {@code expired == 0} on a second run),
 * and every claim about a specific booking is asserted against <b>that booking's</b> own
 * state after {@link #refresh()}. An absolute
 * {@code expired == 1} here would be a test that passes on a clean database and fails on
 * the one the previous run left behind — the same run-scoping lesson the Postman
 * collection had to learn.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class HoldExpiryTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private AirlineRepository airlines;
    @Autowired private DestinationRepository destinations;
    @Autowired private FlightRepository flights;
    @Autowired private BookingRepository bookings;
    @Autowired private PassengerRepository passengers;
    @Autowired private PaymentRepository payments;
    @Autowired private SeatRepository seats;
    @Autowired private BookingService bookingService;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private ObjectMapper objectMapper;

    /* ------------------------------------------------------------------ *
     *  the hole: an abandoned hold gives its seats back                    *
     * ------------------------------------------------------------------ */

    /**
     * The core case: a hold older than the window with no payment and no ticket is an
     * abandoned draft, so it goes — and its seats are sellable again, which is the part
     * the whole task exists for.
     */
    @Test
    void aStaleHoldWithNoPaymentIsDeletedAndItsSeatsAreSellableAgain() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 6, "8299.99");
        int flightId = flightId(flightNo);

        int bookingId = book(fixture, flightNo, 2);
        assertThat(bookedSeats(flightId)).as("held before the gateway is ever called").isEqualTo(2);

        backdate(bookingId, 20);

        HoldExpiryResponse report = bookingService.expireHolds(Duration.ofMinutes(15));
        refresh();

        assertThat(report.windowMinutes()).isEqualTo(15);
        assertThat(report.cutoff()).isBefore(LocalDateTime.now());
        assertThat(report.expired())
                .as("the report's own invariant, so two numbers about one run cannot drift")
                .isEqualTo(report.deleted() + report.cancelled());
        assertThat(report.expired()).as("this booking is among them").isGreaterThanOrEqualTo(1);
        assertThat(report.seatsReleased()).as("two seats came back").isGreaterThanOrEqualTo(2);

        assertThat(bookings.findById(bookingId))
                .as("no sale records: the same delete rule deleteBooking applies")
                .isEmpty();
        assertThat(passengers.findByBookingId(bookingId)).as("its passengers went with it").isEmpty();
        assertThat(bookedSeats(flightId)).as("the seats are out of the cabin's count").isZero();

        //The booking page's own view of availability — rule 1's computed number.
        mockMvc.perform(get("/api/flights/" + flightId + "/seats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookedSeats").value(0))
                .andExpect(jsonPath("$.available").value(6))
                .andExpect(jsonPath("$.seatMap[0].status").value("AVAILABLE"));
    }

    /**
     * The same hold, but the customer got as far as a declined gateway answer: the
     * {@code payment} row exists, so R4 forbids deleting the booking — the failed
     * transaction is the record of what the customer tried to do. The <b>hold</b> still
     * has to end, so the row is cancelled instead and the seats come back.
     *
     * <p>This is the branch that makes the sweep more than a delete-the-drafts loop, and
     * it is the one a naive implementation gets wrong: refusing to touch anything with a
     * payment row would leave the declined-payment case leaking seats for good.
     */
    @Test
    void aStaleHoldWithAFailedPaymentIsCancelledAndItsSeatsAreSellableAgain() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 6, "8299.99");
        int flightId = flightId(flightNo);

        int bookingId = book(fixture, flightNo, 1);
        decline(bookingId);
        refresh();

        Booking declined = require(bookingId);
        assertThat(declined.getBookingStatus()).as("a declined payment leaves the booking PENDING").isEqualTo("PENDING");
        assertThat(declined.getPaymentStatus()).isEqualTo("Failed");
        assertThat(bookedSeats(flightId)).as("the seat stays held, as Phase 10 documented").isEqualTo(1);

        backdate(bookingId, 20);
        bookingService.expireHolds(Duration.ofMinutes(15));
        refresh();

        Booking kept = require(bookingId);
        assertThat(kept.getBookingStatus()).as("kept and cancelled — the payment row is a record").isEqualTo("CANCELLED");
        assertThat(kept.getPaymentStatus())
                .as("the refund flow's business, not a status flip's to claim")
                .isEqualTo("Failed");

        Optional<Payment> payment = payments.findByBookingId(bookingId);
        assertThat(payment).as("the failed transaction survives as the audit trail").isPresent();
        assertThat(payment.orElseThrow().getStatus()).isEqualTo("FAILED");
        assertThat(passengers.findByBookingId(bookingId)).as("a cancelled booking keeps its passengers").hasSize(1);
        assertThat(bookedSeats(flightId)).as("only the hold ended; the sale record did not").isZero();
    }

    /**
     * The other half of that branch: the customer opened a transaction and walked away, so
     * the payment row is still {@code PENDING} when the window closes.
     *
     * <p>The booking is cancelled exactly as the declined case is — and the open
     * transaction is <b>closed with it</b>, because a cancelled booking can never settle:
     * {@code verify} answers 409 {@code BOOKING_CANCELLED}, the refund gap the sweep's own
     * note records. Left open, the row is permanently "awaiting an answer", and the
     * Payments ledger renders precisely that: its Pending filter reads this row while the
     * Status column prints the booking's {@code paymentStatus}, so the orphan appears as a
     * live Pending transaction beside a booking that says Failed.
     */
    @Test
    void anAbandonedAttemptsOpenTransactionIsClosedWithItsBooking() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 6, "8299.99");
        int flightId = flightId(flightNo);

        int bookingId = book(fixture, flightNo, 1);
        initiate(bookingId);
        refresh();

        assertThat(payments.findByBookingId(bookingId).orElseThrow().getStatus())
                .as("initiated at the gateway and never answered").isEqualTo("PENDING");
        assertThat(bookedSeats(flightId)).as("its seat is held while the attempt is open").isEqualTo(1);

        backdate(bookingId, 20);
        bookingService.expireHolds(Duration.ofMinutes(15));
        refresh();

        assertThat(require(bookingId).getBookingStatus())
                .as("kept and cancelled — a transaction is on record").isEqualTo("CANCELLED");
        assertThat(payments.findByBookingId(bookingId).orElseThrow().getStatus())
                .as("and the attempt is closed rather than left waiting for an answer that cannot come")
                .isEqualTo("FAILED");
        assertThat(bookedSeats(flightId)).as("the seat is back in the cabin").isZero();
    }

    /* ------------------------------------------------------------------ *
     *  what must NOT be touched                                            *
     * ------------------------------------------------------------------ */

    /** A hold still inside its window is the normal case, and it is untouchable. */
    @Test
    void aHoldInsideItsWindowIsLeftAlone() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 6, "8299.99");
        int flightId = flightId(flightNo);

        int bookingId = book(fixture, flightNo, 1);

        bookingService.expireHolds(Duration.ofMinutes(15));
        refresh();

        assertThat(require(bookingId).getBookingStatus()).as("still the customer's hold").isEqualTo("PENDING");
        assertThat(bookedSeats(flightId)).as("still counted against the flight").isEqualTo(1);
    }

    /**
     * A {@code PENDING} booking whose payment <i>succeeded</i> is a contradiction the
     * sweep must refuse to act on: a successful payment means the gateway was paid, so
     * the row is a sale record even if its status text has not caught up (normally
     * {@code PaymentService.verify} confirms the booking, which is why this state needs
     * constructing by hand).
     *
     * <p>With {@code minutes=0} — the strictest window there is — the booking still has
     * to survive, seats included. That is the assertion that proves the skip branch is
     * reachable and not just a comment.
     */
    @Test
    void aHoldWhosePaymentSucceededIsNeverSwept() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 6, "8299.99");
        int flightId = flightId(flightNo);

        int bookingId = book(fixture, flightNo, 1);
        initiate(bookingId);

        //Force the contradictory state: the gateway was paid, the booking text says PENDING.
        Payment payment = payments.findByBookingId(bookingId).orElseThrow();
        payment.setStatus("SUCCESS");
        payments.save(payment);
        refresh();

        bookingService.expireHolds(Duration.ZERO);
        refresh();

        //No assertion on the report's own counts: a sweep has no per-fixture scope, so
        //`expired` also covers whatever stale holds the live database already had. The
        //proof is this booking's own state — if the skip branch did not exist, the row
        //would now be CANCELLED and its seat free.
        assertThat(require(bookingId).getBookingStatus()).isEqualTo("PENDING");
        assertThat(bookedSeats(flightId)).as("its seat is not swept out from under it").isEqualTo(1);
    }

    /**
     * Seeded rows belong to {@code POST /api/admin/reset} and never to the sweep (R14:
     * the marker is the only decidable test for "this is demo data"). The seeder does not
     * create {@code PENDING} bookings today — this pins the exclusion so a future seeder
     * change cannot make the demo dataset shrink on a timer.
     */
    @Test
    void seededHoldsAreLeftForTheReset() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 6, "8299.99");
        int flightId = flightId(flightNo);

        int bookingId = book(fixture, flightNo, 1);
        Booking booking = require(bookingId);
        booking.setSeeded(true);
        bookings.save(booking);
        backdate(bookingId, 20);

        bookingService.expireHolds(Duration.ZERO);
        refresh();

        assertThat(require(bookingId)).as("the seeder's row, the seeder's operation").isNotNull();
        assertThat(require(bookingId).getBookingStatus()).isEqualTo("PENDING");
        assertThat(bookedSeats(flightId)).isEqualTo(1);
    }

    /**
     * Idempotence, and the settlement check a caller actually uses: after one sweep has
     * run with the widest window, a second finds nothing. Releasing is a conditional
     * {@code UPDATE} whose row count decides (R13), so a repeated release is a no-op
     * rather than a double write.
     */
    @Test
    void aSecondSweepFindsNothingLeftToDo() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 6, "8299.99");

        book(fixture, flightNo, 1);
        backdate(book(fixture, flightNo, 1), 20);

        bookingService.expireHolds(Duration.ZERO);

        HoldExpiryResponse second = bookingService.expireHolds(Duration.ZERO);
        assertThat(second.expired()).as("every stale hold was already ended").isZero();
        assertThat(second.seatsReleased()).isZero();
    }

    /* ------------------------------------------------------------------ *
     *  the endpoint                                                        *
     * ------------------------------------------------------------------ */

    /** Admin-only, like every other admin route — 401 without a token, 403 for a USER. */
    @Test
    void theEndpointIsAdminOnly() throws Exception {
        mockMvc.perform(post("/api/admin/bookings/expire-holds"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("NOT_AUTHENTICATED"));

        String userToken = jwtUtil.generate(2, "Anju Karki", "anju.karki@example.com", "USER");
        mockMvc.perform(post("/api/admin/bookings/expire-holds")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    /**
     * The manual trigger, and the override that makes the phase demonstrable: omitted,
     * {@code minutes} is the configured 15; {@code ?minutes=0} ends every open hold, which
     * is the only way to prove the rule in a demo or a test run without waiting a quarter
     * of an hour.
     */
    @Test
    void theEndpointUsesTheConfiguredWindowUnlessMinutesIsGiven() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 6, "8299.99");
        int flightId = flightId(flightNo);

        int fresh = book(fixture, flightNo, 1);

        //No override: the configured window leaves a booking made seconds ago alone.
        mockMvc.perform(post("/api/admin/bookings/expire-holds")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowMinutes").value(15))
                .andExpect(jsonPath("$.cutoff").exists())
                .andExpect(jsonPath("$.expired").isNumber())
                .andExpect(jsonPath("$.seatsReleased").isNumber());

        refresh();
        assertThat(require(fresh).getBookingStatus()).isEqualTo("PENDING");

        //With the override, the hold that is seconds old is already stale.
        mockMvc.perform(post("/api/admin/bookings/expire-holds").param("minutes", "0")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.windowMinutes").value(0))
                .andExpect(jsonPath("$.expired").value(greaterThanOrEqualTo(1)));

        refresh();
        assertThat(bookings.findById(fresh)).as("the hold is over").isEmpty();
        assertThat(bookedSeats(flightId)).isZero();
    }

    @Test
    void aNegativeWindowIs400() throws Exception {
        mockMvc.perform(post("/api/admin/bookings/expire-holds").param("minutes", "-1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("INVALID_HOLD_WINDOW"));
    }

    /* ------------------------------------------------------------------ *
     *  request helpers                                                     *
     * ------------------------------------------------------------------ */

    private void backdate(int bookingId, int minutes) {
        Booking booking = require(bookingId);
        booking.setCreatedAt(LocalDateTime.now().minusMinutes(minutes));
        bookings.save(booking);
        refresh();
    }

    /** Opens a transaction and leaves it open — the state an abandoned checkout is in. */
    private void initiate(int bookingId) throws Exception {
        mockMvc.perform(post("/api/payments/initiate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookingId":%d,"method":"esewa","amount":null,"promoCode":""}
                                """.formatted(bookingId)))
                .andExpect(status().isOk());
        refresh();
    }

    /** The whole gateway walk ending in a failure — booking stays PENDING, payment FAILED. */
    private void decline(int bookingId) throws Exception {
        initiate(bookingId);
        mockMvc.perform(post("/api/payments/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"txnId":"%s","bookingId":%d,"method":"esewa","amount":1,"productAmount":1,
                                 "customerName":"Ignored","outcome":"FAILED"}
                                """.formatted(txnId(), bookingId)))
                .andExpect(status().isOk());
        refresh();
    }

    private static String txnId() {
        return "9A" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
    }

    /** Flushes and drops the persistence context, so the next read comes from the database. */
    private void refresh() {
        entityManager.flush();
        entityManager.clear();
    }

    private Booking require(int bookingId) {
        return bookings.findById(bookingId).orElseThrow();
    }

    private long bookedSeats(int flightId) {
        return seats.countByFlightIdAndStatus(flightId, "BOOKED");
    }

    private int book(Fixture fixture, String flightNo, int passengerCount) throws Exception {
        String body = mockMvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(fixture, flightNo, passengerCount)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).get("bookingId").asInt();
    }

    private String adminToken() {
        return jwtUtil.generate(1, "Dikshya Ghising", "admin@gmail.com", "ADMIN");
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    /* ------------------------------------------------------------------ *
     *  fixtures                                                            *
     * ------------------------------------------------------------------ */

    private record Fixture(String tag, Airline airline, Destination from, Destination to) {
    }

    private Fixture fixture() {
        String tag = tag();

        Airline airline = new Airline();
        airline.setName(tag + " Air");
        airline.setIata(uniqueIata());
        airline.setStatus("Active");

        return new Fixture(tag, airlines.save(airline), seedDestination(tag), seedDestination(tag));
    }

    private Destination seedDestination(String tag) {
        Destination destination = new Destination();
        destination.setCity("City " + tag);
        destination.setCode(uniqueAirportCode());
        destination.setAirport(tag + " Airport");
        destination.setStatus("Active");
        return destinations.save(destination);
    }

    /** Creates a flight through the real admin endpoint, which also builds its seat map. */
    private String createFlight(Fixture fixture, int capacity, String fare) throws Exception {
        String no = uniqueFlightNo();

        mockMvc.perform(post("/api/admin/flights")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"no":"%s","airlineId":%d,"from":"%s","to":"%s",
                                 "dep":"06:50","arr":"07:35","aircraft":"ATR 72","fare":%s,"seats":%d,"status":"Active"}
                                """.formatted(no, fixture.airline().getId(), fixture.from().getCode(),
                                fixture.to().getCode(), fare, capacity)))
                .andExpect(status().isOk());

        return no;
    }

    private int flightId(String flightNo) {
        return flights.findByFlightNo(flightNo).orElseThrow().getId();
    }

    /**
     * The wizard's own payload. Seat numbers are omitted, which is what the real page
     * sends today, so the service assigns the lowest free seats — the same path a real
     * abandoned checkout takes.
     */
    private static String bookingBody(Fixture fixture, String flightNo, int passengerCount) {
        List<String> rows = new ArrayList<>();
        for (int index = 0; index < passengerCount; index++) {
            rows.add("""
                    {"title":"Mr","firstName":"Hari","middleName":"Prasad","lastName":"Sharma","nationality":"Nepali","type":"ADT"}""");
        }

        return """
                {"contact":{"title":"Mr","firstName":"Hari","middleName":"Prasad","lastName":"Sharma",
                 "email":"%s","phone":"%s","invoiceParty":"Self","panNo":"","isPassenger":true},
                 "passengers":[%s],
                 "flight":{"from":"%s","to":"%s","date":"%s","depart":"06:50","arrive":"07:35","flightNo":"%s",
                 "airline":"Air","flightClass":"E Class","refundable":true,"pricePerPassenger":8299.99,
                 "passengerCount":%d,"totalPrice":16599.98},
                 "amount":1}"""
                .formatted(contactEmail(fixture), contactPhone(fixture), String.join(",", rows),
                        fixture.from().getCode(), fixture.to().getCode(), LocalDate.now(), flightNo,
                        passengerCount);
    }

    private static String contactEmail(Fixture fixture) {
        return "hari-" + fixture.tag().toLowerCase() + "@example.com";
    }

    /** 10 digits, unique per run, in the roster's stored form. */
    private static String contactPhone(Fixture fixture) {
        return "98" + String.format("%08d", Math.abs(fixture.tag().hashCode()) % 100_000_000);
    }

    private static String tag() {
        return "T" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String uniqueFlightNo() {
        for (int attempt = 0; attempt < 50; attempt++) {
            String candidate = "T8 " + String.format("%04d",
                    Math.abs(UUID.randomUUID().hashCode()) % 10_000);
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
}
