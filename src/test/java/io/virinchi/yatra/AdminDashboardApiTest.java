package io.virinchi.yatra;

import io.virinchi.yatra.Model.Airline;
import io.virinchi.yatra.Model.Booking;
import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Repository.AirlineRepository;
import io.virinchi.yatra.Repository.BookingRepository;
import io.virinchi.yatra.Repository.DestinationRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import io.virinchi.yatra.Repository.UserRepository;
import io.virinchi.yatra.Security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Roadmap Phase 13 — {@code GET /api/admin/dashboard}: the seven Master Plan §2.5
 * cards and the recent-bookings table, in one body.
 *
 * <p>DB-backed, {@code @Transactional} and rolled back, like
 * {@code AdminTicketApiTest} and {@code PaymentApiTest}: the live TiDB schema is never
 * modified. The booking the second half of this class moves is produced by the real
 * flow ({@code POST /api/bookings} → {@code /api/payments/initiate} →
 * {@code /api/payments/verify}) rather than inserted, because "the numbers move when
 * the system does" is the whole point of a dashboard.
 *
 * <h2>What this class is really asserting</h2>
 * <ul>
 *   <li><b>The body is the two keys the page renders.</b> {@code admin-dashboard.js}
 *       draws a recent-bookings table from {@code resp.bookings} and seven cards from
 *       {@code resp.stats}, so those two — and nothing else — are the contract. That is
 *       R16 on this surface, stated positively for once: the shape carries what the page
 *       uses, and the three collection keys it used to carry exist nowhere.</li>
 *   <li><b>The roadmap's checkpoint:</b> every card matches the database. The totals
 *       are compared against the repositories, and revenue against the same
 *       non-cancelled sum the page's own title promises — both computed here
 *       independently, so this is a check rather than a restatement.</li>
 *   <li><b>The definitions are pinned, not implied.</b> Today's Bookings counts
 *       bookings <i>created</i> today and Pending Payments counts
 *       {@code paymentStatus = 'Pending'} — the one definition a "money-first"
 *       implementation would quietly change.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminDashboardApiTest {

    private static final String ADMIN = "ADMIN";
    private static final String USER = "USER";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private AirlineRepository airlines;
    @Autowired private DestinationRepository destinations;
    @Autowired private FlightRepository flights;
    @Autowired private BookingRepository bookings;
    @Autowired private UserRepository users;
    @Autowired private jakarta.persistence.EntityManager entityManager;

    /* ================================================================== *
     *  the page contract                                                 *
     * ================================================================== */

    /**
     * The body is exactly the two keys the page renders — the table's rows and the
     * cards' numbers — and nothing else.
     */
    @Test
    void theBodyIsTheRowsAndTheSevenCardsAndNothingElse() throws Exception {
        JsonNode body = dashboard();

        assertThat(body.size())
                .as("resp.bookings + resp.stats are the whole contract — the trimmed "
                        + "airlines/flights/users arrays must not come back")
                .isEqualTo(2);
        assertThat(body.get("bookings").isArray()).as("resp.bookings").isTrue();

        JsonNode stats = body.get("stats");
        assertThat(stats.isObject()).as("resp.stats").isTrue();
        assertThat(stats.size()).as("the seven Master Plan §2.5 cards").isEqualTo(7);
        assertThat(List.of("totalUsers", "totalFlights", "totalAirlines", "totalBookings",
                        "todaysBookings", "revenue", "pendingPayments"))
                .allSatisfy(key -> assertThat(stats.has(key)).as(key).isTrue());

        // The rows are the admin booking records the page's table renders, so the
        // two pages cannot show different numbers for the same booking.
        if (body.get("bookings").size() > 0) {
            JsonNode first = body.get("bookings").get(0);
            assertThat(first.get("id").isTextual())
                    .as("admin-bookings.js runs b.id.toLowerCase() — the dashboard reuses the row")
                    .isTrue();
        }
    }

    /* ================================================================== *
     *  the checkpoint — the cards match the database                     *
     * ================================================================== */

    /** Every total is what the repositories say, and revenue is the page's own sum. */
    @Test
    void everyCardMatchesTheDatabase() throws Exception {
        JsonNode stats = dashboard().get("stats");

        assertThat(stats.get("totalUsers").asLong())
                .as("Total Users").isEqualTo(users.count());
        assertThat(stats.get("totalAirlines").asLong())
                .as("Total Airlines").isEqualTo(airlines.count());
        assertThat(stats.get("totalFlights").asLong())
                .as("Total Flights").isEqualTo(flights.count());
        assertThat(stats.get("totalBookings").asLong())
                .as("Total Bookings").isEqualTo(bookings.count());

        List<Booking> rows = bookings.findAll();

        BigDecimal expectedRevenue = rows.stream()
                .filter(row -> !"CANCELLED".equalsIgnoreCase(row.getBookingStatus()))
                .map(Booking::getTotalAmount)
                .filter(java.util.Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(money(stats.get("revenue")))
                .as("Revenue = non-cancelled bookings, the page's own definition")
                .isEqualByComparingTo(expectedRevenue);

        long expectedToday = rows.stream()
                .filter(row -> row.getCreatedAt() != null)
                .filter(row -> row.getCreatedAt().toLocalDate().equals(LocalDate.now()))
                .count();
        assertThat(stats.get("todaysBookings").asLong())
                .as("Today's Bookings counts bookings CREATED today")
                .isEqualTo(expectedToday);

        long expectedPending = rows.stream()
                .filter(row -> "Pending".equalsIgnoreCase(row.getPaymentStatus()))
                .count();
        assertThat(stats.get("pendingPayments").asLong())
                .as("Pending Payments is the booking's paymentStatus, not the payment row")
                .isEqualTo(expectedPending);
    }

    /**
     * The point of a dashboard: the numbers move when the system does. A booking is
     * made and paid for through the real endpoints, and the cards follow — by exactly
     * one booking, exactly its amount, and with Pending Payments unchanged because the
     * booking was created pending and then settled.
     */
    @Test
    void theCardsMoveWithTheBookingAndThePayment() throws Exception {
        JsonNode before = dashboard().get("stats");
        long spendableBefore = before.get("totalBookings").asLong();
        long todayBefore = before.get("todaysBookings").asLong();
        long pendingBefore = before.get("pendingPayments").asLong();
        BigDecimal revenueBefore = money(before.get("revenue"));

        Fixture fixture = fixture();
        String flightNo = createFlight(fixture);
        int bookingId = book(fixture, flightNo);
        settle(bookingId);

        JsonNode after = dashboard();
        JsonNode stats = after.get("stats");

        assertThat(stats.get("totalBookings").asLong()).isEqualTo(spendableBefore + 1);
        assertThat(stats.get("todaysBookings").asLong()).isEqualTo(todayBefore + 1);

        // The amount the cards must have moved by is the one the list reports for this
        // very booking — the two cannot be compared against different sources.
        JsonNode row = null;
        for (JsonNode candidate : after.get("bookings")) {
            if (String.valueOf(bookingId).equals(candidate.get("id").asText())) {
                row = candidate;
                break;
            }
        }
        assertThat(row).as("the new booking must appear in the recent-bookings list").isNotNull();
        assertThat(row.get("status").asText()).isEqualTo("Confirmed");
        assertThat(row.get("paymentStatus").asText()).isEqualTo("Paid");

        assertThat(money(stats.get("revenue")))
                .as("revenue grew by exactly the booking's amount")
                .isEqualByComparingTo(revenueBefore.add(money(row.get("amount"))));

        assertThat(stats.get("pendingPayments").asLong())
                .as("created Pending, settled Paid — the card ends where it started")
                .isEqualTo(pendingBefore);
    }

    /* ================================================================== *
     *  authorization — both layers                                        *
     * ================================================================== */

    @Test
    void onlyAnAdminReachesTheDashboard() throws Exception {
        mockMvc.perform(get("/api/admin/dashboard")).andExpect(status().isUnauthorized());

        String userToken = jwtUtil.generate(2, "Anju Karki", "anju.karki@example.com", USER);
        mockMvc.perform(get("/api/admin/dashboard").header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isForbidden());
    }

    /* ================================================================== *
     *  helpers                                                            *
     * ================================================================== */

    /** The dashboard as an admin, parsed. */
    private JsonNode dashboard() throws Exception {
        String body = mockMvc.perform(get("/api/admin/dashboard")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats").exists())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body);
    }

    /** Initiate, then verify — the gateway walk that confirms the booking and pays it. */
    private void settle(int bookingId) throws Exception {
        mockMvc.perform(post("/api/payments/initiate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookingId":%d,"method":"esewa","amount":null,"promoCode":"YATRA10"}
                                """.formatted(bookingId)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/payments/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"txnId":"%s","bookingId":%d,"method":"esewa","amount":1,"productAmount":1,
                                 "customerName":"Ignored","outcome":"SUCCESS",
                                 "booking":{"contact":{"firstName":"Re-sent by the mock page"},"passengers":[]}}
                                """.formatted(txnId(), bookingId)))
                .andExpect(status().isOk());

        entityManagerFlush();
    }

    /* ------------------------------------------------------------------ *
     *  fixtures — the devices the Phase 9-12 suites use, for the same reason
     * ------------------------------------------------------------------ */

    private record Fixture(String tag, String flightNo, Airline airline, Destination from, Destination to) {

        String contactName() {
            return "Ms Sita" + tag + " Rai";
        }

        String contactEmail() {
            return "sita-" + tag.toLowerCase() + "@example.com";
        }

        String contactPhone() {
            return "97" + String.format("%08d", Math.abs(tag.hashCode()) % 100_000_000);
        }
    }

    private Fixture fixture() {
        String tag = tag();

        Airline airline = new Airline();
        airline.setName(tag + " Air");
        airline.setIata(uniqueIata());
        airline.setStatus("Active");

        return new Fixture(tag, uniqueFlightNo(), airlines.save(airline),
                seedDestination(tag), seedDestination(tag));
    }

    private String createFlight(Fixture fixture) throws Exception {
        String no = fixture.flightNo();

        mockMvc.perform(post("/api/admin/flights")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"no":"%s","airlineId":%d,"from":"%s","to":"%s",
                                 "dep":"06:50","arr":"07:35","aircraft":"ATR 72","fare":8299.99,"seats":8,"status":"Active"}
                                """.formatted(no, fixture.airline().getId(), fixture.from().getCode(),
                                fixture.to().getCode())))
                .andExpect(status().isOk());

        return no;
    }

    /** Creates a booking through the public endpoint and returns its id. */
    private int book(Fixture fixture, String flightNo) throws Exception {
        List<String> rows = new ArrayList<>();
        rows.add("""
                {"title":"Ms","firstName":"Sita%s","middleName":"","lastName":"Rai","nationality":"Nepali","type":"ADT"}
                """.formatted(fixture.tag()));

        String body = mockMvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contact":{"title":"Ms","firstName":"Sita%s","middleName":"","lastName":"Rai",
                                 "email":"%s","phone":"%s","invoiceParty":"Self","panNo":"","isPassenger":true},
                                 "passengers":[%s],
                                 "flight":{"from":"%s","to":"%s","date":"%s","depart":"06:50","arrive":"07:35","flightNo":"%s",
                                 "airline":"Air","flightClass":"E Class","refundable":true,"pricePerPassenger":8299.99,
                                 "passengerCount":1,"totalPrice":8299.99},
                                 "amount":1}
                                """.formatted(fixture.tag(), fixture.contactEmail(), fixture.contactPhone(),
                                String.join(",", rows), fixture.from().getCode(), fixture.to().getCode(),
                                LocalDate.now(), flightNo)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).get("bookingId").asInt();
    }

    /**
     * A money field, read from the node's text rather than a numeric accessor: the
     * JSON is the contract being asserted, and parsing its own text is exactly what
     * the page's {@code Number(...)} does with it.
     */
    private static BigDecimal money(JsonNode node) {
        return new BigDecimal(node.asText());
    }

    private void entityManagerFlush() {
        entityManager.flush();
        entityManager.clear();
    }

    private Destination seedDestination(String tag) {
        Destination destination = new Destination();
        destination.setCity("City " + tag);
        destination.setCode(uniqueAirportCode());
        destination.setAirport(tag + " Airport");
        destination.setStatus("Active");
        return destinations.save(destination);
    }

    private String uniqueFlightNo() {
        for (int attempt = 0; attempt < 50; attempt++) {
            String candidate = "T9 " + String.format("%04d",
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

    /** A transaction id in the mock page's own shape: {@code "9A" + 8 digits}. */
    private static String txnId() {
        return "9A" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
    }

    private String adminToken() {
        return jwtUtil.generate(1, "Dikshya Ghising", "admin@gmail.com", ADMIN);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String tag() {
        return "D" + UUID.randomUUID().toString().substring(0, 8);
    }
}
