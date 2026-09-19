package io.virinchi.yatra;

import io.virinchi.yatra.Model.Airline;
import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Model.Ticket;
import io.virinchi.yatra.Repository.AirlineRepository;
import io.virinchi.yatra.Repository.DestinationRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import io.virinchi.yatra.Repository.TicketRepository;
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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Roadmap Phase 12 — the admin ticket read: {@code GET /api/admin/tickets} with its
 * search, both status filters, paging, and {@code GET /api/admin/tickets/{bookingId}}.
 *
 * <p>DB-backed, {@code @Transactional} and rolled back, like {@code PaymentApiTest}:
 * the live TiDB schema is never modified. The tickets it asserts on are <b>not</b>
 * inserted by hand — every one is produced by the real flow
 * ({@code POST /api/bookings} → {@code /api/payments/initiate} →
 * {@code /api/payments/verify}), because that is the roadmap's checkpoint: a fully
 * booked and paid flow must produce a retrievable ticket through this endpoint.
 * Only the airline / airport / flight prerequisites go through the existing
 * endpoints or repositories, as the Phase 6, 9 and 10 suites already do.
 *
 * <h2>The assertions that are about the <i>contract</i>, not the data</h2>
 * <ul>
 *   <li><b>The list answers {@code { bookings: [...] }}.</b> {@code admin-tickets.js}
 *       derives a document per booking and reads {@code resp.bookings}; a
 *       purpose-built {@code tickets} array would leave that undefined and the page
 *       would quietly render its localStorage demo seeds (risk R16).</li>
 *   <li><b>{@code id} is a <i>string</i>.</b> The page's search runs
 *       {@code b.id.toLowerCase(...)} and its row action compares
 *       {@code b.id === viewBtn.dataset.view}, so an integer id would throw into the
 *       page's {@code .catch()} and show demo rows instead of an error.</li>
 *   <li><b>{@code status} is the page's display vocabulary</b> — {@code Confirmed} /
 *       {@code Cancelled} / {@code Pending}. The page turns {@code Cancelled} into the
 *       "Voided" badge and colours everything else by {@code Confirmed}; an
 *       upper-case body would redden every row silently.</li>
 *   <li><b>Every customer field is a string, never null.</b> The page passes the
 *       name, phone and email straight into {@code escapeHtml(...)}, and
 *       {@code String(null)} renders "null".</li>
 * </ul>
 *
 * <h2>Uniqueness, so a live database cannot make this flaky</h2>
 * <p>The seeding phase may already hold tickets, and {@code pnr} / {@code ticket_no}
 * are UNIQUE, so every fixture mints its own airline, airports and flight number from
 * a random source and every assertion is scoped to that flight number rather than to a
 * global count.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminTicketApiTest {

    private static final String ADMIN = "ADMIN";
    private static final String USER = "USER";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private jakarta.persistence.EntityManager entityManager;
    @Autowired private AirlineRepository airlines;
    @Autowired private DestinationRepository destinations;
    @Autowired private FlightRepository flights;
    @Autowired private TicketRepository tickets;
    @Autowired private JwtUtil jwtUtil;

    /* ================================================================== *
     *  the checkpoint                                                     *
     * ================================================================== */

    /**
     * The roadmap's checkpoint, end to end: a booking that really went through the
     * gateway is retrievable through this endpoint, by its own PNR.
     */
    @Test
    void aPaidBookingProducesARetrievableTicket() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8);
        int bookingId = book(fixture, flightNo, 2);
        settle(bookingId, "esewa", "SUCCESS");

        String pnr = ticketOf(bookingId).getPnr();

        JsonNode rows = list("search", pnr).get("bookings");
        assertThat(rows.size()).as("the PNR the payment minted must find exactly one row").isEqualTo(1);

        JsonNode row = rows.get(0);
        assertThat(row.get("pnr").asText()).isEqualTo(pnr);
        assertThat(row.get("ticketNo").asText()).isEqualTo(ticketOf(bookingId).getTicketNo());
        assertThat(row.get("customer").asText()).isEqualTo(fixture.contactName());
        assertThat(row.get("flight").get("flightNo").asText()).isEqualTo(flightNo);
    }

    /**
     * The row is the page's record, key by key — the R16 contract, asserted rather
     * than assumed, because every one of these breaks the page <i>silently</i>.
     */
    @Test
    void theRowSpeaksThePagesKeysAndVocabulary() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8);
        int bookingId = book(fixture, flightNo, 1);
        settle(bookingId, "esewa", "SUCCESS");

        JsonNode row = only(flightNo);

        // id is textual — the page calls .toLowerCase() and compares it as a string.
        assertThat(row.get("id").isTextual())
                .as("admin-tickets.js runs b.id.toLowerCase() — an integer id throws into its .catch()")
                .isTrue();
        assertThat(row.get("id").asText()).isEqualTo(String.valueOf(bookingId));

        // The page's own display vocabulary, not the entity's storage vocabulary.
        assertThat(row.get("status").asText()).isEqualTo("Confirmed");
        assertThat(row.get("paymentStatus").asText()).isEqualTo("Paid");

        // Never null: the page feeds these straight into escapeHtml().
        assertThat(row.get("ticketNo").asText()).isNotBlank();
        assertThat(row.get("pnr").asText()).isNotBlank();
        assertThat(row.get("customer").asText()).isEqualTo(fixture.contactName());
        assertThat(row.get("email").asText()).isEqualTo(fixture.contactEmail());
        assertThat(row.get("phone").asText()).isEqualTo(fixture.contactPhone());

        // The detail modal's blocks: passengers, flight, payment, amounts.
        assertThat(row.get("passengers").size()).isEqualTo(1);
        assertThat(row.get("flight").get("from").asText()).isNotBlank();
        assertThat(row.get("flight").get("to").asText()).isNotBlank();
        assertThat(row.get("payment").get("txnId").asText()).isNotBlank();
        assertThat(row.get("amount").asText()).isNotBlank();
    }

    /**
     * The row carries the <b>document's</b> own state, and it is not the booking's.
     *
     * <p>This field exists because the page was getting the answer wrong: its Status
     * column derived "Issued"/"Voided" from the <i>booking's</i> status rather than
     * reading the ticket's own column — and the two are independent. <b>Nothing in the
     * API voids a document</b>: {@code TicketService.issue} writes {@code ISSUED} when a
     * payment settles, {@code PaymentService.refund} moves money and touches nothing
     * else, and cancelling a booking leaves the ticket alone. So a cancelled booking's
     * live document was painted "Voided". The demo's one {@code CANCELLED} ticket is the
     * seeder's, on a booking whose status the seeder also writes as cancelled — which is
     * why the derived label looked right until a real cancellation went through.
     *
     * <p>The divergent state is therefore built by setting the row directly, and that is
     * not laziness: since no API call can write {@code CANCELLED}, a hand-set row is the
     * only way to make the two columns differ. What is under test is the mapper — that the
     * DTO reports the ticket's own column rather than re-deriving a value from the
     * booking's status.
     */
    @Test
    void theRowReportsTheTicketsOwnStatusAndNotTheBookings() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8);
        int bookingId = book(fixture, flightNo, 1);
        settle(bookingId, "esewa", "SUCCESS");

        JsonNode fresh = only(flightNo);
        assertThat(fresh.get("ticketStatus").asText()).as("a freshly minted document")
                .isEqualTo("ISSUED");
        assertThat(fresh.get("status").asText()).isEqualTo("Confirmed");

        refresh();
        Ticket ticket = ticketOf(bookingId);
        ticket.setStatus("CANCELLED");
        tickets.save(ticket);
        refresh();

        JsonNode voided = only(flightNo);
        assertThat(voided.get("ticketStatus").asText())
                .as("the page's badge reads this, not the booking's status")
                .isEqualTo("CANCELLED");
        assertThat(voided.get("status").asText())
                .as("a voided document is not a cancelled booking — the booking is untouched")
                .isEqualTo("Confirmed");

        // And the endpoint can be asked for the voided documents directly, which is the
        // filter the page now offers. The booking-status filter is a different column and
        // must NOT be what answers this.
        assertThat(count("search", flightNo, "ticketStatus", "CANCELLED")).isEqualTo(1);
        assertThat(count("search", flightNo, "ticketStatus", "ISSUED")).isZero();
        assertThat(count("search", flightNo, "status", "Confirmed"))
                .as("the voided ticket still belongs to a Confirmed booking")
                .isEqualTo(1);
    }

    /* ================================================================== *
     *  search                                                             *
     * ================================================================== */

    /**
     * The search box's fourth term: the passenger's name, which the page prints in its
     * own Passenger column.
     *
     * <p>The query had documented that passenger names were deliberately not matched —
     * the page promised them in its placeholder anyway, so an admin could read a name
     * off the screen and get zero rows with no explanation. They are matched now, by a
     * correlated {@code EXISTS} rather than a join, which is what keeps the paged read a
     * database page (see {@code TicketRepository}'s class note).
     *
     * <p>The booking is made with a passenger whose name is <b>not</b> the contact's on
     * purpose: {@link #book} names both after the fixture, so a search for the shared name
     * would be satisfied by the contact clause and this test would pass with the passenger
     * clause deleted.
     */
    @Test
    void theSearchMatchesThePassengerNamesThePageDisplays() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8);
        String passengerFirst = "Gita" + fixture.tag();
        int bookingId = book(fixture, flightNo, 1, passengerFirst);
        settle(bookingId, "esewa", "SUCCESS");

        assertThat(count("search", passengerFirst))
                .as("the passenger's first name — the row the Passenger column shows")
                .isEqualTo(1);
        assertThat(count("search", passengerFirst + " Rai"))
                .as("and the two together, the way the old client-side filter matched them")
                .isEqualTo(1);
        assertThat(count("search", fixture.contactName()))
                .as("the contact block still matches too — the new clause is OR'd in")
                .isEqualTo(1);
        assertThat(count("search", "Nobody" + fixture.tag()))
                .as("and a name nobody carries still finds nothing")
                .isZero();
    }

    /** Every term the page's search box advertises resolves the run's own ticket. */
    @Test
    void theSearchSpansThePnrTheTicketNumberTheCustomerTheFlightAndTheBookingId() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8);
        int bookingId = book(fixture, flightNo, 1);
        settle(bookingId, "esewa", "SUCCESS");

        Ticket ticket = ticketOf(bookingId);

        assertThat(count("search", ticket.getPnr())).as("PNR").isEqualTo(1);
        assertThat(count("search", ticket.getTicketNo())).as("ticket number").isEqualTo(1);
        assertThat(count("search", fixture.contactName())).as("customer name").isEqualTo(1);
        assertThat(count("search", fixture.contactEmail())).as("customer email").isEqualTo(1);
        assertThat(count("search", fixture.contactPhone())).as("customer phone").isEqualTo(1);
        assertThat(count("search", flightNo)).as("flight number").isEqualTo(1);
        assertThat(count("search", String.valueOf(bookingId))).as("booking id").isEqualTo(1);

        // Case-insensitive, the way the page's own search is.
        assertThat(count("search", ticket.getPnr().toLowerCase())).as("lower-case PNR").isEqualTo(1);

        // A term that matches nothing in this run finds nothing — no accidental match
        // on another fixture or a seeded row.
        assertThat(count("search", "no-such-pnr-" + fixture.tag())).isZero();
    }

    /* ================================================================== *
     *  filters                                                            *
     * ================================================================== */

    /**
     * {@code status} is the page's dropdown, so it must accept the page's values and
     * mean the page's thing — the <i>booking's</i> state.
     */
    @Test
    void theStatusFilterSpeaksThePagesVocabulary() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8);
        int bookingId = book(fixture, flightNo, 1);
        settle(bookingId, "esewa", "SUCCESS");

        assertThat(count("search", flightNo, "status", "Confirmed")).as("Confirmed").isEqualTo(1);
        assertThat(count("search", flightNo, "status", "confirmed")).as("lower case").isEqualTo(1);
        assertThat(count("search", flightNo, "status", "ALL")).as("ALL = no filter").isEqualTo(1);
        assertThat(count("search", flightNo, "status", "")).as("blank = no filter").isEqualTo(1);
        assertThat(count("search", flightNo, "status", "Cancelled")).as("not cancelled yet").isZero();

        // Cancel it through the admin endpoint the page's cancel button calls, and the
        // list has to move with it — same row, new vocabulary.
        mockMvc.perform(put("/api/admin/bookings/" + bookingId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CANCELLED\"}"))
                .andExpect(status().isOk());

        assertThat(count("search", flightNo, "status", "Cancelled")).as("Cancelled after the cancel")
                .isEqualTo(1);
        assertThat(count("search", flightNo, "status", "Confirmed")).as("no longer Confirmed").isZero();
    }

    /**
     * {@code ticketStatus} is the ticket row's own column — the state the seeder
     * writes for a refunded booking's ticket, so it is producible and worth a filter
     * of its own on a <i>ticket</i> endpoint.
     */
    @Test
    void theTicketStatusFilterFindsTheTicketsOwnState() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8);
        int bookingId = book(fixture, flightNo, 1);
        settle(bookingId, "esewa", "SUCCESS");

        assertThat(count("search", flightNo, "ticketStatus", "ISSUED")).as("freshly issued").isEqualTo(1);
        assertThat(count("search", flightNo, "ticketStatus", "issued")).as("any case").isEqualTo(1);
        assertThat(count("search", flightNo, "ticketStatus", "CANCELLED")).as("not voided").isZero();
        assertThat(count("search", flightNo, "ticketStatus", "ALL")).as("ALL = no filter").isEqualTo(1);

        // The shape a refunded booking's ticket has (SeedService writes it that way);
        // the payment path deliberately does not void tickets, so it is arranged here
        // to prove the filter reads the column rather than the booking's status.
        Ticket ticket = ticketOf(bookingId);
        ticket.setStatus("CANCELLED");
        tickets.save(ticket);
        refresh();

        assertThat(count("search", flightNo, "ticketStatus", "CANCELLED")).as("now voided").isEqualTo(1);
        assertThat(count("search", flightNo, "ticketStatus", "ISSUED")).as("no longer issued").isZero();
        // The booking is still confirmed: the two filters are different columns.
        assertThat(count("search", flightNo, "status", "Confirmed"))
                .as("the booking status is untouched by the ticket's").isEqualTo(1);
    }

    /* ================================================================== *
     *  scope: a list of documents, not of bookings                        *
     * ================================================================== */

    /**
     * A booking that never reached the gateway has no ticket, so it is not a ticket —
     * the page's own {@code tickets()} filter says the same thing, and this is the
     * query doing it in the database rather than in the browser.
     */
    @Test
    void aBookingWithNoTicketIsNotInTheList() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8);
        int bookingId = book(fixture, flightNo, 1); // created, never paid

        assertThat(count("search", flightNo)).as("draft bookings carry no document").isZero();

        // Its detail read is a 404 with the project's error shape, not an empty record.
        mockMvc.perform(get("/api/admin/tickets/" + bookingId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TICKET_NOT_FOUND"));
    }

    /* ================================================================== *
     *  detail + paging                                                    *
     * ================================================================== */

    /** The detail read answers the same record the list row holds. */
    @Test
    void theDetailReadReturnsTheSameRecord() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8);
        int bookingId = book(fixture, flightNo, 2);
        settle(bookingId, "esewa", "SUCCESS");

        Ticket ticket = ticketOf(bookingId);

        String body = mockMvc.perform(get("/api/admin/tickets/" + bookingId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(String.valueOf(bookingId)))
                .andExpect(jsonPath("$.pnr").value(ticket.getPnr()))
                .andExpect(jsonPath("$.ticketNo").value(ticket.getTicketNo()))
                .andExpect(jsonPath("$.status").value("Confirmed"))
                .andExpect(jsonPath("$.passengers.length()").value(2))
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(body).get("flight").get("flightNo").asText()).isEqualTo(flightNo);

        // An unknown booking is a 404, not a 500 or an empty body.
        mockMvc.perform(get("/api/admin/tickets/999999999")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("TICKET_NOT_FOUND"));
    }

    /**
     * Paging is opt-in: no {@code size} means the whole list (the shape
     * {@code admin-tickets.js} consumes today), and a {@code size} adds the counts a
     * client needs to walk the rest.
     */
    @Test
    void theListIsUnpagedUntilASizeIsAskedFor() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8);
        int first = book(fixture, flightNo, 1);
        int second = book(fixture, flightNo, 1);
        settle(first, "esewa", "SUCCESS");
        settle(second, "esewa", "SUCCESS");

        JsonNode unpaged = list("search", flightNo);
        assertThat(unpaged.get("bookings").size()).as("both tickets").isEqualTo(2);
        assertThat(unpaged.has("totalElements")).as("the mock's shape carries no paging keys").isFalse();
        assertThat(unpaged.has("page")).isFalse();

        JsonNode paged = list("search", flightNo, "size", "1", "page", "0");
        assertThat(paged.get("bookings").size()).as("one row per page").isEqualTo(1);
        assertThat(paged.get("totalElements").asLong()).isEqualTo(2);
        assertThat(paged.get("totalPages").asInt()).isEqualTo(2);
        assertThat(paged.get("page").asInt()).isZero();
        assertThat(paged.get("size").asInt()).isEqualTo(1);

        // Page two is the other ticket, and paging is stable because the order is.
        JsonNode secondPage = list("search", flightNo, "size", "1", "page", "1");
        assertThat(secondPage.get("bookings").size()).isEqualTo(1);
        assertThat(secondPage.get("bookings").get(0).get("id").asText())
                .isNotEqualTo(paged.get("bookings").get(0).get("id").asText());
    }

    /** A whitelisted sort is accepted; anything else falls back rather than reaching SQL (R6). */
    @Test
    void theSortAcceptsAWhitelistedKeyAndFallsBackForAnythingElse() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8);
        int bookingId = book(fixture, flightNo, 1);
        settle(bookingId, "esewa", "SUCCESS");

        for (String sort : List.of("id", "pnr", "ticket", "issued", "status", "not-a-column", "")) {
            assertThat(count("search", flightNo, "sort", sort))
                    .as("sort=\"" + sort + "\" must still answer the row, never 500")
                    .isEqualTo(1);
        }
    }

    /* ================================================================== *
     *  authorization                                                      *
     * ================================================================== */

    /** Both halves of the admin guard, on both routes. */
    @Test
    void onlyAnAdminReachesTheTickets() throws Exception {
        mockMvc.perform(get("/api/admin/tickets")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/tickets/1")).andExpect(status().isUnauthorized());

        String userToken = jwtUtil.generate(2, "Anju Karki", "anju.karki@example.com", USER);
        mockMvc.perform(get("/api/admin/tickets").header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/tickets/1").header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isForbidden());
    }

    /* ================================================================== *
     *  helpers                                                            *
     * ================================================================== */

    /** The tickets list with the given query parameters, as parsed JSON. */
    private JsonNode list(String... pairs) throws Exception {
        var request = get("/api/admin/tickets")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()));
        for (int index = 0; index + 1 < pairs.length; index += 2) {
            request = request.param(pairs[index], pairs[index + 1]);
        }

        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body);
    }

    /** How many tickets the list answers for the given query parameters. */
    private int count(String... pairs) throws Exception {
        return list(pairs).get("bookings").size();
    }

    /** The single row the flight number identifies — the run's own ticket. */
    private JsonNode only(String flightNo) throws Exception {
        JsonNode rows = list("search", flightNo).get("bookings");
        assertThat(rows.size()).as("exactly the run's own ticket").isEqualTo(1);
        return rows.get(0);
    }

    private Ticket ticketOf(int bookingId) {
        return tickets.findByBookingId(bookingId).orElseThrow();
    }

    /** Initiate, then verify — the whole gateway walk, minting the ticket. */
    private void settle(int bookingId, String method, String outcome) throws Exception {
        mockMvc.perform(post("/api/payments/initiate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"bookingId":%d,"method":"%s","amount":null,"promoCode":"YATRA10"}
                                """.formatted(bookingId, method)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/payments/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"txnId":"%s","bookingId":%d,"method":"%s","amount":1,"productAmount":1,
                                 "customerName":"Ignored","outcome":"%s",
                                 "booking":{"contact":{"firstName":"Re-sent by the mock page"},"passengers":[]}}
                                """.formatted(txnId(), bookingId, method, outcome)))
                .andExpect(status().isOk());

        refresh();
    }

    /** Flushes and drops the persistence context, so the next read comes from the database. */
    private void refresh() {
        entityManager.flush();
        entityManager.clear();
    }

    /** A transaction id in the mock page's own shape: {@code "9A" + 8 digits}. */
    private static String txnId() {
        return "9A" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
    }

    /* ------------------------------------------------------------------ *
     *  fixtures — the same devices PaymentApiTest uses, for the same reason
     * ------------------------------------------------------------------ */

    private record Fixture(String tag, String flightNo, Airline airline, Destination from, Destination to) {

        String contactName() {
            return "Ms Sita" + tag + " Rai";
        }

        String contactEmail() {
            return "sita-" + tag.toLowerCase() + "@example.com";
        }

        /** 10 digits, unique per run, in the roster's stored form. */
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

    private String uniqueFlightNo() {
        for (int attempt = 0; attempt < 50; attempt++) {
            String candidate = "T7 " + String.format("%04d",
                    Math.abs(UUID.randomUUID().hashCode()) % 10_000);
            if (flights.findByFlightNo(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not find an unused flight number");
    }

    private Destination seedDestination(String tag) {
        Destination destination = new Destination();
        destination.setCity("City " + tag);
        destination.setCode(uniqueAirportCode());
        destination.setAirport(tag + " Airport");
        destination.setStatus("Active");
        return destinations.save(destination);
    }

    /** Creates a flight through the real admin endpoint (which also builds its seat map). */
    private String createFlight(Fixture fixture, int capacity) throws Exception {
        String no = fixture.flightNo();

        mockMvc.perform(post("/api/admin/flights")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"no":"%s","airlineId":%d,"from":"%s","to":"%s",
                                 "dep":"06:50","arr":"07:35","aircraft":"ATR 72","fare":8299.99,"seats":%d,"status":"Active"}
                                """.formatted(no, fixture.airline().getId(), fixture.from().getCode(),
                                fixture.to().getCode(), capacity)))
                .andExpect(status().isOk());

        return no;
    }

    /**
     * Creates a booking through the public endpoint and returns its id.
     *
     * <p>The passenger is named after the contact here, which every other test wants and
     * the passenger-search test does not — it needs a name the contact does not carry, or
     * its assertion would be satisfied by the contact clause alone. Hence the overload.
     */
    private int book(Fixture fixture, String flightNo, int passengerCount) throws Exception {
        return book(fixture, flightNo, passengerCount, "Sita" + fixture.tag());
    }

    private int book(Fixture fixture, String flightNo, int passengerCount,
                     String passengerFirst) throws Exception {
        List<String> rows = new ArrayList<>();
        for (int index = 0; index < passengerCount; index++) {
            rows.add("""
                    {"title":"Ms","firstName":"%s","middleName":"","lastName":"Rai","nationality":"Nepali","type":"ADT"}
                    """.formatted(passengerFirst));
        }

        String body = mockMvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contact":{"title":"Ms","firstName":"Sita%s","middleName":"","lastName":"Rai",
                                 "email":"%s","phone":"%s","invoiceParty":"Self","panNo":"","isPassenger":true},
                                 "passengers":[%s],
                                 "flight":{"from":"%s","to":"%s","date":"%s","depart":"06:50","arrive":"07:35","flightNo":"%s",
                                 "airline":"Air","flightClass":"E Class","refundable":true,"pricePerPassenger":8299.99,
                                 "passengerCount":%d,"totalPrice":16599.98},
                                 "amount":1}
                                """.formatted(fixture.tag(), fixture.contactEmail(), fixture.contactPhone(),
                                String.join(",", rows), fixture.from().getCode(), fixture.to().getCode(),
                                LocalDate.now(), flightNo, passengerCount)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).get("bookingId").asInt();
    }

    private String adminToken() {
        return jwtUtil.generate(1, "Dikshya Ghising", "admin@gmail.com", ADMIN);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String tag() {
        return "T" + UUID.randomUUID().toString().substring(0, 8);
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
