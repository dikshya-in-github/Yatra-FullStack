package io.virinchi.yatra;

import io.virinchi.yatra.Model.Airline;
import io.virinchi.yatra.Model.Booking;
import io.virinchi.yatra.Model.Passenger;
import io.virinchi.yatra.Model.Payment;
import io.virinchi.yatra.Model.Ticket;
import io.virinchi.yatra.Repository.AirlineRepository;
import io.virinchi.yatra.Repository.BookingRepository;
import io.virinchi.yatra.Repository.DestinationRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import io.virinchi.yatra.Repository.PassengerRepository;
import io.virinchi.yatra.Repository.PaymentRepository;
import io.virinchi.yatra.Repository.SeatRepository;
import io.virinchi.yatra.Repository.TicketRepository;
import io.virinchi.yatra.Repository.UserRepository;
import io.virinchi.yatra.Security.JwtUtil;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Roadmap Phase 7 — the demo seeder, its reset, and the re-run path.
 *
 * <p>DB-backed and {@code @Transactional} like the other suites, so the seeded rows are
 * rolled back and the live database is never modified: a seeded database is what the
 * <i>demo</i> asks for, never a side effect of running the tests. That is also why the
 * seeder is an admin endpoint rather than a startup runner (see {@code SeedController}).
 *
 * <h2>Why there are only a few tests here, when they assert a lot</h2>
 *
 * <p><b>A full seed is expensive — about a minute — and the reason is structural.</b>
 * Every entity uses {@code GenerationType.IDENTITY} (the SpringWeb template's
 * convention), and with IDENTITY Hibernate cannot batch inserts: it needs each row's
 * generated key in hand, so all 682 seat rows, 12 flights and 8 bookings go over the
 * wire as individual statements to the remote TiDB Cloud instance at roughly 40 ms
 * each. Measured, not assumed: creating one 70-seat flight takes ~3.5 s, which is why
 * {@code FlightApiTest} takes minutes too.
 *
 * <p>So the suite seeds <b>four</b> times rather than once per assertion, and each test
 * groups the assertions that describe one seeded state. Splitting them further would
 * multiply a minute-long operation by the number of facts being checked, and the
 * separate tests would then be re-checking the same dataset from scratch.
 *
 * <h2>What is verified</h2>
 *
 * <p>The roadmap checkpoint — seed, then every admin list answers with realistic
 * non-empty data — plus the properties that make it re-runnable rather than one-shot: a
 * second seed is refused with a 409 and writes nothing, a reset removes exactly the
 * marked rows and leaves your own work alone, and reset-then-seed reproduces the same
 * dataset.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SeedApiTest {

    /** What the seeder's content lists add up to, asserted rather than assumed. */
    private static final int AIRLINES = 4;
    private static final int DESTINATIONS = 11;
    private static final int USERS = 10;
    private static final int FLIGHTS = 12;
    private static final int BOOKINGS = 8;
    private static final int PASSENGERS = 15;
    private static final int SEATS = 682;   // 70+70+78+19+70+46+70+46+78+19+70+46

    @Autowired private MockMvc mockMvc;
    @Autowired private EntityManager entityManager;
    @Autowired private AirlineRepository airlines;
    @Autowired private BookingRepository bookings;
    @Autowired private DestinationRepository destinations;
    @Autowired private FlightRepository flights;
    @Autowired private PassengerRepository passengers;
    @Autowired private PaymentRepository payments;
    @Autowired private SeatRepository seats;
    @Autowired private TicketRepository tickets;
    @Autowired private UserRepository users;
    @Autowired private JwtUtil jwtUtil;

    /* ------------------------------------------------------------------ *
     *  the checkpoint, and the guard that keeps it re-runnable            *
     * ------------------------------------------------------------------ */

    /**
     * One seed, then everything the seeded dataset promises: the roadmap's checkpoint
     * (every admin list realistic and non-empty), real seat holds behind the bookings,
     * money that agrees with itself, dated flights, a usable demo account — and a second
     * seed refused without writing anything.
     */
    @Test
    void seedingCreatesTheDemoDatasetAndRefusesASecondRun() throws Exception {
        // Start from a database that is not seeded, as the assertions below describe.
        // A *seeded* one is the demo's normal resting state, and the walk in postman/
        // leaves the rows it creates behind by design, so "a seed succeeds" is not a
        // property of the environment — it is a property of this test's own start.
        // The reset removes exactly the marked rows, and the test transaction rolls the
        // whole scene back either way. (Reset-then-seed is the documented re-run path.)
        mockMvc.perform(adminPost("/api/admin/reset")).andExpect(status().isOk());

        mockMvc.perform(seed())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("SEED"))
                .andExpect(jsonPath("$.counts.airlines").value(AIRLINES))
                .andExpect(jsonPath("$.counts.destinations").value(DESTINATIONS))
                .andExpect(jsonPath("$.counts.users").value(USERS))
                .andExpect(jsonPath("$.counts.flights").value(FLIGHTS))
                .andExpect(jsonPath("$.counts.seats").value(SEATS))
                .andExpect(jsonPath("$.counts.bookings").value(BOOKINGS))
                .andExpect(jsonPath("$.counts.passengers").value(PASSENGERS))
                .andExpect(jsonPath("$.counts.payments").value(BOOKINGS))
                .andExpect(jsonPath("$.counts.tickets").value(BOOKINGS));

        /* --- the checkpoint: the admin lists answer with real data --- *
         * The list assertions are "at least", not "exactly": a database that has
         * already been used for the demo may hold flights or carriers of its own,
         * and this suite has to stay valid against it. The exact figures are the
         * counts the seeder itself reported, asserted above. */
        mockMvc.perform(adminGet("/api/admin/flights"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flights.length()").value(greaterThanOrEqualTo(FLIGHTS)))
                // The mock's hand-typed bookedSeats (4 for U4 951) is now a count
                // derived from the seeded bookings — two of them, two passengers each.
                .andExpect(jsonPath("$.flights[?(@.no == 'U4 951')].bookedSeats").value(contains(4)));

        mockMvc.perform(get("/api/airlines"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.airlines.length()").value(greaterThanOrEqualTo(AIRLINES)))
                // The logo really is in the MEDIUMBLOB; the list only ever carries the URL.
                .andExpect(jsonPath("$.airlines[?(@.iata == 'U4')].logo")
                        .value(contains(startsWith("/api/airlines/"))));

        assertThat(bookings.findBySeededTrue()).hasSize(BOOKINGS);
        assertThat(users.findBySeededTrue()).hasSize(USERS);
        assertThat(airlines.findBySeededTrue()).hasSize(AIRLINES);

        /* --- the dates are relative, so a dated search always finds something --- */
        mockMvc.perform(adminGet("/api/admin/flights")
                        .param("date", LocalDate.now().plusDays(1).toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flights[?(@.no == 'S3 507')].no").value(contains("S3 507")))
                .andExpect(jsonPath("$.flights[?(@.no == 'ST 221')].no").value(contains("ST 221")));

        /* --- the demo roster is usable: the seeded admin can sign in --- */
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"admin@yatra.com","password":"Yatra@123"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.role").value("ADMIN"));

        /* --- every passenger holds a real, atomically claimed seat --- *
         * refresh() first: the claim is a bulk JPQL UPDATE, which leaves the entity
         * Hibernate cached in this transaction reporting the old status (risk R13). */
        refresh();

        int held = 0;
        for (Booking booking : bookings.findBySeededTrue()) {
            for (Passenger passenger : passengers.findByBookingId(booking.getId())) {
                assertThat(passenger.getSeatNumber()).as("passenger seat number").isNotBlank();
                assertThat(seats.findByFlightIdAndSeatNumber(
                        booking.getFlight().getId(), passenger.getSeatNumber()).orElseThrow().getStatus())
                        .as("seat " + passenger.getSeatNumber() + " on " + booking.getFlight().getFlightNo())
                        .isEqualTo("BOOKED");
                held++;
            }
        }
        assertThat(held).as("one held seat per seeded passenger").isEqualTo(PASSENGERS);

        /* --- money agrees with itself --- *
         * Payment.amount is what the gateway charged; Booking.totalAmount is what the
         * booking says is owed. The payments page and the refund flow read both. */
        for (Booking booking : bookings.findBySeededTrue()) {
            Payment payment = payments.findByBookingId(booking.getId()).orElseThrow();
            assertThat(payment.getAmount())
                    .as("payment vs booking total for " + booking.getContactEmail())
                    .isEqualByComparingTo(booking.getTotalAmount());
            assertThat(payment.getTxnId()).isNotBlank();

            Ticket ticket = tickets.findByBookingId(booking.getId()).orElseThrow();
            assertThat(ticket.getPnr()).isNotBlank();
            assertThat(ticket.getTicketNo()).isNotBlank();
        }

        // The one cancelled booking was refunded, so its ticket is cancelled with it.
        Booking cancelled = bookings.findBySeededTrue().stream()
                .filter(booking -> "CANCELLED".equalsIgnoreCase(booking.getBookingStatus()))
                .findFirst()
                .orElseThrow();
        assertThat(payments.findByBookingId(cancelled.getId()).orElseThrow().getStatus()).isEqualTo("REFUNDED");
        assertThat(tickets.findByBookingId(cancelled.getId()).orElseThrow().getStatus()).isEqualTo("CANCELLED");

        /* --- and a second seed is refused, writing nothing --- *
         * The guard exists because seeding twice would double every booked-seat count
         * and try to mint a second payment and ticket per booking, which the unique
         * keys on booking_id would refuse anyway — as a 500 rather than a clear 409. */
        mockMvc.perform(seed())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ALREADY_SEEDED"));

        refresh();
        assertThat(bookings.findBySeededTrue()).hasSize(BOOKINGS);
        assertThat(flights.findBySeededTrue()).hasSize(FLIGHTS);
        assertThat(seats.countByFlightIdAndStatus(
                flights.findByFlightNo("U4 951").orElseThrow().getId(), "BOOKED"))
                .as("booked seats unchanged by the refused second seed")
                .isEqualTo(4);
    }

    /* ------------------------------------------------------------------ *
     *  reset                                                              *
     * ------------------------------------------------------------------ */

    /**
     * The reset is the one operation that may remove a paid, ticketed booking — and it
     * must still remove <b>only</b> the marked rows.
     *
     * <p>Both awkward cases are checked here, in one seeded state: a carrier and a flight
     * you created yourself survive; a <i>seeded</i> carrier your own flight rides on is
     * kept and reported rather than deleted (which the FK would refuse anyway); and the
     * airports, which are reference data rather than demo content, are never touched.
     */
    @Test
    void resetRemovesSeedDataAndKeepsWhatYouMadeYourself() throws Exception {
        // Seeded wherever the database started: this test describes what a reset does to
        // a seeded dataset, so it has to create that dataset rather than find it.
        mockMvc.perform(adminPost("/api/admin/reset")).andExpect(status().isOk());
        mockMvc.perform(seed()).andExpect(status().isOk());

        Airline seeded = airlines.findByIata("ST").orElseThrow();
        int destinationsBefore = (int) destinations.count();

        String myIata = uniqueIata();
        String myFlightNo = uniqueFlightNo();
        String onSeedFlightNo = uniqueFlightNo();

        mockMvc.perform(adminPost("/api/admin/airlines")
                        .content("""
                                {"name":"My Own Air","iata":"%s","description":"made by hand","status":"Active"}"""
                                .formatted(myIata)))
                .andExpect(status().isOk());
        Airline mine = airlines.findByIata(myIata).orElseThrow();

        mockMvc.perform(adminPost("/api/admin/flights")
                        .content("""
                                {"no":"%s","airlineId":%d,"from":"KTM","to":"PKR","dep":"08:00","arr":"08:45",
                                 "aircraft":"ATR 72","fare":7999.99,"seats":10,"status":"Active"}"""
                                .formatted(myFlightNo, mine.getId())))
                .andExpect(status().isOk());

        // A flight of your own riding on a *seeded* carrier — the case that must be kept,
        // because deleting the carrier would orphan it.
        mockMvc.perform(adminPost("/api/admin/flights")
                        .content("""
                                {"no":"%s","airlineId":%d,"from":"KTM","to":"KEP","dep":"18:00","arr":"18:55",
                                 "aircraft":"Dornier 228","fare":9199.99,"seats":12,"status":"Active"}"""
                                .formatted(onSeedFlightNo, seeded.getId())))
                .andExpect(status().isOk());

        // The seeded bookings' payment and ticket ids, captured before the reset so the
        // check afterwards can be about *those* rows. A live Postman walk creates paid,
        // ticketed bookings of its own, and a reset must leave them alone — so
        // "payments.count() is zero" was a statement about the whole table rather than
        // about the demo dataset, and it only held while nobody had used the app.
        List<Integer> seededPaymentIds = new ArrayList<>();
        List<Integer> seededTicketIds = new ArrayList<>();
        for (Booking booking : bookings.findBySeededTrue()) {
            payments.findByBookingId(booking.getId()).ifPresent(payment -> seededPaymentIds.add(payment.getId()));
            tickets.findByBookingId(booking.getId()).ifPresent(ticket -> seededTicketIds.add(ticket.getId()));
        }
        assertThat(seededPaymentIds).as("one seeded payment per seeded booking").hasSize(BOOKINGS);
        assertThat(seededTicketIds).as("one seeded ticket per seeded booking").hasSize(BOOKINGS);

        mockMvc.perform(adminPost("/api/admin/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("RESET"))
                .andExpect(jsonPath("$.counts.bookings").value(BOOKINGS))
                .andExpect(jsonPath("$.counts.passengers").value(PASSENGERS))
                .andExpect(jsonPath("$.counts.flights").value(FLIGHTS))
                .andExpect(jsonPath("$.counts.airlines").value(AIRLINES - 1))
                .andExpect(jsonPath("$.counts.users").value(USERS))
                .andExpect(jsonPath("$.counts.destinations").value(0))
                .andExpect(jsonPath("$.kept.length()").value(1))
                .andExpect(jsonPath("$.kept[0]").value(containsString("Sita Air")));

        refresh();

        // ST survives — and keeps its marker. Keeping the flag is the point: the row is
        // still seed data, it just could not be removed yet, so a later reset finishes
        // the job once nothing of yours uses it (proved at the end of this test). If the
        // flag were cleared here, that carrier would be permanent.
        assertThat(airlines.findBySeededTrue()).as("only the carrier your own flight still uses survives")
                .extracting(Airline::getIata).containsExactly("ST");
        assertThat(flights.findBySeededTrue()).as("seeded flights gone").isEmpty();
        assertThat(bookings.findBySeededTrue()).as("seeded bookings gone").isEmpty();
        assertThat(users.findBySeededTrue()).as("seeded roster gone").isEmpty();
        assertThat(payments.findAllById(seededPaymentIds)).as("seeded payments gone").isEmpty();
        assertThat(tickets.findAllById(seededTicketIds)).as("seeded tickets gone").isEmpty();

        assertThat(airlines.findByIata(myIata)).as("your airline survives").isPresent();
        assertThat(flights.findByFlightNo(myFlightNo)).as("your flight survives").isPresent();
        assertThat(flights.findByFlightNo(onSeedFlightNo))
                .as("your flight on a seeded carrier survives too")
                .isPresent();
        assertThat(destinations.count())
                .as("airports are reference data — a reset never deletes them")
                .isEqualTo(destinationsBefore);

        /* --- and a later reset finishes the job once your flight is gone --- *
         * The "kept" carrier is not a leak: the marker stayed on it, so the moment the
         * last record of yours using it is deleted, the next reset removes it. */
        int onSeedFlightId = flights.findByFlightNo(onSeedFlightNo).orElseThrow().getId();
        mockMvc.perform(delete("/api/admin/flights/" + onSeedFlightId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isNoContent());

        mockMvc.perform(adminPost("/api/admin/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.airlines").value(AIRLINES - 3))
                .andExpect(jsonPath("$.kept").isEmpty());

        refresh();
        assertThat(airlines.findBySeededTrue())
                .as("a carrier kept earlier is removable as soon as nothing uses it")
                .isEmpty();
        assertThat(airlines.findByIata(myIata)).as("your own carrier is never touched").isPresent();
    }

    /**
     * The documented re-run path, and the roadmap's "re-runnable without manual DB
     * cleanup": reset, seed again, land on the same dataset. Also proves a reset is safe
     * when nothing is seeded, so the two-step is always valid.
     */
    @Test
    void resetThenSeedReproducesTheSameDataset() throws Exception {
        // Two resets, because the first one has work to do and the second one is the
        // assertion: a reset with nothing seeded removes nothing and keeps nothing, so
        // the two-step is always a valid way to start from scratch. Asking the *first*
        // reset to report zero only held while the database happened to be unseeded.
        mockMvc.perform(adminPost("/api/admin/reset")).andExpect(status().isOk());

        mockMvc.perform(adminPost("/api/admin/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.bookings").value(0))
                .andExpect(jsonPath("$.counts.flights").value(0))
                .andExpect(jsonPath("$.kept").isEmpty());

        mockMvc.perform(seed())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.flights").value(FLIGHTS))
                .andExpect(jsonPath("$.counts.bookings").value(BOOKINGS));

        mockMvc.perform(adminPost("/api/admin/reset")).andExpect(status().isOk());
        refresh();
        assertThat(bookings.findBySeededTrue()).isEmpty();

        mockMvc.perform(seed())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.flights").value(FLIGHTS))
                .andExpect(jsonPath("$.counts.bookings").value(BOOKINGS))
                .andExpect(jsonPath("$.counts.users").value(USERS))
                .andExpect(jsonPath("$.counts.seats").value(SEATS));

        assertThat(bookings.findBySeededTrue()).hasSize(BOOKINGS);
        assertThat(flights.findBySeededTrue()).hasSize(FLIGHTS);
    }

    /* ------------------------------------------------------------------ *
     *  access control                                                     *
     * ------------------------------------------------------------------ */

    /** A bulk write and a destructive reset are admin-only, like every other admin write. */
    @Test
    void seedAndResetRequireAnAdminToken() throws Exception {
        int seededFlightsWhenTheTestStarted = flights.findBySeededTrue().size();

        mockMvc.perform(post("/api/admin/seed"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("NOT_AUTHENTICATED"));

        String userToken = jwtUtil.generate(2, "A Customer", "customer@example.com", "USER");
        mockMvc.perform(post("/api/admin/seed")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        mockMvc.perform(post("/api/admin/reset")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden());

        // "Writes nothing" measured against what was there when the test started, not
        // against an empty table: the demo seed fills it, and a live walk adds to it.
        refresh();
        assertThat(flights.findBySeededTrue())
                .as("a refused call writes nothing")
                .hasSize(seededFlightsWhenTheTestStarted);
    }

    /* ------------------------------------------------------------------ *
     *  helpers                                                            *
     * ------------------------------------------------------------------ */

    private MockHttpServletRequestBuilder seed() {
        return adminPost("/api/admin/seed");
    }

    private MockHttpServletRequestBuilder adminGet(String url) {
        return get(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken());
    }

    private MockHttpServletRequestBuilder adminPost(String url) {
        return post(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private String adminToken() {
        return jwtUtil.generate(1, "Dikshya Ghising", "admin@yatra.com", "ADMIN");
    }

    /** Flushes and drops the persistence context so the next read comes from the database. */
    private void refresh() {
        entityManager.flush();
        entityManager.clear();
    }

    /** IATA codes are UNIQUE and only 36² exist, so this is checked rather than assumed. */
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

    private String uniqueFlightNo() {
        for (int attempt = 0; attempt < 50; attempt++) {
            String digits = UUID.randomUUID().toString().replaceAll("[^0-9]", "");
            String candidate = "T9 " + digits.substring(0, 3 + (int) (Math.random() * 2));
            if (flights.findByFlightNo(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not find an unused flight number");
    }
}
