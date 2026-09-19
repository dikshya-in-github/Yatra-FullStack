package io.virinchi.yatra;

import io.virinchi.yatra.Model.Airline;
import io.virinchi.yatra.Model.Booking;
import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Model.Flight;
import io.virinchi.yatra.Model.Seat;
import io.virinchi.yatra.Repository.AirlineRepository;
import io.virinchi.yatra.Repository.BookingRepository;
import io.virinchi.yatra.Repository.DestinationRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import io.virinchi.yatra.Repository.SeatRepository;
import io.virinchi.yatra.Security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The flight module — the second CRUD module, and the first with a generated
 * child table.
 *
 * <p>DB-backed and {@code @Transactional} like {@code AirlineApiTest}, so every
 * row is rolled back and the live database is never modified. Each test builds
 * its own airline, airports and flights from a per-run tag, so the assertions
 * stay valid once Phase 7 seeds real rows.
 *
 * <p>Four tests here are guards for things found by reading the code rather than
 * by running it:
 * <ul>
 *   <li>{@link #aDuplicateFlightNumberIsRefusedEvenInAnotherCase()} — the column
 *       is {@code utf8mb4_bin}, so the unique key alone would accept
 *       {@code "t7 482"} beside {@code "T7 482"};</li>
 *   <li>{@link #capacityCannotShrinkBelowTheBookedSeats()} and
 *       {@link #shrinkingCapacityNeverRemovesASoldSeat()} — capacity is what
 *       "available seats" is computed from, so a shrink can make the flight report
 *       a negative number of free seats, and the database would not object;</li>
 *   <li>{@link #aDatelessFlightIsNotFoundByADateFilter()} — the documented
 *       consequence of the accepted decision to keep the date optional rather than
 *       invent one (the admin form has no date input).</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class FlightApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private FlightRepository flights;
    @Autowired private SeatRepository seats;
    @Autowired private AirlineRepository airlines;
    @Autowired private DestinationRepository destinations;
    @Autowired private BookingRepository bookings;
    @Autowired private JwtUtil jwtUtil;

    /* ------------------------------------------------------------------ *
     *  access control                                                     *
     * ------------------------------------------------------------------ */

    /** Everything in this module is admin-only, unlike the airline reads. */
    @Test
    void adminFlightReadsRequireAnAdminToken() throws Exception {
        mockMvc.perform(get("/api/admin/flights"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("NOT_AUTHENTICATED"));

        String userToken = jwtUtil.generate(2, "A Customer", "customer@example.com", "USER");
        mockMvc.perform(get("/api/admin/flights")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    /**
     * The permit for {@code /api/flights/**} was put in place <i>before</i> the storefront
     * endpoint existed, because the failure it prevents is silent: a 401 on a search reads
     * as "no flights found" rather than as an auth problem, which is exactly how the
     * airline logos lost a phase (risk R8).
     *
     * <p><b>§10 has since built that endpoint, and this assertion moved with it.</b> It
     * asked for a 404 when the path had no handler ("the point is that it is not a 401");
     * the handler exists now, so a tokenless read of a bare route is its own answer —
     * {@code 200} with nothing in {@code flights} — and the property this test exists for
     * is unchanged. No handler is asked for here: a request with no destination and no
     * date is a real customer search with an empty result, which is what the storefront
     * draws its empty state from.
     */
    @Test
    void theSearchIsPermittedRatherThanAnswering401() throws Exception {
        mockMvc.perform(get("/api/flights/search").param("origin", "KTM"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flights").isEmpty());
    }

    /* ------------------------------------------------------------------ *
     *  list contract                                                      *
     * ------------------------------------------------------------------ */

    /**
     * The wrapper {@code admin-flights.js} reads, with the record's own keys — the
     * page formats {@code f.no}, maps {@code f.from}/{@code f.to} through
     * {@code CITIES} and does its own {@code seats − bookedSeats} arithmetic, so
     * any of these renamed means rewriting the page.
     */
    @Test
    void theListIsWrappedInAFlightsKeyWithThePagesOwnKeys() throws Exception {
        Fixture fixture = fixture();
        String no = createFlight(fixture, 70, "Active");

        mockMvc.perform(adminGet("/api/admin/flights").param("search", no))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.flights").isArray())
                .andExpect(jsonPath("$.flights[0].id").exists())
                .andExpect(jsonPath("$.flights[0].no").value(no))
                .andExpect(jsonPath("$.flights[0].airlineId").value(fixture.airline().getId()))
                .andExpect(jsonPath("$.flights[0].from").value(fixture.from().getCode()))
                .andExpect(jsonPath("$.flights[0].to").value(fixture.to().getCode()))
                .andExpect(jsonPath("$.flights[0].dep").value("06:50"))
                .andExpect(jsonPath("$.flights[0].arr").value("07:35"))
                .andExpect(jsonPath("$.flights[0].aircraft").value("ATR 72"))
                .andExpect(jsonPath("$.flights[0].fare").value(8299.99))
                .andExpect(jsonPath("$.flights[0].seats").value(70))
                .andExpect(jsonPath("$.flights[0].bookedSeats").value(0))
                .andExpect(jsonPath("$.flights[0].status").value("Active"))
                // The unpaged body must stay byte-compatible with the mock's shape.
                .andExpect(jsonPath("$.page").doesNotExist())
                .andExpect(jsonPath("$.size").doesNotExist())
                .andExpect(jsonPath("$.totalElements").doesNotExist());
    }

    /* ------------------------------------------------------------------ *
     *  the roadmap checkpoint: seats are generated                        *
     * ------------------------------------------------------------------ */

    /**
     * Roadmap Phase 5's checkpoint, literally: capacity 70 creates 70 seat rows,
     * all AVAILABLE, numbered in rows of six from {@code 1A} to {@code 12D}.
     *
     * <p>The numbering is asserted by set, not list order — a {@code findBy}
     * returns rows in no promised order, and asserting on order would make this
     * test pass or fail on the database's whim.
     */
    @Test
    void creatingAFlightGeneratesOneAvailableSeatPerCapacity() throws Exception {
        Fixture fixture = fixture();
        int flightId = createFlightId(fixture, 70, "Active");

        List<Seat> map = seats.findByFlightId(flightId);
        Set<String> numbers = map.stream().map(Seat::getSeatNumber).collect(Collectors.toSet());

        assertThat(map).as("capacity 70 → 70 seat rows").hasSize(70);
        assertThat(numbers).hasSize(70);
        assertThat(map).allMatch(seat -> "AVAILABLE".equals(seat.getStatus()));
        assertThat(numbers)
                .as("rows of six")
                .contains("1A", "1F", "2A", "12A", "12D");
    }

    /**
     * {@code bookedSeats} is counted from the seat table, not read from a stored
     * total — the teacher-flagged rule. Nothing in the schema even has a column for
     * it, so this proves the count is live: booking a seat changes the response
     * without touching the flight row.
     */
    @Test
    void bookedSeatsIsComputedFromTheSeatMapNotStored() throws Exception {
        Fixture fixture = fixture();
        int flightId = createFlightId(fixture, 8, "Active");

        bookSeat(flightId, "1A");
        bookSeat(flightId, "1B");

        mockMvc.perform(adminGet("/api/admin/flights").param("search", flightNo(flightId)))
                .andExpect(jsonPath("$.flights[0].seats").value(8))
                .andExpect(jsonPath("$.flights[0].bookedSeats").value(2));

        // The capacity figure itself is untouched — available is derived from both.
        assertThat(flights.findById(flightId).orElseThrow().getSeatCapacity()).isEqualTo(8);
    }

    /* ------------------------------------------------------------------ *
     *  the date (optional, never invented)                                *
     * ------------------------------------------------------------------ */

    /** The admin form has no date input, so this is the normal case, not an edge. */
    @Test
    void aFlightCreatedWithoutADateOmitsTheKeyRatherThanDefaultingIt() throws Exception {
        Fixture fixture = fixture();
        String no = flightNo();

        mockMvc.perform(adminPost("/api/admin/flights")
                        .content(flightBody(no, fixture, null, 70, "Active", "06:50", "07:35")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").doesNotExist());

        Flight stored = flights.findByFlightNo(no).orElseThrow();
        assertThat(stored.getFlightDate())
                .as("never defaulted to today — a date the admin never scheduled")
                .isNull();
    }

    @Test
    void aFlightKeepsTheDateItWasGiven() throws Exception {
        Fixture fixture = fixture();
        LocalDate date = LocalDate.of(2026, 10, 5);
        String no = flightNo();

        mockMvc.perform(adminPost("/api/admin/flights")
                        .content(flightBody(no, fixture, date.toString(), 70, "Active", "06:50", "07:35")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.date").value(date.toString()));

        mockMvc.perform(adminGet("/api/admin/flights")
                        .param("date", date.toString()).param("search", no))
                .andExpect(jsonPath("$.flights[0].no").value(no));

        // A different day must not match it.
        mockMvc.perform(adminGet("/api/admin/flights")
                        .param("date", date.plusDays(1).toString()).param("search", no))
                .andExpect(jsonPath("$.flights").isEmpty());
    }

    /**
     * The documented cost of keeping the date optional instead of inventing one: a
     * flight with no date is invisible to a date-filtered query, so the storefront
     * cannot find it until the admin form gains a date field (a Phase 9 change).
     */
    @Test
    void aDatelessFlightIsNotFoundByADateFilter() throws Exception {
        Fixture fixture = fixture();
        String no = createFlight(fixture, 70, "Active");

        mockMvc.perform(adminGet("/api/admin/flights")
                        .param("date", "2026-10-05").param("search", no))
                .andExpect(jsonPath("$.flights").isEmpty());

        // ...but it is still found when no date is asked for.
        mockMvc.perform(adminGet("/api/admin/flights").param("search", no))
                .andExpect(jsonPath("$.flights[0].no").value(no));
    }

    /* ------------------------------------------------------------------ *
     *  duplicates and validation                                          *
     * ------------------------------------------------------------------ */

    /**
     * The unique key alone would not catch this: {@code flight_no} is
     * {@code utf8mb4_bin} (case-<i>sensitive</i>), so {@code "t7 482"} is a
     * different string from {@code "T7 482"} and both would insert. The service
     * normalises first — uppercase and collapsed spaces.
     */
    @Test
    void aDuplicateFlightNumberIsRefusedEvenInAnotherCase() throws Exception {
        Fixture fixture = fixture();
        String no = createFlight(fixture, 70, "Active");

        mockMvc.perform(adminPost("/api/admin/flights")
                        .content(flightBody(no.toLowerCase(Locale.ROOT).replace(" ", "   "),
                                fixture, null, 70, "Active", "06:50", "07:35")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("FLIGHT_NO_EXISTS"));
    }

    @Test
    void arrivalMustBeAfterDeparture() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(adminPost("/api/admin/flights")
                        .content(flightBody(flightNo(), fixture, null, 70, "Active", "07:35", "06:50")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void originAndDestinationCannotBeTheSame() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(adminPost("/api/admin/flights")
                        .content(flightBody(flightNo(), fixture, null, 70, "Active", "06:50", "07:35")
                                .replace("\"to\":\"" + fixture.to().getCode() + "\"",
                                        "\"to\":\"" + fixture.from().getCode() + "\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    /**
     * An unknown airport is a 400 naming the code, not a raw FK failure at flush
     * time — which would surface as a 500 quoting a constraint name instead of the
     * airport the admin mistyped.
     */
    @Test
    void anUnknownAirportCodeIsA400NotAForeignKeyFailure() throws Exception {
        Fixture fixture = fixture();

        mockMvc.perform(adminPost("/api/admin/flights")
                        .content(flightBody(flightNo(), fixture, null, 70, "Active", "06:50", "07:35")
                                .replace("\"from\":\"" + fixture.from().getCode() + "\"",
                                        "\"from\":\"ZZZ\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("UNKNOWN_AIRPORT"));
    }

    /** The page's own bounds, enforced server-side: capacity 1..999, "U4 951" format. */
    @Test
    void anInvalidBodyIsRejectedBeforeTheServiceRuns() throws Exception {
        Fixture fixture = fixture();
        String no = flightNo();

        mockMvc.perform(adminPost("/api/admin/flights")
                        .content(flightBody(no, fixture, null, 0, "Active", "06:50", "07:35")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        mockMvc.perform(adminPost("/api/admin/flights")
                        .content(flightBody("NOT A NUMBER", fixture, null, 70, "Active", "06:50", "07:35")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        assertThat(flights.findByFlightNo(no))
                .as("no flight may be written by a rejected request")
                .isEmpty();
    }

    /* ------------------------------------------------------------------ *
     *  filters, search, ordering, paging                                  *
     * ------------------------------------------------------------------ */

    @Test
    void airlineRouteDateAndStatusFilterIndependently() throws Exception {
        Fixture first = fixture();
        Fixture second = fixture();
        LocalDate date = LocalDate.of(2026, 11, 2);

        String a = createFlight(first, 70, "Active");
        String b = createFlightWithDate(second, date, "Inactive");

        // Airline.
        mockMvc.perform(adminGet("/api/admin/flights")
                        .param("airlineId", String.valueOf(first.airline().getId()))
                        .param("search", a))
                .andExpect(jsonPath("$.flights[0].no").value(a));

        // Route, by code.
        mockMvc.perform(adminGet("/api/admin/flights")
                        .param("from", first.from().getCode()).param("to", first.to().getCode())
                        .param("search", a))
                .andExpect(jsonPath("$.flights[0].no").value(a));

        // Date + status together.
        mockMvc.perform(adminGet("/api/admin/flights")
                        .param("date", date.toString()).param("status", "Inactive")
                        .param("search", b))
                .andExpect(jsonPath("$.flights[0].no").value(b));

        // A status the flight does not have must exclude it.
        mockMvc.perform(adminGet("/api/admin/flights")
                        .param("status", "Inactive").param("search", a))
                .andExpect(jsonPath("$.flights").isEmpty());
    }

    /**
     * The free-text search covers the flight number, both airport codes and the
     * airline's name — the three things {@code admin-flights.js} filters on
     * client-side today, so Phase 9 can move the filter to the server without
     * changing what the box matches.
     */
    @Test
    void searchMatchesTheFlightNumberRouteAndAirlineName() throws Exception {
        Fixture fixture = fixture();
        String no = createFlight(fixture, 70, "Active");

        mockMvc.perform(adminGet("/api/admin/flights").param("search", no.toLowerCase(Locale.ROOT)))
                .andExpect(jsonPath("$.flights[0].no").value(no));

        mockMvc.perform(adminGet("/api/admin/flights")
                        .param("search", fixture.from().getCode().toLowerCase(Locale.ROOT))
                        .param("airlineId", String.valueOf(fixture.airline().getId())))
                .andExpect(jsonPath("$.flights[0].no").value(no));

        mockMvc.perform(adminGet("/api/admin/flights")
                        .param("search", fixture.airline().getName()))
                .andExpect(jsonPath("$.flights[0].no").value(no));
    }

    /** R6's discipline: paging always has an explicit order, so pages cannot overlap. */
    @Test
    void pagingReturnsDisjointPagesWithCounts() throws Exception {
        Fixture fixture = fixture();
        createFlight(fixture, 10, "Active");
        createFlight(fixture, 20, "Active");

        MvcResult firstPage = mockMvc.perform(adminGet("/api/admin/flights")
                        .param("airlineId", String.valueOf(fixture.airline().getId()))
                        .param("size", "1").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.flights").isArray())
                .andReturn();

        MvcResult secondPage = mockMvc.perform(adminGet("/api/admin/flights")
                        .param("airlineId", String.valueOf(fixture.airline().getId()))
                        .param("size", "1").param("page", "1"))
                .andExpect(jsonPath("$.page").value(1))
                .andReturn();

        assertThat(firstPage.getResponse().getContentAsString())
                .as("the same row must not appear on two pages")
                .isNotEqualTo(secondPage.getResponse().getContentAsString());
    }

    @Test
    void theListOrderIsExplicitAndTheSortParameterIsHonoured() throws Exception {
        Fixture fixture = fixture();
        String cheaper = createFlight(fixture, 10, "Active", "4000.00");
        String dearer = createFlight(fixture, 10, "Active", "9000.00");

        mockMvc.perform(adminGet("/api/admin/flights")
                        .param("airlineId", String.valueOf(fixture.airline().getId()))
                        .param("sort", "fare"))
                .andExpect(jsonPath("$.flights[0].no").value(cheaper))
                .andExpect(jsonPath("$.flights[1].no").value(dearer));

        mockMvc.perform(adminGet("/api/admin/flights")
                        .param("airlineId", String.valueOf(fixture.airline().getId()))
                        .param("sort", "fare").param("page", "0").param("size", "2"))
                .andExpect(jsonPath("$.flights[0].no").value(cheaper));

        // A property that is not whitelisted must fall back, not fail: the page's
        // sort values are its own record keys, and an unknown one arrives as "".
        mockMvc.perform(adminGet("/api/admin/flights")
                        .param("airlineId", String.valueOf(fixture.airline().getId()))
                        .param("sort", "bookedSeats"))
                .andExpect(status().isOk());
    }

    /* ------------------------------------------------------------------ *
     *  capacity changes                                                   *
     * ------------------------------------------------------------------ */

    /**
     * Growing appends seats, continuing the numbering — and because 70 seats end
     * mid-row (the 12th row only reaches {@code D}), the two new seats complete
     * that row before a 13th row can start. Asserted at both steps, because "which
     * letters come next" is exactly the kind of rule that is easy to state wrongly.
     */
    @Test
    void growingCapacityAppendsSeatsContinuingTheNumbering() throws Exception {
        Fixture fixture = fixture();
        int flightId = createFlightId(fixture, 70, "Active");
        String no = flightNo(flightId);

        mockMvc.perform(adminPut("/api/admin/flights/" + flightId)
                        .content(flightBody(no, fixture, null, 72, "Active", "06:50", "07:35")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats").value(72))
                .andExpect(jsonPath("$.bookedSeats").value(0));

        Set<String> at72 = seats.findByFlightId(flightId).stream()
                .map(Seat::getSeatNumber).collect(Collectors.toSet());
        assertThat(at72).as("70 → 72 completes row 12").hasSize(72).contains("12E", "12F");

        mockMvc.perform(adminPut("/api/admin/flights/" + flightId)
                        .content(flightBody(no, fixture, null, 74, "Active", "06:50", "07:35")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats").value(74));

        Set<String> at74 = seats.findByFlightId(flightId).stream()
                .map(Seat::getSeatNumber).collect(Collectors.toSet());
        assertThat(at74).as("only a full row starts row 13").hasSize(74).contains("13A", "13B");
    }

    /**
     * Shrinking removes available seats from the top of the map — never a sold one.
     * The row for {@code 12D} is booked first, and it has to survive a shrink to 68.
     */
    @Test
    void shrinkingCapacityNeverRemovesASoldSeat() throws Exception {
        Fixture fixture = fixture();
        int flightId = createFlightId(fixture, 70, "Active");
        String no = flightNo(flightId);
        bookSeat(flightId, "12D");

        mockMvc.perform(adminPut("/api/admin/flights/" + flightId)
                        .content(flightBody(no, fixture, null, 68, "Active", "06:50", "07:35")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seats").value(68))
                .andExpect(jsonPath("$.bookedSeats").value(1));

        List<Seat> map = seats.findByFlightId(flightId);
        assertThat(map).hasSize(68);
        assertThat(map.stream().map(Seat::getSeatNumber))
                .as("the sold seat is still there")
                .contains("12D");
        assertThat(map.stream().filter(seat -> "BOOKED".equals(seat.getStatus())))
                .hasSize(1);
    }

    /**
     * Capacity is the number the available-seat sum starts from
     * ({@code available = capacity − booked}), so a flight must never be shrunk
     * below the seats already sold — the database would happily allow it, and the
     * flight would then report a negative number of free seats.
     */
    @Test
    void capacityCannotShrinkBelowTheBookedSeats() throws Exception {
        Fixture fixture = fixture();
        int flightId = createFlightId(fixture, 10, "Active");
        String no = flightNo(flightId);
        bookSeat(flightId, "1A");
        bookSeat(flightId, "1B");
        bookSeat(flightId, "1C");

        mockMvc.perform(adminPut("/api/admin/flights/" + flightId)
                        .content(flightBody(no, fixture, null, 2, "Active", "06:50", "07:35")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("CAPACITY_BELOW_BOOKED_SEATS"));

        assertThat(seats.findByFlightId(flightId))
                .as("a refused update must not have removed anything")
                .hasSize(10);
    }

    @Test
    void aFlightCannotBeRetypedIntoADuplicateNumber() throws Exception {
        Fixture fixture = fixture();
        String taken = createFlight(fixture, 70, "Active");
        int other = createFlightId(fixture, 70, "Active");

        mockMvc.perform(adminPut("/api/admin/flights/" + other)
                        .content(flightBody(taken, fixture, null, 70, "Active", "06:50", "07:35")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("FLIGHT_NO_EXISTS"));
    }

    /** A body that only changes fields the flight does not care about still works. */
    @Test
    void anUpdateReplacesTheFields() throws Exception {
        Fixture fixture = fixture();
        int flightId = createFlightId(fixture, 70, "Active");
        String no = flightNo(flightId);

        mockMvc.perform(adminPut("/api/admin/flights/" + flightId)
                        .content(flightBody(no, fixture, "2026-12-01", 70, "Inactive", "09:10", "10:05")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Inactive"))
                .andExpect(jsonPath("$.date").value("2026-12-01"))
                .andExpect(jsonPath("$.dep").value("09:10"))
                .andExpect(jsonPath("$.arr").value("10:05"));
    }

    /* ------------------------------------------------------------------ *
     *  deletes                                                            *
     * ------------------------------------------------------------------ */

    /** Seats only mean something inside their flight, so they go with it. */
    @Test
    void anUnsoldFlightCanBeDeletedAndItsSeatMapGoesWithIt() throws Exception {
        Fixture fixture = fixture();
        int flightId = createFlightId(fixture, 12, "Active");

        mockMvc.perform(adminDelete("/api/admin/flights/" + flightId))
                .andExpect(status().isNoContent());

        assertThat(flights.findById(flightId)).isEmpty();
        assertThat(seats.findByFlightId(flightId))
                .as("the seat map cannot outlive the flight")
                .isEmpty();
    }

    /** A sold flight is a customer contract — 409 and "set it Inactive instead". */
    @Test
    void aFlightWithBookingsCannotBeDeleted() throws Exception {
        Fixture fixture = fixture();
        int flightId = createFlightId(fixture, 12, "Active");
        Booking booking = new Booking();
        booking.setFlight(flights.findById(flightId).orElseThrow());
        booking.setBookingStatus("CONFIRMED");
        booking.setTotalAmount(new BigDecimal("8299.99"));
        booking.setProductAmount(new BigDecimal("8299.99"));
        bookings.save(booking);

        mockMvc.perform(adminDelete("/api/admin/flights/" + flightId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("FLIGHT_HAS_BOOKINGS"));

        assertThat(flights.findById(flightId))
                .as("the refusal must not have deleted anything")
                .isPresent();
    }

    @Test
    void deletingAnUnknownFlightIsA404WithTheProjectsErrorShape() throws Exception {
        mockMvc.perform(adminDelete("/api/admin/flights/99999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("FLIGHT_NOT_FOUND"));
    }

    /* ------------------------------------------------------------------ *
     *  fixtures                                                          *
     * ------------------------------------------------------------------ */

    /** One airline plus two airports, all named after a per-run tag. */
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

    private String createFlight(Fixture fixture, int capacity, String status) {
        return createFlight(fixture, capacity, status, "8299.99");
    }

    private String createFlight(Fixture fixture, int capacity, String status, String fare) {
        return createFlight(fixture, capacity, status, fare, null);
    }

    private String createFlightWithDate(Fixture fixture, LocalDate date, String status) {
        return createFlight(fixture, 70, status, "8299.99", date);
    }

    private String createFlight(Fixture fixture, int capacity, String status, String fare, LocalDate date) {
        String no = flightNo();
        try {
            mockMvc.perform(adminPost("/api/admin/flights")
                            .content(flightBody(no, fixture, date == null ? null : date.toString(),
                                    capacity, status, "06:50", "07:35", fare)))
                    .andExpect(status().isOk());
        } catch (Exception ex) {
            throw new IllegalStateException("could not create the fixture flight " + no, ex);
        }
        return no;
    }

    private int createFlightId(Fixture fixture, int capacity, String status) {
        String no = createFlight(fixture, capacity, status);
        return flights.findByFlightNo(no).orElseThrow().getId();
    }

    /**
     * Writes a BOOKED seat directly, so this module's tests stay about the flight —
     * the real booking path (and the concurrency guard) lives in
     * {@code BookingApiTest}.
     */
    private void bookSeat(int flightId, String seatNumber) {
        Seat seat = seats.findByFlightIdAndSeatNumber(flightId, seatNumber).orElseThrow();
        seat.setStatus("BOOKED");
        seats.save(seat);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder adminGet(String url) {
        return get(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder adminPost(String url) {
        return post(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder adminPut(String url) {
        return put(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder adminDelete(String url) {
        return delete(url).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken());
    }

    private String adminToken() {
        return jwtUtil.generate(1, "Dikshya Ghising", "admin@gmail.com", "ADMIN");
    }

    /**
     * The page's payload shape: its form's keys, in its own units — times as
     * {@code "HH:mm"} and the route as airport codes. The date key is emitted only
     * when a date is given, mirroring a form that has no date input.
     */
    private static String flightBody(String no, Fixture fixture, String date, int seats,
                                     String status, String dep, String arr) {
        return flightBody(no, fixture, date, seats, status, dep, arr, "8299.99");
    }

    private static String flightBody(String no, Fixture fixture, String date, int seats,
                                     String status, String dep, String arr, String fare) {
        String dateKey = date == null ? "" : "\"date\":\"" + date + "\",";
        return """
                {"no":"%s","airlineId":%d,"from":"%s","to":"%s",%s
                 "dep":"%s","arr":"%s","aircraft":"ATR 72","fare":%s,"seats":%d,"status":"%s"}
                """.formatted(no, fixture.airline().getId(), fixture.from().getCode(),
                fixture.to().getCode(), dateKey, dep, arr, fare, seats, status);
    }

    private String flightNo(int flightId) {
        return flights.findById(flightId).orElseThrow().getFlightNo();
    }

    /** Isolates each test's rows from other tests and from any future seed data. */
    private static String tag() {
        return "T" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * A flight number in the page's own format — {@code "T7 482"} — that no
     * existing flight already owns. Checked rather than assumed: this test creates
     * several flights per run and the column is UNIQUE.
     */
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

    /**
     * A 2-character IATA code no airline already owns — the column is UNIQUE, and
     * the code space is only 36², so this is checked rather than assumed.
     */
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

    /** A 3-letter code no destination already owns — the column is UNIQUE. */
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
