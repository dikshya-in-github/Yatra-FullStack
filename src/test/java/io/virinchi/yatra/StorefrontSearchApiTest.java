package io.virinchi.yatra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.virinchi.yatra.Model.Airline;
import io.virinchi.yatra.Model.Booking;
import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Model.FareClass;
import io.virinchi.yatra.Model.Flight;
import io.virinchi.yatra.Repository.AirlineRepository;
import io.virinchi.yatra.Repository.BookingRepository;
import io.virinchi.yatra.Repository.DestinationRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import io.virinchi.yatra.Security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fix-plan §10 — the storefront's search read, and the one rule that had to move
 * with it: <b>the price a page quotes is the price the server charges</b>.
 *
 * <p>DB-backed and {@code @Transactional} like {@code BookingApiTest}, so nothing
 * here survives the run and each test builds its own airline, airports and flights
 * from a per-run tag.
 *
 * <h2>Why these assertions and not "the search returns flights"</h2>
 *
 * <p>A test that a route with flights comes back with those flights would have
 * passed against the mock's shape too, which is exactly what it must not do: the
 * defect §10 fixed was not the shape, it was that the flights were <i>invented</i>
 * — numbers derived from the date, which {@code POST /api/bookings} then resolved by
 * number. So the assertions are about the facts the page depends on: that the rows
 * are this database's ({@code id}, the flight's own times and fare), that an
 * out-of-service flight is not sellable, that the seat count is live, and that the
 * class pill's price is the number the booking later stores.
 *
 * <p>The last one is the reason the fare classes moved into
 * {@link FareClass}. With the page on the mock the two halves could not disagree —
 * the mock priced its own total. With the page on this API, an A Class pill reading
 * base + 4,000 and a {@code booking} row storing base is a quote and a charge that
 * differ by up to 5,000 per passenger, and the figure that eventually reaches the
 * gateway signature would be the wrong one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class StorefrontSearchApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private AirlineRepository airlines;
    @Autowired private DestinationRepository destinations;
    @Autowired private FlightRepository flights;
    @Autowired private BookingRepository bookings;
    @Autowired private JwtUtil jwtUtil;

    private final ObjectMapper mapper = new ObjectMapper();

    /* ------------------------------------------------------------------ *
     *  the read                                                          *
     * ------------------------------------------------------------------ */

    /**
     * The route's flights, ordered by departure, named from the destination rows —
     * and answered <b>without a token</b>, which is the permit R8 recorded when the
     * {@code /api/flights/**} GET rule went in a phase early. Every request in this
     * class is tokenless for that reason: a 401 here would have looked like "no
     * flights found" on the storefront.
     */
    @Test
    void theRouteComesBackInDepartureOrderWithItsOwnNames() throws Exception {
        Fixture fixture = fixture();
        String later = createFlight(fixture, "T7 002", 46, "8999.99", LocalDate.now(), "13:05", "13:55");
        String earlier = createFlight(fixture, "T7 001", 46, "8299.99", LocalDate.now(), "07:20", "08:05");

        JsonNode body = search(fixture, fixture.from().getCode(), fixture.to().getCode(), LocalDate.now());

        assertThat(body.get("flights")).hasSize(2);
        assertThat(body.get("flights").get(0).get("flightNo").asText()).isEqualTo(earlier);
        assertThat(body.get("flights").get(1).get("flightNo").asText()).isEqualTo(later);

        /* The endpoint block: the page prints `label` in its results heading and the
           city names on every card, so both come from the destination row rather than
           from the code the browser sent. */
        assertThat(body.get("origin").get("code").asText()).isEqualTo(fixture.from().getCode());
        assertThat(body.get("origin").get("city").asText()).isEqualTo(fixture.from().getCity());
        assertThat(body.get("origin").get("label").asText())
                .isEqualTo(fixture.from().getCity().toUpperCase());
        assertThat(body.get("destination").get("code").asText()).isEqualTo(fixture.to().getCode());

        /* `id` rides along because the seat map is keyed by it, and the row is the one
           this database holds — the fact the mock could never satisfy. */
        Flight row = flights.findByFlightNo(earlier).orElseThrow();
        assertThat(body.get("flights").get(0).get("id").asInt()).isEqualTo(row.getId());
        assertThat(body.get("flights").get(0).get("depart").asText()).isEqualTo("07:20");
        assertThat(body.get("flights").get(0).get("arrive").asText()).isEqualTo("08:05");
        assertThat(body.get("flights").get(0).get("durationMinutes").asLong()).isEqualTo(45);
        assertThat(body.get("flights").get(0).get("seatsAvailable").asInt()).isEqualTo(46);
    }

    /**
     * An {@code Inactive} flight is one the admin has taken out of service, so the
     * storefront must not sell it. Its absence is asserted against a flight that
     * <i>is</i> listed on the same route and day, so the test cannot pass by the
     * search returning nothing.
     */
    @Test
    void anInactiveFlightIsNotSellable() throws Exception {
        Fixture fixture = fixture();
        String sellable = createFlight(fixture, "T7 011", 46, "8299.99", LocalDate.now(), "07:20", "08:05");
        createFlight(fixture, "T7 012", 46, "8299.99", LocalDate.now(), "09:00", "09:45", "Inactive");

        JsonNode body = search(fixture, fixture.from().getCode(), fixture.to().getCode(), LocalDate.now());

        assertThat(body.get("flights")).hasSize(1);
        assertThat(body.get("flights").get(0).get("flightNo").asText()).isEqualTo(sellable);
    }

    /**
     * Six classes, priced from the flight's own fare, in the order the pill row draws
     * them — and the lowest real fare on the day is the only one badged.
     *
     * <p>The response deliberately has <b>no</b> {@code comparePrice}: the mock's
     * strike-through was {@code base + 177}, a discount nobody was ever charged. The
     * absence is asserted, so a later pass cannot quietly put an invented number back
     * on the card.
     */
    @Test
    void everyClassIsPricedFromTheFlightsOwnFare() throws Exception {
        Fixture fixture = fixture();
        createFlight(fixture, "T7 021", 46, "8299.99", LocalDate.now(), "07:20", "08:05");
        createFlight(fixture, "T7 022", 70, "11499.99", LocalDate.now(), "16:40", "17:25");

        JsonNode body = search(fixture, fixture.from().getCode(), fixture.to().getCode(), LocalDate.now());
        JsonNode cheap = body.get("flights").get(0);
        JsonNode pricey = body.get("flights").get(1);

        assertThat(cheap.get("baseFare").decimalValue()).isEqualByComparingTo("8299.99");
        assertThat(cheap.get("isLowest").asBoolean()).isTrue();
        assertThat(pricey.get("isLowest").asBoolean()).isFalse();
        assertThat(cheap.has("comparePrice")).isFalse();

        JsonNode options = cheap.get("fareOptions");
        assertThat(options).hasSize(FareClass.values().length);
        assertThat(options.get(0).get("label").asText()).isEqualTo("E Class");
        assertThat(options.get(0).get("price").decimalValue()).isEqualByComparingTo("8299.99");
        assertThat(options.get(0).get("refundable").asBoolean()).isFalse();
        assertThat(options.get(5).get("label").asText()).isEqualTo("Y Class");
        assertThat(options.get(5).get("price").decimalValue()).isEqualByComparingTo("13299.99");
        assertThat(options.get(5).get("refundable").asBoolean()).isTrue();

        /* The policy copy the card's modal prints, which the page reads from here. */
        assertThat(body.get("farePolicies").get("nonRefundable")).isNotEmpty();
        assertThat(body.get("farePolicies").get("refundable")).isNotEmpty();
    }

    /**
     * The seat count is read from the {@code seat} table at request time, so booking a
     * seat on that flight moves it — the teacher-flagged availability rule seen from
     * the storefront rather than from the admin table.
     */
    @Test
    void seatsAvailableFallsWhenASeatIsBooked() throws Exception {
        Fixture fixture = fixture();
        String no = createFlight(fixture, "T7 031", 4, "8299.99", LocalDate.now(), "07:20", "08:05");

        assertThat(search(fixture, fixture.from().getCode(), fixture.to().getCode(), LocalDate.now())
                .get("flights").get(0).get("seatsAvailable").asInt()).isEqualTo(4);

        mockMvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(no, fixture, "1", "E Class", false, "1A")))
                .andExpect(status().isOk());

        assertThat(search(fixture, fixture.from().getCode(), fixture.to().getCode(), LocalDate.now())
                .get("flights").get(0).get("seatsAvailable").asInt()).isEqualTo(3);
    }

    /* ------------------------------------------------------------------ *
     *  the empty answers                                                 *
     * ------------------------------------------------------------------ */

    /**
     * A day with nothing on it, and a route to itself, are both <b>200 with an empty
     * {@code flights} array</b> — not a 404. The page has an empty state for exactly
     * this; an error dialog for a search the customer can fix by changing the date
     * would be a worse answer, and {@code origin}/{@code destination} still come back
     * so the heading can name the route that had nothing.
     */
    @Test
    void anEmptyAnswerIsAnEmptyListNotAnError() throws Exception {
        Fixture fixture = fixture();
        createFlight(fixture, "T7 041", 46, "8299.99", LocalDate.now(), "07:20", "08:05");

        JsonNode otherDay = search(fixture, fixture.from().getCode(), fixture.to().getCode(),
                LocalDate.now().plusDays(30));
        assertThat(otherDay.get("flights")).isEmpty();
        assertThat(otherDay.get("origin").get("city").asText()).isEqualTo(fixture.from().getCity());

        JsonNode sameCode = search(fixture, fixture.from().getCode(), fixture.from().getCode(),
                LocalDate.now());
        assertThat(sameCode.get("flights")).isEmpty();

        /* An airport code that is not a destination at all is an empty answer too, and
           still titles honestly (the code stands in for the city). */
        JsonNode unknown = search(fixture, "ZZZ", "ZZY", LocalDate.now());
        assertThat(unknown.get("flights")).isEmpty();
        assertThat(unknown.get("origin").get("label").asText()).isEqualTo("ZZZ");
    }

    /* ------------------------------------------------------------------ *
     *  the quote is the charge                                           *
     * ------------------------------------------------------------------ */

    /**
     * <b>The rule §10 could not leave behind.</b> The price the search quotes on a
     * class pill is the amount the booking stores — read from the response rather than
     * written into the test, so the two cannot drift: change {@code FareClass}'s delta
     * and this test follows it, change only {@code BookingService}'s pricing and it
     * fails.
     *
     * <p>The request also claims {@code refundable: false} for a class whose rule is
     * refundable, so the stored flag proves it comes from the class rather than from
     * the browser's copy of it.
     */
    @Test
    void thePillPriceTheSearchQuotesIsTheAmountTheBookingStores() throws Exception {
        Fixture fixture = fixture();
        String no = createFlight(fixture, "T7 051", 46, "8299.99", LocalDate.now(), "07:20", "08:05");

        BigDecimal pill = search(fixture, fixture.from().getCode(), fixture.to().getCode(), LocalDate.now())
                .get("flights").get(0).get("fareOptions").get(5).get("price").decimalValue();
        assertThat(pill).isEqualByComparingTo("13299.99");

        mockMvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(no, fixture, "1", "Y Class", false, "1A")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(pill.doubleValue()));

        Booking stored = bookings.findByFlightId(flights.findByFlightNo(no).orElseThrow().getId())
                .stream().findFirst().orElseThrow();
        assertThat(stored.getTotalAmount()).isEqualByComparingTo(pill);
        assertThat(stored.getFareClass()).isEqualTo("Y Class");
        assertThat(stored.isRefundable()).as("the class's rule, not the request's claim").isTrue();
    }

    /**
     * A class this server does not sell is a 400, not a silent fall back to the base
     * fare: the caller asked for a price that does not exist, and answering with a
     * different one would put a figure on the booking that nobody quoted.
     */
    @Test
    void anUnknownFareClassIsRefused() throws Exception {
        Fixture fixture = fixture();
        String no = createFlight(fixture, "T7 061", 46, "8299.99", LocalDate.now(), "07:20", "08:05");

        mockMvc.perform(post("/api/bookings").contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(no, fixture, "1", "Z Class", true, "1A")))
                .andExpect(status().isBadRequest());

        assertThat(bookings.findByFlightId(flights.findByFlightNo(no).orElseThrow().getId())).isEmpty();
    }

    /* ------------------------------------------------------------------ *
     *  helpers                                                           *
     * ------------------------------------------------------------------ */

    private JsonNode search(Fixture fixture, String origin, String destination, LocalDate date)
            throws Exception {
        String json = mockMvc.perform(get("/api/flights/search")
                        .param("origin", origin)
                        .param("destination", destination)
                        .param("date", date.toString())
                        .param("passengers", "2"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return mapper.readTree(json);
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

    /**
     * Creates a flight through the real admin endpoint and returns its number.
     *
     * <p>Both times are parameters, and both are always sent, because the API refuses
     * an arrival that is not after its departure ("same-day domestic flights"). The
     * first draft of this helper passed one fixed arrival for every departure and got
     * three 400s back — which is the endpoint working, not the test being unlucky.
     */
    private String createFlight(Fixture fixture, String no, int capacity, String fare,
                                LocalDate date, String depart, String arrive) {
        return createFlight(fixture, no, capacity, fare, date, depart, arrive, "Active");
    }

    private String createFlight(Fixture fixture, String no, int capacity, String fare,
                                LocalDate date, String depart, String arrive, String status) {
        try {
            mockMvc.perform(post("/api/admin/flights")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"no":"%s","airlineId":%d,"from":"%s","to":"%s","date":"%s",
                                     "dep":"%s","arr":"%s","aircraft":"ATR 72","fare":%s,
                                     "seats":%d,"status":"%s"}
                                    """.formatted(no, fixture.airline().getId(),
                                    fixture.from().getCode(), fixture.to().getCode(), date,
                                    depart, arrive, fare, capacity, status)))
                    .andExpect(status().isOk());
        } catch (Exception ex) {
            throw new IllegalStateException("could not create the fixture flight " + no, ex);
        }
        return no;
    }

    /**
     * The wizard's payload as {@code booking.js} sends it, with the fare class spelled
     * the way the page's pill writes it ({@code "Y Class"}) and a {@code refundable}
     * flag the service is expected to ignore.
     */
    private static String bookingBody(String flightNo, Fixture fixture, String amount,
                                      String fareClass, boolean refundable, String seat) {
        return """
                {"contact":{"title":"Mr","firstName":"Hari","lastName":"Sharma",
                 "email":"hari@example.com","phone":"+977 9812345678","invoiceParty":"Self","panNo":"","isPassenger":true},
                 "passengers":[{"title":"Mr","firstName":"Hari","lastName":"Sharma","nationality":"Nepal","type":"ADT","seatNumber":"%s"}],
                 "flight":{"from":"%s","to":"%s","date":"%s","depart":"07:20","arrive":"08:05","flightNo":"%s",
                 "airline":"Air","flightClass":"%s","refundable":%s,"pricePerPassenger":1,
                 "passengerCount":1,"totalPrice":%s},
                 "amount":%s}
                """.formatted(seat, fixture.from().getCode(), fixture.to().getCode(),
                LocalDate.now(), flightNo, fareClass, refundable, amount, amount);
    }

    private String adminToken() {
        return jwtUtil.generate(1, "Dikshya Ghising", "admin@gmail.com", "ADMIN");
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
