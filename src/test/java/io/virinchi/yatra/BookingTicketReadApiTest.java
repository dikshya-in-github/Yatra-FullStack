package io.virinchi.yatra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virinchi.yatra.Model.Airline;
import io.virinchi.yatra.Model.Booking;
import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Model.User;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /api/bookings/{id}} — the read behind {@code eticket.html}, and the
 * authorization rule that had to be decided with it.
 *
 * <h2>What this endpoint is for</h2>
 *
 * <p>The e-ticket was the last screen in the storefront reading from nowhere: it
 * rendered {@code sessionStorage} and <b>derived</b> its PNR and ticket number from
 * the transaction id, falling back to {@code "DEMO" + Date.now()} — so the two
 * numbers that make a printed ticket a ticket were invented in the browser. This is
 * the read that replaces them, and the assertions below are about the facts that
 * makes true: the identifiers are the {@code tickets} row's, the money is the
 * booking's, and a booking that has not settled says so instead of printing.
 *
 * <h2>Why the refusal is asserted as carefully as the success</h2>
 *
 * <p>Booking ids are sequential, so an unguarded read by id is a walkable ledger of
 * every customer's contact details, PNR and passengers. These tests pin both halves:
 * a tokenless caller gets 401, and a signed-in caller asking for <i>someone else's</i>
 * booking gets the same 404, with the same code, as an id that does not exist — no
 * 403, because a 403 confirms the id is real and belongs to another account, which is
 * exactly what a probe of a sequential id space is looking for.
 *
 * <p>DB-backed and {@code @Transactional} like the rest of the suite: every row here
 * is built from a per-run tag and nothing survives the run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BookingTicketReadApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AirlineRepository airlines;
    @Autowired private DestinationRepository destinations;
    @Autowired private FlightRepository flights;
    @Autowired private BookingRepository bookings;
    @Autowired private UserRepository users;
    @Autowired private JwtUtil jwtUtil;

    private final ObjectMapper mapper = new ObjectMapper();

    /* ------------------------------------------------------------------ *
     *  the document                                                      *
     * ------------------------------------------------------------------ */

    /**
     * The owner's settled booking: the PNR and ticket number are the ones the
     * checkout minted (asserted against the verify response <i>and</i> against the
     * stored row, so neither the page's old derivation nor a fresh invention can
     * satisfy it), the money is the booking's, and the flight and passenger are the
     * fixture's rather than anything the browser kept.
     */
    @Test
    void theOwnersTicketCarriesTheIdentifiersTheCheckoutMinted() throws Exception {
        Fixture fixture = fixture();
        User owner = seedUser();
        String no = createFlight(fixture, 46, "8299.99");
        int bookingId = bookSignedIn(fixture, no, owner);

        JsonNode settled = settle(bookingId);
        String pnr = settled.get("pnr").asText();
        String ticketNo = settled.get("ticketNo").asText();
        assertThat(pnr).as("the checkout mints both identifiers").isNotBlank();
        assertThat(ticketNo).isNotBlank();

        String json = mockMvc.perform(get("/api/bookings/" + bookingId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = mapper.readTree(json);

        assertThat(body.get("pnr").asText()).isEqualTo(pnr);
        assertThat(body.get("ticketNo").asText()).isEqualTo(ticketNo);
        assertThat(body.get("ticketStatus").asText()).isEqualTo("ISSUED");
        assertThat(body.get("issuedAt").asText()).isNotBlank();
        /* The removed fallback, asserted as gone: the page used to fill a missing
           transaction id with "DEMO" + Date.now() and derive the PNR from it, so the
           identifiers on a printed ticket were the browser's invention. */
        assertThat(json).doesNotContain("DEMO");

        assertThat(body.get("status").asText()).isEqualTo("CONFIRMED");
        assertThat(body.get("paymentStatus").asText()).isEqualTo("Paid");
        assertThat(body.get("amount").decimalValue()).isEqualByComparingTo("8299.99");
        assertThat(body.get("amount").decimalValue())
                .isEqualByComparingTo(bookings.findById(bookingId).orElseThrow().getTotalAmount());

        /* How it was paid — the page prints all three. */
        assertThat(body.get("method").asText()).isEqualTo("eSewa");
        assertThat(body.get("txnId").asText()).isNotBlank();
        assertThat(body.get("paidAt").asText()).isNotBlank();

        /* The flight, named from its own rows. */
        assertThat(body.get("flightNo").asText()).isEqualTo(no);
        assertThat(body.get("fromCode").asText()).isEqualTo(fixture.from().getCode());
        assertThat(body.get("fromCity").asText()).isEqualTo(fixture.from().getCity());
        assertThat(body.get("toCode").asText()).isEqualTo(fixture.to().getCode());
        assertThat(body.get("toCity").asText()).isEqualTo(fixture.to().getCity());
        assertThat(body.get("depart").asText()).startsWith("06:50");
        assertThat(body.get("arrive").asText()).startsWith("07:35");
        assertThat(body.get("durationMinutes").asInt()).isEqualTo(45);
        assertThat(body.get("airlineName").asText()).isEqualTo(fixture.airline().getName());
        assertThat(body.get("airlineCode").asText()).isEqualTo(fixture.airline().getIata());

        /* The passenger the ticket prints. */
        assertThat(body.get("passengers")).hasSize(1);
        assertThat(body.get("passengers").get(0).get("name").asText()).isEqualTo("Hari Sharma");
    }

    /**
     * A booking nobody has paid for yet is <b>readable and honest</b>: the owner gets
     * a 200 with no identifiers rather than a ticket made up for the occasion. This is
     * the branch {@code eticket.js} renders as "Not ticketed yet" — reachable because
     * the gateway callback can lag the customer's own redirect, and worth pinning so a
     * later pass cannot answer an unissued booking with a placeholder PNR.
     */
    @Test
    void anUnsettledBookingIsReadableAndHasNoIdentifiers() throws Exception {
        Fixture fixture = fixture();
        User owner = seedUser();
        int bookingId = bookSignedIn(fixture, createFlight(fixture, 46, "8299.99"), owner);

        JsonNode body = bodyOf(bookingId, owner);

        assertThat(body.get("status").asText()).isEqualTo("PENDING");
        assertThat(body.get("pnr").asText()).isEmpty();
        assertThat(body.get("ticketNo").asText()).isEmpty();
        assertThat(body.get("ticketStatus").asText()).isEmpty();
        assertThat(body.get("issuedAt").isNull()).as("no ticket means no issue time").isTrue();
        assertThat(body.get("method").asText()).isEmpty();
        assertThat(body.get("txnId").asText()).isEmpty();
        /* The booking itself is still fully described — the page needs the route and
           the amount to explain what is waiting. */
        assertThat(body.get("amount").decimalValue()).isEqualByComparingTo("8299.99");
        assertThat(body.get("flightNo").asText()).isNotBlank();
        assertThat(body.get("passengers")).hasSize(1);
    }

    /* ------------------------------------------------------------------ *
     *  who may read it                                                   *
     * ------------------------------------------------------------------ */

    /**
     * <b>The rule the route exists under.</b> A signed-in customer asking for an id
     * that is not theirs is answered exactly as an id that does not exist, and the
     * body carries nothing from the other customer's booking — not the PNR, not the
     * email, not the amount.
     */
    @Test
    void someoneElsesBookingIsAnsweredAsNoSuchBooking() throws Exception {
        Fixture fixture = fixture();
        User owner = seedUser();
        User stranger = seedUser();
        int bookingId = bookSignedIn(fixture, createFlight(fixture, 46, "8299.99"), owner);
        settle(bookingId);

        String refused = mockMvc.perform(get("/api/bookings/" + bookingId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("BOOKING_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();

        assertThat(refused).doesNotContain("Hari").doesNotContain("8299.99");
        assertThat(refused).doesNotContain(bookings.findById(bookingId).orElseThrow().getContactEmail());

        /* And an id that plainly does not exist answers the same way, which is the
           point: the two refusals are indistinguishable. */
        String missing = mockMvc.perform(get("/api/bookings/" + (bookingId + 900000))
                        .header(HttpHeaders.AUTHORIZATION, bearer(stranger)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("BOOKING_NOT_FOUND"))
                .andReturn().getResponse().getContentAsString();

        /* The two refusals are the same answer: same status, same code, and a body that
           differs only in the id it names. Asserted by replacing the ids rather than by
           comparing the strings, which would fail on the id alone. */
        assertThat(refused.replace(String.valueOf(bookingId), "ID"))
                .as("an id that exists and is someone else's answers exactly as a missing one")
                .isEqualTo(missing.replace(String.valueOf(bookingId + 900000), "ID"));
    }

    /** No token at all: the read is authenticated, so a permitted-but-anonymous call cannot reach it. */
    @Test
    void theReadNeedsASession() throws Exception {
        Fixture fixture = fixture();
        User owner = seedUser();
        int bookingId = bookSignedIn(fixture, createFlight(fixture, 46, "8299.99"), owner);
        settle(bookingId);

        mockMvc.perform(get("/api/bookings/" + bookingId))
                .andExpect(status().isUnauthorized());

        /* A token that is not a token is refused for the same reason. */
        mockMvc.perform(get("/api/bookings/" + bookingId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    /**
     * A guest booking has no owner ({@code Booking.user} is null), so it is readable by
     * nobody through this route — including a signed-in customer who happened to hold
     * it. The storefront requires sign-in before the wizard, so this is the API's
     * guest path (a tokenless {@code POST /api/bookings}) rather than a customer's.
     */
    @Test
    void aGuestBookingBelongsToNobody() throws Exception {
        Fixture fixture = fixture();
        User customer = seedUser();
        String no = createFlight(fixture, 46, "8299.99");

        String json = mockMvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(fixture, no)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        int bookingId = mapper.readTree(json).get("bookingId").asInt();

        Booking stored = bookings.findById(bookingId).orElseThrow();
        assertThat(stored.getUser()).as("booked without a token, so owned by nobody").isNull();

        mockMvc.perform(get("/api/bookings/" + bookingId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(customer)))
                .andExpect(status().isNotFound());
    }

    /* ------------------------------------------------------------------ *
     *  helpers                                                           *
     * ------------------------------------------------------------------ */

    /** A signed-in booking, which is what the wizard makes: the owner rides along with the request. */
    private int bookSignedIn(Fixture fixture, String flightNo, User owner) throws Exception {
        String json = mockMvc.perform(post("/api/bookings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(fixture, flightNo)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int bookingId = mapper.readTree(json).get("bookingId").asInt();
        assertThat(bookings.findById(bookingId).orElseThrow().getUser().getId())
                .as("the booking is attributed to the caller, not left ownerless")
                .isEqualTo(owner.getId());
        return bookingId;
    }

    /** Initiate, then the gateway's own callback: SUCCESS settles the booking and issues the ticket. */
    private JsonNode settle(int bookingId) throws Exception {
        mockMvc.perform(post("/api/payments/initiate").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":%d,\"method\":\"eSewa\"}".formatted(bookingId)))
                .andExpect(status().isOk());

        String json = mockMvc.perform(post("/api/payments/verify").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"txnId":"9A%08d","bookingId":%d,"method":"eSewa","outcome":"SUCCESS"}
                                """.formatted(bookingId % 100000000, bookingId)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return mapper.readTree(json);
    }

    private JsonNode bodyOf(int bookingId, User as) throws Exception {
        String json = mockMvc.perform(get("/api/bookings/" + bookingId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(as)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(json);
    }

    private String bearer(User user) {
        return "Bearer " + jwtUtil.generate(user.getId(), user.getName(), user.getEmail(), user.getRole());
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
    private String createFlight(Fixture fixture, int capacity, String fare) throws Exception {
        String no = flightNo();
        mockMvc.perform(post("/api/admin/flights")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"no":"%s","airlineId":%d,"from":"%s","to":"%s","date":"%s",
                                 "dep":"06:50","arr":"07:35","aircraft":"ATR 72","fare":%s,
                                 "seats":%d,"status":"Active"}
                                """.formatted(no, fixture.airline().getId(), fixture.from().getCode(),
                                fixture.to().getCode(), LocalDate.now(), fare, capacity)))
                .andExpect(status().isOk());
        return no;
    }

    /** The wizard's payload as {@code booking.js} sends it — contact, one passenger, the selected flight. */
    private static String bookingBody(Fixture fixture, String flightNo) {
        return """
                {"contact":{"title":"Mr","firstName":"Hari","lastName":"Sharma",
                 "email":"hari@example.com","phone":"+977 9812345678","invoiceParty":"Self","panNo":"","isPassenger":true},
                 "passengers":[{"title":"Mr","firstName":"Hari","lastName":"Sharma","nationality":"Nepal","type":"ADT"}],
                 "flight":{"from":"%s","to":"%s","date":"%s","depart":"06:50","arrive":"07:35","flightNo":"%s",
                 "airline":"Air","flightClass":"E Class","refundable":false,"pricePerPassenger":8299.99,
                 "passengerCount":1,"totalPrice":8299.99},
                 "amount":8299.99}
                """.formatted(fixture.from().getCode(), fixture.to().getCode(), LocalDate.now(), flightNo);
    }

    private String adminToken() {
        return jwtUtil.generate(1, "Dikshya Ghising", "admin@gmail.com", "ADMIN");
    }

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
