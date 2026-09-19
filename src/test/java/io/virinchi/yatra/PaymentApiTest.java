package io.virinchi.yatra;

import io.virinchi.yatra.Model.Airline;
import io.virinchi.yatra.Model.Booking;
import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Model.Payment;
import io.virinchi.yatra.Model.Ticket;
import io.virinchi.yatra.Repository.AirlineRepository;
import io.virinchi.yatra.Repository.BookingRepository;
import io.virinchi.yatra.Repository.DestinationRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import io.virinchi.yatra.Repository.PaymentRepository;
import io.virinchi.yatra.Repository.SeatRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Roadmap Phase 10 — the eSewa payment backend: {@code initiate}, {@code verify},
 * the ticket a success mints, the admin ledger, and the refund.
 *
 * <p>DB-backed and {@code @Transactional} like {@code BookingApiTest} and
 * {@code AdminBookingApiTest}: every row is rolled back and the live TiDB schema is
 * never modified. Unlike those two, this suite touches <b>no</b> repository to
 * arrange its payments — the whole point of the phase is that the API can now
 * produce a paid, confirmed, ticketed booking on its own, so a test that inserted
 * the payment row by hand would prove the opposite of what it claims. Only the
 * airline / airport / flight / booking prerequisites go through the existing
 * endpoints, exactly as the Phase 6 and Phase 9 suites do.
 *
 * <h2>The assertions that are about the <i>contract</i>, not the data</h2>
 * <ul>
 *   <li><b>The response speaks the mock's keys.</b> {@code payment.js} reads only
 *       {@code gatewayRedirect} and {@code esewaConfirm.js} reads nothing at all, so
 *       the flow cannot break on a missing field — but the Postman collection and the
 *       report do read them, and "the page tolerates it" is not the same as "the
 *       contract holds".</li>
 *   <li><b>The ledger answers {@code { bookings: [...] }}.</b> {@code admin-payments.js}
 *       derives a transaction per booking and reads {@code resp.bookings}; a
 *       purpose-built payments array would leave that undefined and the page would
 *       quietly render its localStorage demo seeds (risk R16).</li>
 *   <li><b>Money is the server's.</b> Every request in here sends a nonsense amount
 *       on purpose; the recorded figure has to be the booking's own total anyway.</li>
 * </ul>
 *
 * <h2>Uniqueness, so a live database cannot make this flaky</h2>
 * <p>{@code payment.txn_id} and {@code ticket.pnr}/{@code ticket.ticket_no} are
 * UNIQUE, and the seeding phase has already put its own rows in the live schema, so
 * every transaction id here is minted from a random source (in the mock's
 * {@code "9A" + 8 digits} shape) and every search is scoped by the run's own tag
 * ({@link #tag()}) or flight number rather than asserted against a global count.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PaymentApiTest {

    private static final String ADMIN = "ADMIN";
    private static final String PENDING = "PENDING";
    private static final String CONFIRMED = "CONFIRMED";
    private static final String CANCELLED = "CANCELLED";

    private static final String PENDING_PAYMENT = "Pending";
    private static final String PAID = "Paid";
    private static final String FAILED_PAYMENT = "Failed";
    private static final String REFUNDED = "Refunded";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private jakarta.persistence.EntityManager entityManager;
    @Autowired private AirlineRepository airlines;
    @Autowired private DestinationRepository destinations;
    @Autowired private FlightRepository flights;
    @Autowired private BookingRepository bookings;
    @Autowired private PaymentRepository payments;
    @Autowired private TicketRepository tickets;
    @Autowired private SeatRepository seats;
    @Autowired private JwtUtil jwtUtil;

    /* ================================================================== *
     *  initiate                                                          *
     * ================================================================== */

    /**
     * The first half of the roadmap's checkpoint: a transaction is opened for a real
     * pending booking, the body is the mock's own shape, and the recorded amount is
     * the booking's — not the figure the request asked for.
     */
    @Test
    void initiatingOpensAPendingTransactionAndAnswersTheMockShape() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, 2);

        // "amount": 1 is deliberate — the request's figure must be ignored.
        String body = mockMvc.perform(initiate(bookingId, "esewa", "1"))
                .andExpect(status().isOk())
                // The mock's keys, so the wizard's handoff needs no change.
                .andExpect(jsonPath("$.paymentRef").value(startsWith("PAY")))
                .andExpect(jsonPath("$.bookingId").value(bookingId))
                // The stored spelling, which admin-payments.html's filter compares against.
                .andExpect(jsonPath("$.method").value("eSewa"))
                .andExpect(jsonPath("$.amount").value(16599.98))
                // §10 (Session 63) — this used to answer "esewaLogin.html", one of the
                // four pages that recreated eSewa's own login/OTP/balance screens as
                // static Yatra HTML. The wizard now navigates to this server's signed
                // handoff, which POSTs the customer to eSewa's real hosted page. The
                // mock path is unaffected: api.js's own route still answers the page.
                .andExpect(jsonPath("$.gatewayRedirect")
                        .value("/api/payments/esewa/checkout/" + bookingId))
                .andReturn().getResponse().getContentAsString();

        // The reference names the row it created, not a throwaway timestamp.
        String paymentRef = objectMapper.readTree(body).get("paymentRef").asText();
        assertThat(paymentRef).as("PAY + the payment row's id, zero padded").hasSize(11);

        refresh();

        Payment payment = payment(payments.findByBookingId(bookingId));
        assertThat(payment.getStatus()).as("initiate opens the transaction, it does not settle it")
                .isEqualTo("PENDING");
        // §10 (Session 63) — the row now holds the transaction_uuid this server mints and
        // sends to eSewa, because that reference is what the real callback is matched by:
        // eSewa's redirect carries the uuid and no booking id, so the row has to be
        // findable by it. It replaces an older assertion that this was null; the value is
        // deliberately opaque (a bare UUID), so only its presence is asserted here.
        assertThat(payment.getTxnId())
                .as("the transaction reference the callback will be matched by")
                .isNotBlank();
        assertThat(payment.getTxnId())
                .as("a UUID, not a booking-derived value — eSewa rejects a reused transaction_uuid")
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        assertThat(payment.getPaidAt()).isNull();
        assertThat(payment.getMethod()).isEqualTo("eSewa");
        assertThat(payment.getAmount()).as("fare x passengers, computed server-side")
                .isEqualByComparingTo("16599.98");
        assertThat(paymentRef).isEqualTo("PAY" + String.format("%08d", payment.getId()));

        Booking booking = require(bookingId);
        assertThat(booking.getBookingStatus()).isEqualTo(PENDING);
        assertThat(booking.getPaymentStatus()).isEqualTo(PENDING_PAYMENT);
    }

    /**
     * The payment table is 1:1 with a booking, so a second initiate reuses the row
     * instead of colliding with the unique key — and the reference it answers with is
     * the same one.
     */
    @Test
    void initiatingTwiceReusesTheSameRowBecauseABookingHasOneTransaction() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, 1);

        String first = mockMvc.perform(initiate(bookingId, "esewa", null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(initiate(bookingId, "esewa", null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(second).get("paymentRef").asText())
                .as("the same row, so the same reference")
                .isEqualTo(objectMapper.readTree(first).get("paymentRef").asText());

        refresh();
        assertThat(payments.findByBookingId(bookingId)).isPresent();
    }

    /** A cancelled booking has been surrendered — there is nothing left to charge. */
    @Test
    void initiatingRefusesACancelledBooking() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, 1);

        mockMvc.perform(put("/api/admin/bookings/" + bookingId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Cancelled\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(initiate(bookingId, "esewa", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("BOOKING_CANCELLED"));

        refresh();
        assertThat(payments.findByBookingId(bookingId)).as("nothing was written").isEmpty();
    }

    /**
     * Only eSewa is integrated. The page blocks the other radio buttons with an
     * alert; the API refuses them too, because accepting one would record a
     * transaction with no gateway behind it.
     */
    @Test
    void onlyTheIntegratedGatewayIsAccepted() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, 1);

        mockMvc.perform(initiate(bookingId, "card", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("METHOD_NOT_SUPPORTED"));

        // eSewa's own bank-linked option is the same integration, and is accepted.
        mockMvc.perform(initiate(bookingId, "Linked Bank Account", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.method").value("Linked Bank Account"));
    }

    /** An unknown booking is a 404, not a payment row quietly pointing at nothing. */
    @Test
    void initiatingForAnUnknownBookingIsA404() throws Exception {
        mockMvc.perform(initiate(999_999_999, "esewa", null))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("BOOKING_NOT_FOUND"));
    }

    /* ================================================================== *
     *  verify — the success path is the roadmap checkpoint             *
     * ================================================================== */

    /**
     * The checkpoint's core: a simulated successful payment flips the booking to
     * {@code CONFIRMED}, records the transaction as {@code SUCCESS}, and mints the
     * ticket — with the seat it was holding left exactly as it was.
     */
    @Test
    void aSuccessfulVerifyConfirmsTheBookingAndIssuesATicket() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, 2);
        mockMvc.perform(initiate(bookingId, "esewa", null)).andExpect(status().isOk());

        String txnId = txnId();
        String body = mockMvc.perform(verify(bookingId, txnId, "esewa", null))
                .andExpect(status().isOk())
                // The payment row's vocabulary here, not the booking's title case.
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.txnId").value(txnId))
                .andExpect(jsonPath("$.bookingId").value(bookingId))
                .andExpect(jsonPath("$.persisted").value(true))
                // The two facts the mock never reported: the identifiers a real sale has.
                .andExpect(jsonPath("$.pnr").value(org.hamcrest.Matchers.matchesPattern("YTRA[A-J]{2}\\d{2}")))
                .andExpect(jsonPath("$.ticketNo").value(org.hamcrest.Matchers.matchesPattern("784-24\\d{10}")))
                .andExpect(jsonPath("$.verifiedAt").exists())
                .andReturn().getResponse().getContentAsString();

        String pnr = objectMapper.readTree(body).get("pnr").asText();
        String ticketNo = objectMapper.readTree(body).get("ticketNo").asText();

        refresh();

        Booking booking = require(bookingId);
        assertThat(booking.getBookingStatus()).isEqualTo(CONFIRMED);
        assertThat(booking.getPaymentStatus()).as("the row cannot read Confirmed and still say Pending")
                .isEqualTo(PAID);

        Payment payment = payment(payments.findByBookingId(bookingId));
        assertThat(payment.getStatus()).isEqualTo("SUCCESS");
        assertThat(payment.getTxnId()).isEqualTo(txnId);
        assertThat(payment.getPaidAt()).isNotNull();
        assertThat(payment.getAmount()).isEqualByComparingTo("16599.98");

        Optional<Ticket> ticket = tickets.findByBookingId(bookingId);
        assertThat(ticket).as("a successful payment is what issues the ticket").isPresent();
        assertThat(ticket.orElseThrow().getStatus()).isEqualTo("ISSUED");
        assertThat(ticket.orElseThrow().getPnr()).isEqualTo(pnr);
        assertThat(ticket.orElseThrow().getTicketNo()).isEqualTo(ticketNo);
        assertThat(ticket.orElseThrow().getIssuedAt()).isNotNull();

        // The sale does not move a seat: they were held at booking time and stay held.
        assertThat(bookedSeats(flightNo)).isEqualTo(2);
    }

    /**
     * The failure half of the checkpoint: a declined payment is recorded as
     * {@code FAILED} and the booking is left exactly where it was — pending, its
     * seats still held, and no ticket.
     *
     * <p>Many of the mock's own "demonstrate a failure" scenarios (insufficient
     * balance) happen entirely inside the gateway page and never reach this API at
     * all, which is why the {@code outcome} switch exists — see
     * {@code Dto/PaymentVerifyRequest}.
     */
    @Test
    void aFailedVerifyLeavesTheBookingPendingAndIssuesNoTicket() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, 2);
        mockMvc.perform(initiate(bookingId, "esewa", null)).andExpect(status().isOk());

        String txnId = txnId();
        mockMvc.perform(verify(bookingId, txnId, "esewa", "FAILED"))
                .andExpect(status().isOk())
                // 200, because the *verification* succeeded: the gateway said no.
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.persisted").value(false))
                .andExpect(jsonPath("$.pnr").value(""))
                .andExpect(jsonPath("$.ticketNo").value(""));

        refresh();

        Booking booking = require(bookingId);
        assertThat(booking.getBookingStatus()).isEqualTo(PENDING);
        assertThat(booking.getPaymentStatus()).as("the page's filter vocabulary").isEqualTo(FAILED_PAYMENT);

        Payment payment = payment(payments.findByBookingId(bookingId));
        assertThat(payment.getStatus()).isEqualTo("FAILED");
        assertThat(payment.getTxnId()).as("the failed attempt still has a gateway reference").isEqualTo(txnId);
        assertThat(payment.getPaidAt()).isNull();
        assertThat(payments.findByTxnId(txnId)).isPresent();

        assertThat(tickets.findByBookingId(bookingId)).as("a declined payment mints nothing").isEmpty();
        assertThat(bookedSeats(flightNo)).as("the seats stay held for the wizard's retry").isEqualTo(2);
    }

    /**
     * A gateway callback for a transaction that was never started is refused, and
     * <b>nothing</b> is written — this is the structural guard that survives having
     * no callback signature: a caller cannot mark an arbitrary booking paid.
     */
    @Test
    void verifyingWithoutInitiatingIsRefusedAndWritesNothing() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, 1);

        mockMvc.perform(verify(bookingId, txnId(), "esewa", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PAYMENT_NOT_INITIATED"));

        refresh();
        assertThat(payments.findByBookingId(bookingId)).isEmpty();
        assertThat(require(bookingId).getBookingStatus()).isEqualTo(PENDING);
        assertThat(tickets.findByBookingId(bookingId)).isEmpty();
    }

    /** An unsupported gateway is refused on the verify path as well as the initiate one. */
    @Test
    void verifyingWithAnUnsupportedGatewayIsRefused() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, 1);
        mockMvc.perform(initiate(bookingId, "esewa", null)).andExpect(status().isOk());

        mockMvc.perform(verify(bookingId, txnId(), "khalti", null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("METHOD_NOT_SUPPORTED"));

        refresh();
        assertThat(payment(payments.findByBookingId(bookingId)).getStatus())
                .as("the refusal left the transaction untouched").isEqualTo("PENDING");
    }

    /* ================================================================== *
     *  verify — idempotency and the refusals                            *
     * ================================================================== */

    /**
     * A retry with the same reference answers {@code SUCCESS} again and mints no
     * second ticket.
     *
     * <p>The count is proved by the schema rather than by a query:
     * {@code ticket.booking_id} is UNIQUE, so a second ticket for the same booking
     * cannot exist — and {@code TicketService.issue} returns the existing one instead
     * of trying, which is what keeps a browser retry from turning a completed sale
     * into a 500.
     */
    @Test
    void verifyingTheSameTransactionAgainIsIdempotent() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, 1);
        mockMvc.perform(initiate(bookingId, "esewa", null)).andExpect(status().isOk());

        String txnId = txnId();
        String first = mockMvc.perform(verify(bookingId, txnId, "esewa", null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String second = mockMvc.perform(verify(bookingId, txnId, "esewa", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andReturn().getResponse().getContentAsString();

        assertThat(objectMapper.readTree(second).get("pnr").asText())
                .as("the same ticket, not a second one")
                .isEqualTo(objectMapper.readTree(first).get("pnr").asText());
        assertThat(objectMapper.readTree(second).get("ticketNo").asText())
                .isEqualTo(objectMapper.readTree(first).get("ticketNo").asText());

        refresh();
        assertThat(require(bookingId).getBookingStatus()).isEqualTo(CONFIRMED);
    }

    /**
     * A <i>different</i> reference against an already-paid booking is refused: the
     * booking holds one transaction, and re-pointing it at another one is how a
     * payment row stops matching what the gateway did.
     */
    @Test
    void aDifferentTransactionAgainstAPaidBookingIsRefused() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, 1);
        mockMvc.perform(initiate(bookingId, "esewa", null)).andExpect(status().isOk());

        String paidWith = txnId();
        mockMvc.perform(verify(bookingId, paidWith, "esewa", null)).andExpect(status().isOk());

        mockMvc.perform(verify(bookingId, txnId(), "esewa", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PAYMENT_ALREADY_COMPLETED"));

        refresh();
        assertThat(payment(payments.findByBookingId(bookingId)).getTxnId())
                .as("the original reference survives the refusal").isEqualTo(paidWith);
    }

    /** A second payment attempt on a booking that is already paid is refused up front. */
    @Test
    void initiatingASecondPaymentForAPaidBookingIsRefused() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, 1);
        mockMvc.perform(initiate(bookingId, "esewa", null)).andExpect(status().isOk());
        mockMvc.perform(verify(bookingId, txnId(), "esewa", null)).andExpect(status().isOk());

        mockMvc.perform(initiate(bookingId, "esewa", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PAYMENT_ALREADY_COMPLETED"));
    }

    /**
     * One gateway reference cannot settle two bookings — {@code payment.txn_id} is
     * UNIQUE, and the service pre-checks it so the caller gets a message naming the
     * reference instead of a generic duplicate-key error.
     */
    @Test
    void aTransactionIdAlreadyUsedByAnotherBookingIsRefused() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int first = book(fixture, flightNo, 1);
        int second = book(fixture, flightNo, 1);

        mockMvc.perform(initiate(first, "esewa", null)).andExpect(status().isOk());
        mockMvc.perform(initiate(second, "esewa", null)).andExpect(status().isOk());

        String shared = txnId();
        mockMvc.perform(verify(first, shared, "esewa", null)).andExpect(status().isOk());

        mockMvc.perform(verify(second, shared, "esewa", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("TXN_ID_ALREADY_USED"));

        refresh();
        assertThat(payment(payments.findByBookingId(second)).getStatus()).isEqualTo("PENDING");
        assertThat(tickets.findByBookingId(second)).isEmpty();
    }

    /** A cancelled booking cannot be paid for, and confirming it is refused anyway. */
    @Test
    void aCancelledBookingCannotBeVerified() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, 1);
        mockMvc.perform(initiate(bookingId, "esewa", null)).andExpect(status().isOk());

        mockMvc.perform(put("/api/admin/bookings/" + bookingId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Cancelled\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(verify(bookingId, txnId(), "esewa", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("BOOKING_CANCELLED"));

        refresh();
        assertThat(require(bookingId).getBookingStatus()).isEqualTo(CANCELLED);
        assertThat(payment(payments.findByBookingId(bookingId)).getStatus()).isEqualTo("PENDING");
    }

    /* ================================================================== *
     *  ticket derivation                                                 *
     * ================================================================== */

    /**
     * The derivation is deterministic on the transaction id, and the identifiers are
     * UNIQUE — so this is the test that matters most in this section: two ids whose
     * eight digits agree derive the same PNR, and the second booking must still get
     * its own.
     *
     * <p>Not a hypothetical: the page mints its id as
     * {@code "9A" + Date.now().toString().slice(-8)}, so two payments about 27.8
     * hours apart share those eight digits, and the {@code 9A}/{@code 9B} prefix
     * contributes nothing to the derivation at all. Without the salt both bookings
     * would derive the same pair and the second insert would fail the unique key
     * <i>inside the payment transaction</i> — rolling back a payment the gateway had
     * already taken.
     */
    @Test
    void twoTransactionsWithTheSameDigitsStillGetDistinctTickets() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int first = book(fixture, flightNo, 1);
        int second = book(fixture, flightNo, 1);

        String digits = String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
        mockMvc.perform(initiate(first, "esewa", null)).andExpect(status().isOk());
        mockMvc.perform(initiate(second, "esewa", null)).andExpect(status().isOk());

        String firstPnr = pnrOf(mockMvc.perform(verify(first, "9A" + digits, "esewa", null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        String secondPnr = pnrOf(mockMvc.perform(verify(second, "9B" + digits, "esewa", null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());

        assertThat(secondPnr).as("the same digits derive the same PNR — so the salt must differ")
                .isNotEqualTo(firstPnr);

        refresh();
        Ticket firstTicket = tickets.findByBookingId(first).orElseThrow();
        Ticket secondTicket = tickets.findByBookingId(second).orElseThrow();
        assertThat(firstTicket.getPnr()).isNotEqualTo(secondTicket.getPnr());
        assertThat(firstTicket.getTicketNo()).isNotEqualTo(secondTicket.getTicketNo());
        assertThat(firstTicket.getPnr()).isEqualTo(firstPnr);
    }

    /* ================================================================== *
     *  the admin ledger                                                  *
     * ================================================================== */

    /**
     * The ledger's shape. The wrapper key, the string id and the title-case display
     * statuses are the three things {@code admin-payments.js} reads directly — the
     * page has no payments store of its own, it derives one transaction per booking.
     */
    @Test
    void theLedgerListsTheTransactionInThePageShape() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, 2);
        String txnId = settle(bookingId, "esewa", null);

        mockMvc.perform(get("/api/admin/payments").param("search", txnId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                // The wrapper IS the contract: the page reads resp.bookings.
                .andExpect(jsonPath("$.bookings").isArray())
                .andExpect(jsonPath("$.bookings.length()").value(1))
                // Unpaged by default, so the paging keys must be absent, not null.
                .andExpect(jsonPath("$.page").doesNotExist())
                .andExpect(jsonPath("$.totalElements").doesNotExist())

                // A string id: the page calls .toLowerCase() on it (R16).
                .andExpect(jsonPath("$.bookings[0].id").value(String.valueOf(bookingId)))
                .andExpect(jsonPath("$.bookings[0].status").value("Confirmed"))
                .andExpect(jsonPath("$.bookings[0].paymentStatus").value(PAID))

                // What toTxn() derives the transaction row from.
                .andExpect(jsonPath("$.bookings[0].payment.txnId").value(txnId))
                .andExpect(jsonPath("$.bookings[0].payment.method").value("eSewa"))
                .andExpect(jsonPath("$.bookings[0].payment.paidAt").exists())
                .andExpect(jsonPath("$.bookings[0].amount").value(16599.98))

                // The columns the table shows beside it.
                .andExpect(jsonPath("$.bookings[0].customer").value(fixture.contactName()))
                .andExpect(jsonPath("$.bookings[0].pnr").value(org.hamcrest.Matchers.matchesPattern("YTRA[A-J]{2}\\d{2}")))
                .andExpect(jsonPath("$.bookings[0].flight.flightNo").value(flightNo))
                .andExpect(jsonPath("$.bookings[0].flight.passengerCount").value(2));
    }

    /** The search box's own promise: txn id, booking id, PNR, customer — and the flight number. */
    @Test
    void theLedgerSearchSpansTheTransactionTheBookingThePnrAndTheCustomer() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, 1);
        String txnId = settle(bookingId, "esewa", null);

        refresh();
        String pnr = tickets.findByBookingId(bookingId).orElseThrow().getPnr();

        assertThat(ledgerCount(txnId)).as("the transaction id").isEqualTo(1);
        assertThat(ledgerCount(String.valueOf(bookingId))).as("the booking id").isEqualTo(1);
        assertThat(ledgerCount(pnr)).as("the PNR").isEqualTo(1);
        assertThat(ledgerCount(fixture.contactName())).as("the customer").isEqualTo(1);
        assertThat(ledgerCount(fixture.contactEmail())).as("the customer's email").isEqualTo(1);
        assertThat(ledgerCount(flightNo)).as("the flight number").isEqualTo(1);
    }

    /**
     * Both filters, and both vocabularies: the page's dropdown sends
     * {@code Paid}/{@code Pending}/{@code Failed}/{@code Refunded} (it reads the
     * booking's {@code paymentStatus}) while the payment row stores
     * {@code SUCCESS}/{@code PENDING}/… — a caller quoting the API back to itself
     * must not get zero rows.
     */
    @Test
    void theLedgerFiltersByMethodAndByStatusInEitherVocabulary() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int paid = book(fixture, flightNo, 1);
        int declined = book(fixture, flightNo, 1);
        int awaiting = book(fixture, flightNo, 1);

        settle(paid, "esewa", null);
        settle(declined, "esewa", "FAILED");
        mockMvc.perform(initiate(awaiting, "Linked Bank Account", null)).andExpect(status().isOk());

        assertThat(filteredLedgerCount(flightNo, "status", PAID)).as("Paid").isEqualTo(1);
        assertThat(filteredLedgerCount(flightNo, "status", "SUCCESS")).as("SUCCESS").isEqualTo(1);
        assertThat(filteredLedgerCount(flightNo, "status", FAILED_PAYMENT)).as("Failed").isEqualTo(1);
        assertThat(filteredLedgerCount(flightNo, "status", "PENDING")).as("PENDING").isEqualTo(1);
        assertThat(filteredLedgerCount(flightNo, "method", "eSewa")).as("eSewa").isEqualTo(2);
        assertThat(filteredLedgerCount(flightNo, "method", "Linked Bank Account")).as("bank").isEqualTo(1);
        assertThat(filteredLedgerCount(flightNo, "status", "ALL")).as("ALL is no filter").isEqualTo(3);
    }

    /** Paging is real, disjoint and counted — and only when {@code size} is asked for. */
    @Test
    void theLedgerPagesAndCounts() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int first = book(fixture, flightNo, 1);
        int second = book(fixture, flightNo, 1);
        settle(first, "esewa", null);
        settle(second, "esewa", null);

        String page0 = mockMvc.perform(get("/api/admin/payments").param("search", flightNo)
                        .param("page", "0").param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.bookings.length()").value(1))
                .andReturn().getResponse().getContentAsString();

        String page1 = mockMvc.perform(get("/api/admin/payments").param("search", flightNo)
                        .param("page", "1").param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        String idOnPage0 = firstRowId(page0);
        String idOnPage1 = firstRowId(page1);

        assertThat(idOnPage0).as("the pages are disjoint").isNotEqualTo(idOnPage1);
        assertThat(List.of(idOnPage0, idOnPage1))
                .containsExactlyInAnyOrder(String.valueOf(first), String.valueOf(second));
    }

    /**
     * The four tiles ride with the page, and they are the LEDGER's numbers — not the
     * rows on screen.
     *
     * <p>{@code admin-payments.html} used to sum its tiles in the browser over the whole
     * list it held, which is only possible while the list <i>is</i> the ledger: the moment
     * the table pages server-side, a client-side "Collected" describes the eight rows on
     * screen and drops as the admin walks to page 2. So the totals are {@code sum} /
     * {@code count} queries — asserted here against those same queries rather than against
     * a literal, which is what proves the tiles and the rows cannot drift.
     *
     * <p>They are also deliberately <b>immune to the toolbar</b>: the tile row summarises
     * the gateway's traffic, while the filtered figure is the count line beside the search
     * box. And a refund <i>moves</i> money between two tiles without creating a second
     * transaction, because it is a status change on one payment row.
     */
    @Test
    void theLedgerCarriesTheTilesAndTheyDescribeTheWholeLedger() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int paid = book(fixture, flightNo, 1);
        int alsoPaid = book(fixture, flightNo, 1);
        int awaiting = book(fixture, flightNo, 1);

        settle(paid, "esewa", null);
        settle(alsoPaid, "esewa", null);
        mockMvc.perform(initiate(awaiting, "esewa", null)).andExpect(status().isOk());

        refresh();
        long ledger = payments.count();
        BigDecimal collected = payments.sumAmountByStatus("SUCCESS");
        BigDecimal alreadyRefunded = payments.sumAmountByStatus("REFUNDED");
        assertThat(collected).as("the aggregate answers with money, not SQL's null").isPositive();

        JsonNode stats = ledgerBody(flightNo, "size", "2").get("stats");
        assertThat(stats).as("a paged read carries the tiles").isNotNull();
        assertThat(stats.get("transactions").asLong())
                .as("every transaction in the ledger, not the page's two rows")
                .isEqualTo(ledger);
        assertThat(stats.get("collected").decimalValue()).isEqualByComparingTo(collected);
        // Against the query, for the same reason as `refunded` below: this is a shared
        // LIVE ledger, and a booking somebody left unpaid (the storefront mints PENDING
        // bookings and an abandoned one outlives the tab) is a second PENDING row that
        // this test did not make. The tile's claim is that it counts the whole ledger
        // rather than the page — so it is compared with the ledger's own count, not 1.
        assertThat(stats.get("pending").asLong())
                .isEqualTo(payments.countByStatusIgnoreCase("PENDING"));
        // Against the query, not zero: the tiles span the whole ledger, and the live
        // database holds seeded refunds this test did not make (which is the semantics
        // under test — a filtered read must not narrow a tile).
        assertThat(stats.get("refunded").decimalValue())
                .isEqualByComparingTo(alreadyRefunded);

        // The toolbar does not move the tiles: one Pending row on screen, the whole
        // ledger in the tiles.
        JsonNode filtered = ledgerBody(flightNo, "status", "Pending", "size", "1");
        assertThat(filtered.get("totalElements").asLong()).isEqualTo(1);
        assertThat(filtered.get("stats").get("transactions").asLong()).isEqualTo(ledger);
        assertThat(filtered.get("stats").get("collected").decimalValue())
                .isEqualByComparingTo(collected);

        // A refund is a status change on the one row: the money changes tile, and the
        // ledger does not grow a second transaction.
        BigDecimal refunded = require(paid).getTotalAmount();
        mockMvc.perform(refund(paid)).andExpect(status().isOk());
        JsonNode after = ledgerBody(flightNo, "size", "2").get("stats");
        assertThat(after.get("refunded").decimalValue())
                .isEqualByComparingTo(alreadyRefunded.add(refunded));
        assertThat(after.get("collected").decimalValue())
                .isEqualByComparingTo(collected.subtract(refunded));
        assertThat(after.get("transactions").asLong()).isEqualTo(ledger);

        // The unpaged shape is unchanged: the tiles, like the paging counts, exist only
        // when paging was asked for.
        mockMvc.perform(get("/api/admin/payments").param("search", flightNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats").doesNotExist());
    }

    /**
     * A booking that never reached the gateway is not a transaction — the ledger
     * holds payments, not bookings, which is what makes the page's "Transactions"
     * tile count transactions.
     */
    @Test
    void aBookingWithNoPaymentRowIsNotInTheLedger() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, 1);

        assertThat(ledgerCount(flightNo)).as("booked but never initiated").isZero();

        mockMvc.perform(initiate(bookingId, "esewa", null)).andExpect(status().isOk());
        assertThat(ledgerCount(flightNo)).as("initiating is what puts it on the ledger").isEqualTo(1);
    }

    /* ================================================================== *
     *  the refund                                                        *
     * ================================================================== */

    /**
     * The page's refund button: two rows change (the payment row and the booking's
     * {@code paymentStatus}) and nothing else does — the booking stays confirmed,
     * its seats stay counted and its ticket stays issued, because cancelling is a
     * separate admin decision (R4).
     */
    @Test
    void refundingMarksBothRowsAndTouchesNothingElse() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, 2);
        settle(bookingId, "esewa", null);

        mockMvc.perform(refund(bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(String.valueOf(bookingId)))
                .andExpect(jsonPath("$.paymentStatus").value(REFUNDED))
                .andExpect(jsonPath("$.status").value("Confirmed"));

        refresh();

        Payment payment = payment(payments.findByBookingId(bookingId));
        assertThat(payment.getStatus()).isEqualTo("REFUNDED");
        assertThat(payment.getPaidAt()).as("the money really did move once").isNotNull();

        Booking booking = require(bookingId);
        assertThat(booking.getPaymentStatus()).isEqualTo(REFUNDED);
        assertThat(booking.getBookingStatus()).as("a refund is not a cancellation").isEqualTo(CONFIRMED);
        assertThat(bookedSeats(flightNo)).isEqualTo(2);
        assertThat(tickets.findByBookingId(bookingId).orElseThrow().getStatus()).isEqualTo("ISSUED");
    }

    /** A doubled refund is a no-op, not an error — the page's button can be pressed twice. */
    @Test
    void refundingIsIdempotent() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, 1);
        settle(bookingId, "esewa", null);

        mockMvc.perform(refund(bookingId)).andExpect(status().isOk());
        mockMvc.perform(refund(bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentStatus").value(REFUNDED));

        refresh();
        assertThat(payment(payments.findByBookingId(bookingId)).getStatus()).isEqualTo("REFUNDED");
    }

    /**
     * Nothing was collected, so there is nothing to give back — for a transaction
     * still awaiting the gateway and for a booking that never had one.
     */
    @Test
    void refundingAPaymentThatNeverSucceededIsRefused() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int initiated = book(fixture, flightNo, 1);
        int noPayment = book(fixture, flightNo, 1);

        mockMvc.perform(initiate(initiated, "esewa", null)).andExpect(status().isOk());

        mockMvc.perform(refund(initiated))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PAYMENT_NOT_REFUNDABLE"));

        mockMvc.perform(refund(noPayment))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PAYMENT_NOT_REFUNDABLE"));

        refresh();
        assertThat(payment(payments.findByBookingId(initiated)).getStatus()).isEqualTo("PENDING");
    }

    /** A refunded transaction cannot be paid again — that is a finance action, not a retry. */
    @Test
    void verifyingAfterARefundIsRefused() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, 1);
        String txnId = settle(bookingId, "esewa", null);

        mockMvc.perform(refund(bookingId)).andExpect(status().isOk());

        mockMvc.perform(verify(bookingId, txnId, "esewa", null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PAYMENT_ALREADY_REFUNDED"));
    }

    /* ================================================================== *
     *  authorization                                                     *
     * ================================================================== */

    /**
     * An explicit check beside {@code AdminRouteAuthorizationTest}'s sweep, because
     * the ledger is where every customer's money trail becomes readable: anonymous is
     * 401, a signed-in {@code USER} is 403 — on the read <i>and</i> on the refund.
     *
     * <p>The public half is asserted by not sending a token anywhere above: the whole
     * gateway flow in this class is anonymous, which is the storefront's own
     * behaviour and not an accident of the harness.
     */
    @Test
    void onlyAnAdminReachesTheLedgerAndTheRefund() throws Exception {
        mockMvc.perform(get("/api/admin/payments"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("NOT_AUTHENTICATED"));

        String userToken = jwtUtil.generate(2, "Anju Karki", "anju.karki@example.com", "USER");
        mockMvc.perform(get("/api/admin/payments").header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        mockMvc.perform(post("/api/admin/payments/1/refund")
                        .header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    /**
     * A declined attempt that the customer retries moves <b>both</b> rows.
     *
     * <p>A decline is written on the payment row ({@code FAILED}) <i>and</i> on the
     * booking's {@code paymentStatus} ({@code Failed}), because the booking is what the
     * admin surfaces show. The retry replaced only the payment row, so the booking went on
     * reporting a failure behind a transaction that was live again — and the two rows
     * describe one attempt.
     *
     * <p>What the disagreement cost, and why this test asserts the rendered value as well
     * as the column: the Payments ledger's filter reads the payment row while its Status
     * column prints the booking's field, so the retried transaction came back under the
     * <b>Pending</b> filter displaying <b>"Failed"</b>. One attempt, two rows, one of them
     * moved — the same shape as a ticket column derived from its booking.
     */
    @Test
    void retryingADeclinedAttemptMovesTheBookingBackWithThePayment() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 8, "8299.99");
        int bookingId = book(fixture, flightNo, 1);

        settle(bookingId, "esewa", "FAILED");
        assertThat(payments.findByBookingId(bookingId).orElseThrow().getStatus())
                .as("the decline is on the payment row").isEqualTo("FAILED");
        assertThat(bookings.findById(bookingId).orElseThrow().getPaymentStatus())
                .as("and on the booking, which is what the admin pages print").isEqualTo("Failed");

        mockMvc.perform(initiate(bookingId, "esewa", null)).andExpect(status().isOk());
        refresh();

        assertThat(payments.findByBookingId(bookingId).orElseThrow().getStatus())
                .as("the retry is a live transaction").isEqualTo("PENDING");
        assertThat(bookings.findById(bookingId).orElseThrow().getPaymentStatus())
                .as("and the booking says so too — one attempt, moved together")
                .isEqualTo("Pending");

        JsonNode rows = ledgerBody(flightNo, "status", "Pending").get("bookings");
        assertThat(rows).as("the retried transaction is what the filter finds").isNotEmpty();
        assertThat(rows.get(0).get("paymentStatus").asText())
                .as("and the row it renders does not disagree with the filter that found it")
                .isEqualTo("Pending");
    }

    /* ------------------------------------------------------------------ *
     *  request helpers                                                   *
     * ------------------------------------------------------------------ */

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder initiate(
            int bookingId, String method, String amount) {
        return post("/api/payments/initiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"bookingId":%d,"method":"%s","amount":%s,"promoCode":"YATRA10"}
                        """.formatted(bookingId, method, amount == null ? "null" : amount));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder verify(
            int bookingId, String txnId, String method, String outcome) {
        return post("/api/payments/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"txnId":"%s","bookingId":%d,"method":"%s","amount":1,"productAmount":1,
                         "customerName":"Ignored","outcome":%s,
                         "booking":{"contact":{"firstName":"Re-sent by the mock page"},"passengers":[]}}
                        """.formatted(txnId, bookingId, method,
                        outcome == null ? "null" : "\"" + outcome + "\""));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder refund(int bookingId) {
        return post("/api/admin/payments/" + bookingId + "/refund")
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()));
    }

    /** Initiate, then verify — the whole gateway walk, returning the reference used. */
    private String settle(int bookingId, String method, String outcome) throws Exception {
        mockMvc.perform(initiate(bookingId, method, null)).andExpect(status().isOk());

        String txnId = txnId();
        mockMvc.perform(verify(bookingId, txnId, method, outcome)).andExpect(status().isOk());
        refresh();
        return txnId;
    }

    private int ledgerCount(String term) throws Exception {
        return filteredLedgerCount(null, "search", term);
    }

    /** The ledger filtered by the flight number plus one more parameter. */
    private int filteredLedgerCount(String flightNo, String parameter, String value) throws Exception {
        var request = get("/api/admin/payments").param(parameter, value)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()));
        if (flightNo != null) {
            request = request.param("search", flightNo);
        }

        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).get("bookings").size();
    }

    /**
     * One paged ledger read, scoped to a flight number, as parsed JSON — so the tiles can
     * be compared against the queries that produce them instead of against literals. The
     * remaining arguments are {@code name, value} pairs.
     */
    private JsonNode ledgerBody(String flightNo, String... params) throws Exception {
        var request = get("/api/admin/payments").param("search", flightNo)
                .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()));
        for (int i = 0; i + 1 < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }

        String body = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body);
    }

    private String firstRowId(String body) throws Exception {
        JsonNode rows = objectMapper.readTree(body).get("bookings");
        assertThat(rows.size()).isEqualTo(1);
        return rows.get(0).get("id").asText();
    }

    private String pnrOf(String body) throws Exception {
        return objectMapper.readTree(body).get("pnr").asText();
    }

    /* ------------------------------------------------------------------ *
     *  fixtures                                                          *
     * ------------------------------------------------------------------ */

    /** Creates a booking through the public endpoint and returns its id. */
    private int book(Fixture fixture, String flightNo, int passengerCount) throws Exception {
        String body = mockMvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(fixture, flightNo, passengerCount)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).get("bookingId").asInt();
    }

    /** A transaction id in the mock page's own shape: {@code "9A" + 8 digits}. */
    private static String txnId() {
        return "9A" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
    }

    private long bookedSeats(String flightNo) {
        int flightId = flights.findByFlightNo(flightNo).orElseThrow().getId();
        return seats.countByFlightIdAndStatus(flightId, "BOOKED");
    }

    private Booking require(int bookingId) {
        return bookings.findById(bookingId).orElseThrow();
    }

    private static Payment payment(Optional<Payment> row) {
        assertThat(row).as("a payment row must exist by now").isPresent();
        return row.orElseThrow();
    }

    /** Flushes and drops the persistence context, so the next read comes from the database. */
    private void refresh() {
        entityManager.flush();
        entityManager.clear();
    }

    /**
     * One test's rows, isolated from every other test <i>and</i> from whatever the
     * live database already holds — the same device Phase 6/9's suites use, and for
     * the same reason: the admin search must not be able to find a seeded demo row
     * that happens to share a name.
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
                        .content("""
                                {"no":"%s","airlineId":%d,"from":"%s","to":"%s",
                                 "dep":"06:50","arr":"07:35","aircraft":"ATR 72","fare":%s,"seats":%d,"status":"Active"}
                                """.formatted(no, fixture.airline().getId(), fixture.from().getCode(),
                                fixture.to().getCode(), fare, capacity)))
                .andExpect(status().isOk());

        return no;
    }

    /**
     * The page's own payload — contact block, one passenger per entry, and the
     * selected-flight record. Seat numbers are omitted (a {@code null} in the seat
     * list), which is what the wizard sends today, so the service assigns the
     * lowest-numbered free seats exactly as it does for a real booking.
     */
    private static String bookingBody(Fixture fixture, String flightNo, int passengerCount) {
        List<String> rows = new ArrayList<>();
        for (int index = 0; index < passengerCount; index++) {
            rows.add("""
                    {"title":"Mr","firstName":"Hari%s","middleName":"Prasad","lastName":"Sharma","nationality":"Nepali","type":"ADT"}"""
                    .formatted(fixture.tag()));
        }

        return """
                {"contact":{"title":"Mr","firstName":"Hari%s","middleName":"Prasad","lastName":"Sharma",
                 "email":"%s","phone":"%s","invoiceParty":"Self","panNo":"","isPassenger":true},
                 "passengers":[%s],
                 "flight":{"from":"%s","to":"%s","date":"%s","depart":"06:50","arrive":"07:35","flightNo":"%s",
                 "airline":"Air","flightClass":"E Class","refundable":true,"pricePerPassenger":8299.99,
                 "passengerCount":%d,"totalPrice":16599.98},
                 "amount":1}"""
                .formatted(fixture.tag(), fixture.contactEmail(), fixture.contactPhone(),
                        String.join(",", rows), fixture.from().getCode(), fixture.to().getCode(),
                        LocalDate.now(), flightNo, passengerCount);
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
