package io.virinchi.yatra;

import io.virinchi.yatra.Model.Airline;
import io.virinchi.yatra.Model.Booking;
import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Model.Flight;
import io.virinchi.yatra.Model.Payment;
import io.virinchi.yatra.Repository.AirlineRepository;
import io.virinchi.yatra.Repository.BookingRepository;
import io.virinchi.yatra.Repository.DestinationRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import io.virinchi.yatra.Repository.PaymentRepository;
import io.virinchi.yatra.Repository.SeatRepository;
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
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Roadmap Phase 9 — admin booking management: the list with its filters and paging,
 * the full detail, and the status change with its business rules.
 *
 * <p>DB-backed and {@code @Transactional} like {@code BookingApiTest}, so every row
 * is rolled back and the live database is never modified. Each test builds its own
 * airline, airports and flight from a per-run tag, creates its bookings through the
 * real public endpoint ({@code POST /api/bookings}) and reads them back through the
 * admin API. Nothing is inserted behind the API's back except the payment row a
 * successful gateway would have written — the payment flow itself is Phase 10's, and
 * this phase's job is the <i>gate</i> that reads it.
 *
 * <h2>The assertions that are about the <i>contract</i>, not the data</h2>
 * <p>Three of them exist because {@code admin-bookings.js} would fail
 * <i>silently</i> if the body were merely close:
 * <ul>
 *   <li>{@code id} must be a JSON <b>string</b> — the page's search calls
 *       {@code b.id.toLowerCase()}, so an integer would throw a {@code TypeError} and
 *       the page's {@code .catch()} would quietly fall back to its localStorage
 *       seeds;</li>
 *   <li>{@code status} must be the display vocabulary ({@code "Pending"},
 *       {@code "Confirmed"}, {@code "Cancelled"}), because the badge colour, the
 *       filter options and {@code canCancel = b.status === 'Confirmed'} all compare
 *       against those exact strings — an uppercase body removes the cancel button
 *       with no error anywhere;</li>
 *   <li>an absent {@code pnr}/{@code customer} must be {@code ""}, never
 *       {@code null}, because the page runs those straight through
 *       {@code escapeHtml(...)}, which renders the string "null".</li>
 * </ul>
 *
 * <h2>Uniqueness, so a live database cannot make this flaky</h2>
 * <p>Every searchable value carries the run's {@link #tag()} — the contact name,
 * email and phone included. The database these tests run against is the author's
 * live TiDB schema and may hold seeded demo bookings, so "search for a customer"
 * cannot assert an exact count against a fixed name.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminBookingApiTest {

    private static final String ADMIN = "ADMIN";
    private static final String PENDING = "PENDING";
    private static final String CONFIRMED = "CONFIRMED";
    private static final String CANCELLED = "CANCELLED";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private jakarta.persistence.EntityManager entityManager;
    @Autowired private AirlineRepository airlines;
    @Autowired private DestinationRepository destinations;
    @Autowired private FlightRepository flights;
    @Autowired private BookingRepository bookings;
    @Autowired private PaymentRepository payments;
    @Autowired private SeatRepository seats;
    @Autowired private JwtUtil jwtUtil;

    /* ------------------------------------------------------------------ *
     *  the list                                                          *
     * ------------------------------------------------------------------ */

    /**
     * The roadmap checkpoint's first half: an admin lists a booking that really
     * exists, and the body is the mock's record — including the three contract
     * details named in the class doc.
     */
    @Test
    void theAdminListAnswersTheMockRecordShape() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, "16599.98", "1A", "1B");

        mockMvc.perform(get("/api/admin/bookings").param("search", flightNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                // The wrapper is the contract: the mock answers { bookings: [...] }.
                .andExpect(jsonPath("$.bookings").isArray())
                .andExpect(jsonPath("$.bookings.length()").value(1))
                // Unpaged by default, so the paging keys must be absent, not null.
                .andExpect(jsonPath("$.page").doesNotExist())
                .andExpect(jsonPath("$.totalElements").doesNotExist())

                // id is text (the page calls .toLowerCase() on it) and carries the key.
                .andExpect(jsonPath("$.bookings[0].id").value(String.valueOf(bookingId)))

                // Display vocabulary, not the stored CONFIRMED/CANCELLED/PENDING.
                .andExpect(jsonPath("$.bookings[0].status").value("Pending"))
                .andExpect(jsonPath("$.bookings[0].paymentStatus").value("Pending"))

                // The contact block is the page's "customer" column.
                .andExpect(jsonPath("$.bookings[0].customer").value(fixture.contactName()))
                .andExpect(jsonPath("$.bookings[0].email").value(fixture.contactEmail()))
                .andExpect(jsonPath("$.bookings[0].phone").value(fixture.contactPhone()))

                .andExpect(jsonPath("$.bookings[0].flight.flightNo").value(flightNo))
                .andExpect(jsonPath("$.bookings[0].flight.airline.name").value(fixture.airline().getName()))
                .andExpect(jsonPath("$.bookings[0].flight.from").value(fixture.from().getCode()))
                .andExpect(jsonPath("$.bookings[0].flight.to").value(fixture.to().getCode()))
                .andExpect(jsonPath("$.bookings[0].flight.flightClass").value("E Class"))
                .andExpect(jsonPath("$.bookings[0].flight.passengerCount").value(2))
                .andExpect(jsonPath("$.bookings[0].flight.pricePerPassenger").value(8299.99))

                .andExpect(jsonPath("$.bookings[0].amount").value(16599.98))
                .andExpect(jsonPath("$.bookings[0].productAmount").value(16599.98))

                // No ticket and no payment yet — still strings, still an object,
                // never the nulls the page would render as "null".
                .andExpect(jsonPath("$.bookings[0].pnr").value(""))
                .andExpect(jsonPath("$.bookings[0].ticketNo").value(""))
                .andExpect(jsonPath("$.bookings[0].payment.method").value(""))
                .andExpect(jsonPath("$.bookings[0].payment.txnId").value(""))

                .andExpect(jsonPath("$.bookings[0].passengers.length()").value(2))
                .andExpect(jsonPath("$.bookings[0].passengers[0].seatNumber").value("1A"))
                .andExpect(jsonPath("$.bookings[0].passengers[0].type").value("ADT"))
                .andExpect(jsonPath("$.bookings[0].createdAt").isNotEmpty());
    }

    /** The search box's own promise: PNR, id, customer or flight number. */
    @Test
    void searchMatchesTheCustomerTheFlightNumberAndTheId() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, "8299.99", "1A");

        assertThat(searchCount(flightNo, fixture.contactName())).as("customer name").isEqualTo(1);
        assertThat(searchCount(flightNo, fixture.contactEmail())).as("contact email").isEqualTo(1);
        assertThat(searchCount(flightNo, fixture.contactPhone())).as("contact phone").isEqualTo(1);
        assertThat(searchCount(flightNo, flightNo)).as("flight number").isEqualTo(1);
        assertThat(searchCount(flightNo, String.valueOf(bookingId))).as("booking id").isEqualTo(1);
        assertThat(searchCount(flightNo, "nobody-by-that-name")).as("no match").isZero();
    }

    /**
     * The filters, each one independently: booking status in either vocabulary, the
     * payment status, the flight's travel date and the booking's creation date.
     *
     * <p>The flight is dated directly here rather than through the admin API, because
     * the admin flight form still has no date field (R12) — a frontend gap this phase
     * does not close. The filter has to work for a dated flight regardless of how the
     * date got there.
     */
    @Test
    void filtersByStatusPaymentAndBothDateKinds() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");

        Flight flight = flights.findByFlightNo(flightNo).orElseThrow();
        flight.setFlightDate(LocalDate.now());
        flights.saveAndFlush(flight);

        int bookingId = book(fixture, flightNo, "8299.99", "1A");

        // Status: the page's title-case value and the entity's uppercase both work.
        assertThat(filteredCount(flightNo, "status", "Pending")).isEqualTo(1);
        assertThat(filteredCount(flightNo, "status", "PENDING")).isEqualTo(1);
        assertThat(filteredCount(flightNo, "status", "Confirmed")).as("nothing is confirmed yet").isZero();
        assertThat(filteredCount(flightNo, "status", "ALL")).as("ALL means no filter, not no rows").isEqualTo(1);

        // Payment status: the page's title case, matched case-insensitively.
        assertThat(filteredCount(flightNo, "paymentStatus", "Pending")).isEqualTo(1);
        assertThat(filteredCount(flightNo, "paymentStatus", "paid")).isZero();

        // Travel date: the flight's own date matches, the next day does not.
        assertThat(filteredCount(flightNo, "date", LocalDate.now().toString())).isEqualTo(1);
        assertThat(filteredCount(flightNo, "date", LocalDate.now().plusDays(1).toString())).isZero();

        // Creation date: today matches, tomorrow does not.
        assertThat(filteredCount(flightNo, "created", LocalDate.now().toString())).isEqualTo(1);
        assertThat(filteredCount(flightNo, "created", LocalDate.now().plusDays(1).toString())).isZero();

        // And the flight filter itself.
        assertThat(filteredCount(flightNo, "flightId", String.valueOf(flight.getId()))).isEqualTo(1);
        assertThat(filteredCount(flightNo, "flightId", String.valueOf(flight.getId() + 999_999))).isZero();

        assertThat(bookings.findById(bookingId).orElseThrow().getBookingStatus())
                .as("reading a booking never moves it").isEqualTo(PENDING);
    }

    /**
     * Paging: the pages are disjoint and the counts let a client walk the rest — the
     * failure mode R6 records (a row on two pages, another on none) is exactly what
     * an unsorted page produces.
     */
    @Test
    void pagingIsDisjointAndCounted() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");

        List<String> ids = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            ids.add(String.valueOf(book(fixture, flightNo, "8299.99", 1)));
        }

        assertThat(List.of(pageIds(fixture, flightNo, 0), pageIds(fixture, flightNo, 1),
                        pageIds(fixture, flightNo, 2)))
                .as("three pages of one, no repeats and nothing dropped")
                .flatMap(page -> page)
                .containsExactlyInAnyOrderElementsOf(ids);

        mockMvc.perform(get("/api/admin/bookings").param("search", flightNo)
                        .param("size", "1").param("page", "0")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(3));
    }

    /* ------------------------------------------------------------------ *
     *  the detail                                                        *
     * ------------------------------------------------------------------ */

    /** Full detail: the contact, every passenger with its seat, the flight and the payment. */
    @Test
    void theDetailReturnsPassengersFlightAndPayment() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, "16599.98", "1A", "1B");
        pay(bookingId, "SUCCESS");

        mockMvc.perform(get("/api/admin/bookings/" + bookingId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(String.valueOf(bookingId)))
                .andExpect(jsonPath("$.customer").value(fixture.contactName()))
                .andExpect(jsonPath("$.flight.flightNo").value(flightNo))
                .andExpect(jsonPath("$.passengers.length()").value(2))
                .andExpect(jsonPath("$.passengers[0].seatNumber").value("1A"))
                .andExpect(jsonPath("$.passengers[1].seatNumber").value("1B"))
                .andExpect(jsonPath("$.payment.method").value("eSewa"))
                .andExpect(jsonPath("$.payment.txnId").isNotEmpty());
    }

    /** A missing booking is the project's 404 code, not a 500 from an empty Optional. */
    @Test
    void aMissingBookingIsA404() throws Exception {
        mockMvc.perform(get("/api/admin/bookings/999999999")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("BOOKING_NOT_FOUND"));
    }

    /* ------------------------------------------------------------------ *
     *  the status change                                                 *
     * ------------------------------------------------------------------ */

    /**
     * The rule the roadmap names: <b>no confirmation without a successful
     * payment</b>. The booking is created by the public endpoint exactly as the
     * wizard does — PENDING, seats held, no payment row — and the confirm is refused
     * with the specific code, leaving the row untouched.
     */
    @Test
    void confirmingWithoutASuccessfulPaymentIsRefused() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, "8299.99", "1A");

        mockMvc.perform(setStatus(bookingId, "Confirmed"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PAYMENT_NOT_SUCCESSFUL"));

        assertThat(bookings.findById(bookingId).orElseThrow().getBookingStatus())
                .as("a refused confirm changes nothing").isEqualTo(PENDING);

        // A payment that exists but failed is refused the same way — the gate reads
        // the payment row, not the presence of one.
        pay(bookingId, "FAILED");
        mockMvc.perform(setStatus(bookingId, "CONFIRMED"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PAYMENT_NOT_SUCCESSFUL"));

        assertThat(bookings.findById(bookingId).orElseThrow().getBookingStatus()).isEqualTo(PENDING);
    }

    /**
     * With a successful payment the confirm goes through, and the booking's own
     * {@code paymentStatus} is brought into line — a row reading {@code CONFIRMED}
     * while showing "Pending" would be the API contradicting itself in one record.
     */
    @Test
    void confirmingWithASuccessfulPaymentConfirmsAndMarksItPaid() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, "8299.99", "1A");
        pay(bookingId, "SUCCESS");

        mockMvc.perform(setStatus(bookingId, "CONFIRMED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Confirmed"))
                .andExpect(jsonPath("$.paymentStatus").value("Paid"));

        Booking saved = bookings.findById(bookingId).orElseThrow();
        assertThat(saved.getBookingStatus()).isEqualTo(CONFIRMED);
        assertThat(saved.getPaymentStatus()).isEqualTo("Paid");
    }

    /**
     * Cancel is a soft cancel, and idempotent — the admin page's own promise ("seats
     * stay counted on the flight; the refund itself is completed by the payments
     * flow") asserted against the rows.
     */
    @Test
    void cancellingIsSoftAndIdempotent() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, "8299.99", "1A");
        pay(bookingId, "SUCCESS");
        int flightId = flights.findByFlightNo(flightNo).orElseThrow().getId();
        long heldSeats = seats.countByFlightIdAndStatus(flightId, "BOOKED");

        mockMvc.perform(setStatus(bookingId, "Cancelled"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Cancelled"));

        assertThat(payments.findByBookingId(bookingId))
                .as("the payment trail the refund flow needs survives")
                .isPresent();
        assertThat(bookings.findById(bookingId).orElseThrow().getPaymentStatus())
                .as("cancelling must not claim a refund that never happened")
                .isEqualTo("Pending");
        assertThat(seats.countByFlightIdAndStatus(flightId, "BOOKED"))
                .as("seats stay counted — releasing them here would raise availability with no booking change")
                .isEqualTo(heldSeats);

        // A double-click is a no-op, not a 409.
        mockMvc.perform(setStatus(bookingId, "CANCELLED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Cancelled"));
    }

    /**
     * Nothing goes back to PENDING — not a confirmed booking (it has a paid payment
     * and will have a ticket) and not a cancelled one (it has been surrendered).
     */
    @Test
    void revertingToPendingIsRefusedFromBothLiveStates() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");

        int confirmed = book(fixture, flightNo, "8299.99", "1A");
        pay(confirmed, "SUCCESS");
        mockMvc.perform(setStatus(confirmed, "Confirmed")).andExpect(status().isOk());

        mockMvc.perform(setStatus(confirmed, "Pending"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"));
        assertThat(bookings.findById(confirmed).orElseThrow().getBookingStatus())
                .as("the refused transition left the booking confirmed").isEqualTo(CONFIRMED);

        int cancelled = book(fixture, flightNo, "8299.99", "1B");
        mockMvc.perform(setStatus(cancelled, "Cancelled")).andExpect(status().isOk());

        mockMvc.perform(setStatus(cancelled, "Pending"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("INVALID_STATUS_TRANSITION"));
        assertThat(bookings.findById(cancelled).orElseThrow().getBookingStatus())
                .as("the refused transition left the booking cancelled").isEqualTo(CANCELLED);
    }

    /** A status that is not one of the three is a 400 (a bad request), not a 409 (a conflict). */
    @Test
    void anUnknownStatusIsA400() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, "8299.99", "1A");

        mockMvc.perform(setStatus(bookingId, "DONE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    /* ------------------------------------------------------------------ *
     *  authorization                                                     *
     * ------------------------------------------------------------------ */

    /**
     * One explicit check beside {@code AdminRouteAuthorizationTest}'s sweep, because
     * this controller is where a booking's money trail becomes readable: anonymous is
     * 401, a signed-in {@code USER} is 403.
     */
    @Test
    void onlyAnAdminReachesTheBookingSurface() throws Exception {
        mockMvc.perform(get("/api/admin/bookings"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("NOT_AUTHENTICATED"));

        String userToken = jwtUtil.generate(2, "Anju Karki", "anju.karki@example.com", "USER");
        mockMvc.perform(get("/api/admin/bookings").header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    /* ------------------------------------------------------------------ *
     *  request helpers                                                   *
     * ------------------------------------------------------------------ */

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder setStatus(
            int bookingId, String status) {
        return put("/api/admin/bookings/" + bookingId + "/status")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + status + "\"}");
    }

    /**
     * How many rows this test's flight produces for one filter.
     *
     * <p>Always scoped by the flight's unique number as well, so a live database
     * holding seeded demo bookings cannot change the count — and so the assertion is
     * about the filter, not about what happens to be in the cloud schema.
     */
    private int filteredCount(String flightNo, String parameter, String value) throws Exception {
        String body = mockMvc.perform(get("/api/admin/bookings")
                        .param("search", flightNo)
                        .param(parameter, value)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).get("bookings").size();
    }

    /**
     * How many rows one search term finds <i>within this test's flight</i>.
     *
     * <p>Scoped by {@code flightId} rather than by the flight number, because the
     * term being tested may itself be the flight number — a second {@code search}
     * parameter would be ignored by the controller and the assertion would pass by
     * accident, which is the kind of green test that proves nothing.
     */
    private int searchCount(String flightNo, String term) throws Exception {
        int flightId = flights.findByFlightNo(flightNo).orElseThrow().getId();

        String body = mockMvc.perform(get("/api/admin/bookings")
                        .param("search", term)
                        .param("flightId", String.valueOf(flightId))
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).get("bookings").size();
    }

    private List<String> pageIds(Fixture fixture, String flightNo, int page) throws Exception {
        String body = mockMvc.perform(get("/api/admin/bookings").param("search", flightNo)
                        .param("page", String.valueOf(page)).param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode rows = objectMapper.readTree(body).get("bookings");
        List<String> ids = new ArrayList<>();
        rows.forEach(row -> ids.add(row.get("id").asText()));
        return ids;
    }

    /* ------------------------------------------------------------------ *
     *  fixtures                                                          *
     * ------------------------------------------------------------------ */

    /** Creates a booking through the public endpoint and returns its id. */
    private int book(Fixture fixture, String flightNo, String amount, String... seatNumbers) throws Exception {
        return book(fixture, flightNo, amount, List.of(seatNumbers));
    }

    /** The same, for a caller that only cares how many passengers there are. */
    private int book(Fixture fixture, String flightNo, String amount, int passengerCount) throws Exception {
        return book(fixture, flightNo, amount, Collections.nCopies(passengerCount, (String) null));
    }

    private int book(Fixture fixture, String flightNo, String amount, List<String> seatNumbers) throws Exception {
        String body = mockMvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(fixture, flightNo, amount, seatNumbers)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The response's own id — read from the body so the test never guesses.
        return objectMapper.readTree(body).get("bookingId").asInt();
    }

    /**
     * The row a successful gateway would have written.
     *
     * <p>Inserted directly rather than through a service: the payment flow is Phase
     * 10's, and this phase's job is the <i>gate</i> — the admin endpoint must read
     * the payment row's status, so the test has to be able to produce every one of
     * them without borrowing a service that does not exist yet.
     *
     * <p><b>Why the persistence context is cleared afterwards.</b> Same class of
     * problem {@code BookingApiTest.refresh()} documents, in a new place: the booking
     * entity is already managed (the test created it through the API in this very
     * transaction), and flushing the new payment makes Hibernate initialise the
     * booking's inverse {@code payment} association — from a database that does not
     * hold the row yet. The null it caches is then what any later
     * {@code booking.getPayment()} in this context sees, so a detail response inside
     * the same transaction would honestly report "no payment". In production each
     * request has its own persistence context and the row is already committed, which
     * is why this is a harness concern and not a service bug — but it is exactly the
     * kind of thing that would otherwise be "fixed" by weakening the assertion.
     */
    private Payment pay(int bookingId, String status) {
        Booking booking = bookings.findById(bookingId).orElseThrow();

        Payment payment = new Payment();
        payment.setBooking(booking);
        payment.setMethod("eSewa");
        payment.setTxnId("T" + UUID.randomUUID().toString().replaceAll("[^A-Za-z0-9]", "")
                .substring(0, 14).toUpperCase());
        payment.setAmount(booking.getTotalAmount());
        payment.setStatus(status);
        payment.setCreatedAt(LocalDateTime.now());
        payment.setPaidAt("SUCCESS".equals(status) ? LocalDateTime.now() : null);

        Payment saved = payments.saveAndFlush(payment);
        refresh();
        return saved;
    }

    /** Flushes and drops the persistence context, so the next read comes from the database. */
    private void refresh() {
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * One test's rows, isolated from every other test <i>and</i> from whatever the
     * live database already holds.
     *
     * <p>Everything the admin can search on carries the tag: the flight number, the
     * contact name, the email and the phone. Without that, "search for the customer"
     * would be an exact-count assertion against a name a seeded demo booking could
     * already have — a test that fails only on a machine that has run the seeder.
     */
    private record Fixture(String tag, String flightNo, Airline airline, Destination from, Destination to) {

        String contactName() {
            return "Mr Hari" + tag + " Prasad Sharma";
        }

        String contactEmail() {
            return "hari-" + tag.toLowerCase() + "@example.com";
        }

        /** 10 digits, unique per run, in the roster's stored form. */
        String contactPhone() {
            return "98" + String.format("%08d", Math.abs(tag.hashCode()) % 100_000_000);
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

    /**
     * A flight number the page's own format accepts ({@code "U4 951"}) and this run
     * does not already use — the pattern is airline code plus digits, so the tag's
     * hex characters cannot be used here the way they are in the contact block.
     */
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

    private Destination seedDestination(String tag) {
        Destination destination = new Destination();
        destination.setCity("City " + tag);
        destination.setCode(uniqueAirportCode());
        destination.setAirport(tag + " Airport");
        destination.setStatus("Active");
        return destinations.save(destination);
    }

    /** Creates a flight through the real admin endpoint (which also builds its seat map). */
    private String createFlight(Fixture fixture, int capacity, String fare) throws Exception {
        String no = fixture.flightNo();

        mockMvc.perform(post("/api/admin/flights")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(flightBody(fixture, no, capacity, fare)))
                .andExpect(status().isOk());

        return no;
    }

    private static String flightBody(Fixture fixture, String no, int seats, String fare) {
        return """
                {"no":"%s","airlineId":%d,"from":"%s","to":"%s",
                 "dep":"06:50","arr":"07:35","aircraft":"ATR 72","fare":%s,"seats":%d,"status":"Active"}
                """.formatted(no, fixture.airline().getId(), fixture.from().getCode(),
                fixture.to().getCode(), fare, seats);
    }

    /**
     * The page's own payload — contact block, one passenger per entry, and the
     * selected-flight record. A {@code null} seat is omitted entirely, which is what
     * the wizard sends today (it has no seat picker).
     */
    private static String bookingBody(Fixture fixture, String flightNo, String amount,
                                      List<String> seatNumbers) {
        List<String> rows = new ArrayList<>();
        for (String seatNumber : seatNumbers) {
            String seatKey = seatNumber == null ? "" : ",\"seatNumber\":\"" + seatNumber + "\"";
            rows.add("""
                    {"title":"Mr","firstName":"Hari%s","middleName":"Prasad","lastName":"Sharma","nationality":"Nepali","type":"ADT"%s}"""
                    .formatted(fixture.tag(), seatKey));
        }

        return """
                {"contact":{"title":"Mr","firstName":"Hari%s","middleName":"Prasad","lastName":"Sharma",
                 "email":"%s","phone":"%s","invoiceParty":"Self","panNo":"","isPassenger":true},
                 "passengers":[%s],
                 "flight":{"from":"%s","to":"%s","date":"%s","depart":"06:50","arrive":"07:35","flightNo":"%s",
                 "airline":"Air","flightClass":"E Class","refundable":true,"pricePerPassenger":8299.99,
                 "passengerCount":%d,"totalPrice":%s},
                 "amount":%s}"""
                .formatted(fixture.tag(), fixture.contactEmail(), fixture.contactPhone(),
                        String.join(",", rows), fixture.from().getCode(), fixture.to().getCode(),
                        LocalDate.now(), flightNo, seatNumbers.size(), amount, amount);
    }

    private String adminToken() {
        return jwtUtil.generate(1, "Dikshya Ghising", "admin@gmail.com", ADMIN);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    /** Isolates each test's rows from other tests and from the seeded demo data. */
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
