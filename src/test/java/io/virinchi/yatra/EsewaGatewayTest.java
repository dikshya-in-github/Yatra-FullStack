package io.virinchi.yatra;

import io.virinchi.yatra.Config.EsewaConfig;
import io.virinchi.yatra.Dto.EsewaStatusResponse;
import io.virinchi.yatra.Exception.ServiceUnavailableException;
import io.virinchi.yatra.Model.Airline;
import io.virinchi.yatra.Model.Booking;
import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Model.Payment;
import io.virinchi.yatra.Repository.AirlineRepository;
import io.virinchi.yatra.Repository.BookingRepository;
import io.virinchi.yatra.Repository.DestinationRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import io.virinchi.yatra.Repository.PaymentRepository;
import io.virinchi.yatra.Repository.SeatRepository;
import io.virinchi.yatra.Repository.TicketRepository;
import io.virinchi.yatra.Security.JwtUtil;
import io.virinchi.yatra.Service.EsewaSignature;
import io.virinchi.yatra.Service.EsewaStatusClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Fix-plan §10 — the real eSewa ePay v2 flow: the signed handoff, both callbacks, and
 * everything they refuse.
 *
 * <h2>The status API is a mock, and that is the design</h2>
 * <p>{@link EsewaStatusClient} is replaced with {@code @MockitoBean}, so the whole
 * callback path — signature check, status check, amount check, settle, ticket — runs
 * here with <b>no socket opened anywhere</b>. The project's rule is that the suite needs
 * no third-party credentials (R15); a test that dialled eSewa's sandbox would be slower,
 * flakier, and would fail on the day their UAT environment is down for reasons that have
 * nothing to do with this code.
 *
 * <p>What that means for what is <i>not</i> proven here, stated plainly: the live
 * round trip to eSewa's hosted page and back is a manual walk (the credentials and the
 * two amounts worth trying are in {@code application-local.properties}), and this class
 * cannot stand in for it. What it does prove is every rule this project owns — the
 * signature is reproduced over the payload's own field list, a tampered one is refused,
 * a claim eSewa contradicts is refused, an amount that is not the booking's is refused,
 * a stale reference is refused, an unreachable status API settles nothing, and a
 * confirmed payment settles exactly once.
 *
 * <h2>The payloads are signed with the same helper the server uses</h2>
 * <p>That is a deliberate weakness of the test rather than an oversight: it means the
 * signature <i>format</i> is not independently checked here — {@code EsewaSignatureTest}
 * is where the byte-exact published pair is frozen. What these tests check is that the
 * flow requires the signature to hold, which is the part a forged callback attacks.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class EsewaGatewayTest {

    private static final String ADMIN = "ADMIN";
    private static final String PENDING = "PENDING";
    private static final String CONFIRMED = "CONFIRMED";
    private static final String SUCCESS = "SUCCESS";
    private static final String FAILED = "FAILED";
    private static final String PENDING_PAYMENT = "Pending";
    private static final String PAID = "Paid";
    private static final String FAILED_PAYMENT = "Failed";

    /** The fare the fixture flight is created with, so one passenger owes exactly this. */
    private static final String FARE = "8299.99";

    /** The six fields eSewa signs on the way back, in eSewa's own order. */
    private static final String CALLBACK_FIELDS =
            "transaction_code,status,total_amount,transaction_uuid,product_code,signed_field_names";

    /** The hidden inputs the checkout page renders, in the order it renders them. */
    private static final Pattern FORM_INPUT =
            Pattern.compile("name=\"([^\"]+)\" value=\"([^\"]*)\"");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private jakarta.persistence.EntityManager entityManager;
    @Autowired private EsewaConfig esewaConfig;
    @Autowired private AirlineRepository airlines;
    @Autowired private DestinationRepository destinations;
    @Autowired private FlightRepository flights;
    @Autowired private BookingRepository bookings;
    @Autowired private PaymentRepository payments;
    @Autowired private TicketRepository tickets;
    @Autowired private SeatRepository seats;
    @Autowired private JwtUtil jwtUtil;

    /** eSewa's server-to-server answer. Mocked so no test here needs the network. */
    @MockitoBean private EsewaStatusClient statusClient;

    /* ================================================================== *
     *  the handoff                                                       *
     * ================================================================== */

    /**
     * {@code initiate} now points the wizard at this server's own signed handoff, and
     * stores the transaction reference the callback will be matched by.
     */
    @Test
    void initiatePointsTheWizardAtTheSignedCheckoutPage() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 2);
        int bookingId = book(fixture, flightNo);

        mockMvc.perform(initiateRequest(bookingId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.gatewayRedirect")
                        .value("/api/payments/esewa/checkout/" + bookingId));

        refresh();
        assertThat(payment(payments.findByBookingId(bookingId)).getTxnId())
                .as("the reference the callback is matched by")
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    /**
     * The checkout page is a real signed form: its action is eSewa's hosted page, its
     * fields are the eleven ePay v2 requires, and its signature reproduces over the
     * field list it declares — with the payload's own values, which is the only thing
     * eSewa will check.
     */
    @Test
    void theCheckoutPageIsASignedFormForEsewa() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 2);
        int bookingId = book(fixture, flightNo);
        mockMvc.perform(initiateRequest(bookingId)).andExpect(status().isOk());

        String html = mockMvc.perform(get("/api/payments/esewa/checkout/" + bookingId))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "action=\"" + esewaConfig.formUrl() + "\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("esewaForm")))
                .andReturn().getResponse().getContentAsString();

        Map<String, String> fields = formFields(html);

        assertThat(fields.get("total_amount")).as("one passenger at the fixture fare").isEqualTo(FARE);
        assertThat(fields.get("amount")).isEqualTo(FARE);
        assertThat(fields.get("product_code")).isEqualTo(esewaConfig.productCode());
        assertThat(fields.get("signed_field_names"))
                .isEqualTo("total_amount,transaction_uuid,product_code");
        assertThat(fields.get("success_url")).endsWith("/api/payments/esewa/success/" + bookingId);
        assertThat(fields.get("failure_url")).endsWith("/api/payments/esewa/failure/" + bookingId);
        assertThat(fields.get("success_url")).as("an absolute URL is what eSewa redirects to")
                .startsWith("http");

        // And the signature the page carries is the one the declared fields produce.
        assertThat(EsewaSignature.sign(
                EsewaSignature.names(fields.get("signed_field_names")), fields, esewaConfig.secretKey()))
                .as("the served form verifies against its own fields")
                .isEqualTo(fields.get("signature"));
    }

    /** A booking whose transaction was never opened has nothing to hand off. */
    @Test
    void theCheckoutRefusesABookingWithNoTransaction() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 2);
        int bookingId = book(fixture, flightNo);

        mockMvc.perform(get("/api/payments/esewa/checkout/" + bookingId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PAYMENT_NOT_INITIATED"));
    }

    /* ================================================================== *
     *  the success callback                                              *
     * ================================================================== */

    /**
     * The checkpoint: a signed {@code COMPLETE} callback that eSewa's own status API
     * agrees with confirms the booking, records the payment as {@code SUCCESS}, mints
     * the ticket, and sends the customer to the e-ticket.
     *
     * <p>The status answer is stubbed, but its shape is not made up — see
     * {@link #theRealStatusBodyBindsToTheDto()} for the same body as the sandbox returns
     * it, and this and every other stub here read their call arguments the way
     * {@code EsewaStatusClient} does.
     */
    @Test
    void aConfirmedCallbackSettlesTheBookingAndRedirectsToTheETicket() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 2);
        int bookingId = book(fixture, flightNo);
        String uuid = initiate(bookingId);

        statusComplete(uuid);

        mockMvc.perform(get("/api/payments/esewa/success/" + bookingId).param("data", callback(uuid, "COMPLETE", FARE)))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "/eticket.html?bookingId=" + bookingId + "&payment=success"));

        refresh();

        Booking booking = require(bookingId);
        assertThat(booking.getBookingStatus()).isEqualTo(CONFIRMED);
        assertThat(booking.getPaymentStatus()).isEqualTo(PAID);

        Payment payment = payment(payments.findByBookingId(bookingId));
        assertThat(payment.getStatus()).isEqualTo(SUCCESS);
        assertThat(payment.getTxnId()).as("the reference this server sent survives the round trip")
                .isEqualTo(uuid);
        assertThat(payment.getPaidAt()).isNotNull();

        assertThat(tickets.findByBookingId(bookingId)).as("a confirmed sale has a ticket").isPresent();
        assertThat(bookedSeats(flightNo)).as("the seat was held at booking time and stays held").isEqualTo(1);
    }

    /**
     * A customer refreshing the tab eSewa left them on must get their e-ticket, not a
     * 409 — the payment row is found by the same reference, and settling it again is a
     * no-op that answers with the sale that already exists.
     */
    @Test
    void theSameCallbackTwiceSettlesOnceAndStillReachesTheETicket() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 2);
        int bookingId = book(fixture, flightNo);
        String uuid = initiate(bookingId);
        statusComplete(uuid);

        String data = callback(uuid, "COMPLETE", FARE);

        mockMvc.perform(get("/api/payments/esewa/success/" + bookingId).param("data", data))
                .andExpect(status().isFound());
        mockMvc.perform(get("/api/payments/esewa/success/" + bookingId).param("data", data))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "/eticket.html?bookingId=" + bookingId + "&payment=success"));

        refresh();

        // ticket.booking_id is UNIQUE, so "one ticket" is a schema fact rather than a
        // count of what the endpoint returned twice.
        assertThat(tickets.findByBookingId(bookingId)).isPresent();
        assertThat(payment(payments.findByBookingId(bookingId)).getStatus()).isEqualTo(SUCCESS);
        assertThat(require(bookingId).getBookingStatus()).isEqualTo(CONFIRMED);
    }

    /**
     * The forgery this flow exists to refuse, in its simplest form: change one character
     * of the payload and the signature no longer matches. Nothing is settled — the
     * booking keeps its seats and its pending state.
     */
    @Test
    void aTamperedPayloadIsRefusedAndNothingIsSettled() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 2);
        int bookingId = book(fixture, flightNo);
        String uuid = initiate(bookingId);
        statusComplete(uuid);

        // Signed over 8299.99, then the amount is rewritten to 1.00 before it is sent —
        // which is what a customer editing the redirect's Base64 would try. The edit is
        // made on the JSON and the Base64 is taken afterwards, so the payload really does
        // decode to the tampered figure rather than to a corrupted encoding.
        String data = base64(signedPayload(uuid, "COMPLETE", FARE)
                .replace("\"total_amount\":\"8299.99\"", "\"total_amount\":\"1.00\""));

        mockMvc.perform(get("/api/payments/esewa/success/" + bookingId).param("data", data))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ESEWA_SIGNATURE_INVALID"));

        assertNothingSettled(bookingId, flightNo);
    }

    /**
     * A callback that claims success while eSewa's ledger says otherwise — the shape a
     * forgery-with-a-key takes, since the signature checked out. Nothing is settled in
     * either direction: the booking is not confirmed, and it is not failed either, because
     * failing it would act on the same untrusted claim.
     */
    @Test
    void aCallbackClaimingSuccessIsRefusedWhenEsewaDisagrees() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 2);
        int bookingId = book(fixture, flightNo);
        String uuid = initiate(bookingId);

        // eSewa has no record of it, the payload says COMPLETE.
        when(statusClient.status(any(), any())).thenReturn(
                new EsewaStatusResponse("EPAYTEST", uuid, new BigDecimal(FARE), "NOT_FOUND", null));

        mockMvc.perform(get("/api/payments/esewa/success/" + bookingId)
                        .param("data", callback(uuid, "COMPLETE", FARE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ESEWA_PAYMENT_NOT_CONFIRMED"));

        assertNothingSettled(bookingId, flightNo);
    }

    /**
     * eSewa confirming a <i>different</i> figure than the booking owes settles nothing.
     * The status API is asked about the payment row's own amount, so a mismatch means the
     * answer is about another transaction entirely.
     */
    @Test
    void aCallbackNamingAnotherAmountIsRefused() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 2);
        int bookingId = book(fixture, flightNo);
        String uuid = initiate(bookingId);

        when(statusClient.status(any(), any())).thenReturn(
                new EsewaStatusResponse("EPAYTEST", uuid, new BigDecimal("1.00"), "COMPLETE", "0001TS9"));

        mockMvc.perform(get("/api/payments/esewa/success/" + bookingId)
                        .param("data", callback(uuid, "COMPLETE", FARE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ESEWA_AMOUNT_MISMATCH"));

        assertNothingSettled(bookingId, flightNo);
    }

    /**
     * A signed payload naming a transaction that is not this booking's current attempt —
     * a replayed call from before a re-initiate — is refused. This is what keeps an old
     * success URL from settling a fresh attempt.
     */
    @Test
    void aStaleTransactionReferenceIsRefused() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 2);
        int bookingId = book(fixture, flightNo);

        String firstAttempt = initiate(bookingId);
        String secondAttempt = initiate(bookingId);
        assertThat(secondAttempt).as("a re-initiate mints a new reference").isNotEqualTo(firstAttempt);

        statusComplete(firstAttempt);

        mockMvc.perform(get("/api/payments/esewa/success/" + bookingId)
                        .param("data", callback(firstAttempt, "COMPLETE", FARE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ESEWA_TRANSACTION_NOT_CURRENT"));

        assertNothingSettled(bookingId, flightNo);
    }

    /**
     * A missing payload is refused rather than worked around: without one there is
     * nothing to verify, and asking the status API anyway would confirm bookings from a
     * URL anyone can call.
     */
    @Test
    void aSuccessCallbackWithNoPayloadIsRefused() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 2);
        int bookingId = book(fixture, flightNo);
        String uuid = initiate(bookingId);
        statusComplete(uuid);

        mockMvc.perform(get("/api/payments/esewa/success/" + bookingId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("ESEWA_PAYLOAD_MISSING"));

        assertNothingSettled(bookingId, flightNo);
    }

    /**
     * An unreachable status API is "unknown", not "failed": the customer's payment stays
     * pending, the booking is untouched, and the caller is told the confirmation could
     * not be made. Confirming on an unconfirmed payment is the one outcome that must
     * never happen here.
     */
    @Test
    void anUnreachableStatusCheckSettlesNothing() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 2);
        int bookingId = book(fixture, flightNo);
        String uuid = initiate(bookingId);

        when(statusClient.status(any(), any())).thenThrow(new ServiceUnavailableException(
                "ESEWA_STATUS_CHECK_FAILED", "the eSewa sandbox was not reachable"));

        mockMvc.perform(get("/api/payments/esewa/success/" + bookingId)
                        .param("data", callback(uuid, "COMPLETE", FARE)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("ESEWA_STATUS_CHECK_FAILED"));

        assertNothingSettled(bookingId, flightNo);
    }

    /* ================================================================== *
     *  the failure callback                                              *
     * ================================================================== */

    /**
     * A cancellation arrives from eSewa with nothing to read, and the attempt still has
     * to be recorded — the alternative is a row claiming a live attempt the customer
     * already abandoned. The seats stay held and no ticket appears.
     */
    @Test
    void theFailureCallbackRecordsTheAttemptAndTouchesNothingElse() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 2);
        int bookingId = book(fixture, flightNo);
        initiate(bookingId);

        mockMvc.perform(get("/api/payments/esewa/failure/" + bookingId))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "/payment.html?bookingId=" + bookingId + "&payment=failed"));

        refresh();

        Booking booking = require(bookingId);
        assertThat(booking.getBookingStatus()).as("a declined attempt is still a booking").isEqualTo(PENDING);
        assertThat(booking.getPaymentStatus()).isEqualTo(FAILED_PAYMENT);

        assertThat(payment(payments.findByBookingId(bookingId)).getStatus()).isEqualTo(FAILED);
        assertThat(tickets.findByBookingId(bookingId)).isEmpty();
        assertThat(bookedSeats(flightNo)).as("the seats stay held for the retry").isEqualTo(1);
    }

    /**
     * A failure callback for a transaction that actually succeeded lands the customer on
     * their e-ticket, because the money moved. The payment is not un-paid by a URL.
     */
    @Test
    void aFailureCallbackForAPaidTransactionStillReachesTheETicket() throws Exception {
        Fixture fixture = fixture();
        String flightNo = createFlight(fixture, 2);
        int bookingId = book(fixture, flightNo);
        String uuid = initiate(bookingId);
        statusComplete(uuid);

        mockMvc.perform(get("/api/payments/esewa/success/" + bookingId)
                .param("data", callback(uuid, "COMPLETE", FARE))).andExpect(status().isFound());

        mockMvc.perform(get("/api/payments/esewa/failure/" + bookingId))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        "/eticket.html?bookingId=" + bookingId + "&payment=success"));

        refresh();
        assertThat(payment(payments.findByBookingId(bookingId)).getStatus())
                .as("a paid transaction is not failed by a failure URL")
                .isEqualTo(SUCCESS);
        assertThat(require(bookingId).getBookingStatus()).isEqualTo(CONFIRMED);
    }

    /* ================================================================== *
     *  the wire shape                                                    *
     * ================================================================== */

    /**
     * eSewa's real status body binds to {@link EsewaStatusResponse}.
     *
     * <p>The string below is not invented — it is the sandbox's own answer, captured
     * from {@code https://rc.esewa.com.np/api/epay/transaction/status/} for the example
     * eSewa's documentation uses, during this section's verification. It is replayed here
     * as a literal rather than called live because the suite must not need the network
     * (R15) — but a hand-written fixture would have proved nothing about field names, and
     * two things in this body are exactly what a guessed DTO gets wrong:
     *
     * <ul>
     *   <li><b>Every field name is snake_case on the wire</b> and matches the record's own
     *       names, so no {@code @JsonProperty} is needed. A camel-cased guess would bind
     *       {@code null} and silently skip the amount check.</li>
     *   <li><b>{@code total_amount} is a JSON number ({@code 100.0}), not a string</b> —
     *       which is why the record holds a {@code BigDecimal} and why the comparison is
     *       {@code compareTo}: bound as text, a value like this is at the mercy of how a
     *       double renders.</li>
     * </ul>
     */
    @Test
    void theRealStatusBodyBindsToTheDto() throws Exception {
        String sandboxBody = "{\"product_code\":\"EPAYTEST\",\"transaction_uuid\":\"123\","
                + "\"total_amount\":100.0,\"status\":\"COMPLETE\",\"ref_id\":\"00066XV\"}";

        EsewaStatusResponse parsed = objectMapper.readValue(sandboxBody, EsewaStatusResponse.class);

        assertThat(parsed.status()).isEqualTo("COMPLETE");
        assertThat(parsed.isComplete()).isTrue();
        assertThat(parsed.transaction_uuid()).isEqualTo("123");
        assertThat(parsed.ref_id()).isEqualTo("00066XV");
        assertThat(parsed.total_amount())
                .as("a JSON number, compared by value so 100.0 and 100.00 are the same amount")
                .isEqualByComparingTo("100.00");
    }

    /* ------------------------------------------------------------------ *
     *  payload helpers                                                   *
     * ------------------------------------------------------------------ */

    /**
     * A Base64 callback payload signed the way eSewa signs one — over the fields
     * {@code signed_field_names} lists, in that order, including itself.
     */
    private String callback(String uuid, String status, String amount) {
        return base64(signedPayload(uuid, status, amount));
    }

    /** The callback's JSON, signed over the field list it declares. */
    private String signedPayload(String uuid, String status, String amount) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("transaction_code", "0K3R17T");
        fields.put("status", status);
        //eSewa sends this as a JSON number; the signature covers its string form.
        fields.put("total_amount", amount);
        fields.put("transaction_uuid", uuid);
        fields.put("product_code", esewaConfig.productCode());
        fields.put("signed_field_names", CALLBACK_FIELDS);
        fields.put("signature", EsewaSignature.sign(
                EsewaSignature.names(CALLBACK_FIELDS), fields, esewaConfig.secretKey()));

        return objectMapper.writeValueAsString(fields);
    }

    private static String base64(String json) {
        return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private void statusComplete(String uuid) {
        when(statusClient.status(any(), any())).thenReturn(new EsewaStatusResponse(
                esewaConfig.productCode(), uuid, new BigDecimal(FARE), "COMPLETE", "0007G36"));
    }

    /** The hidden inputs of the checkout page, as sent. */
    private static Map<String, String> formFields(String html) {
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher matcher = FORM_INPUT.matcher(html);
        while (matcher.find()) {
            fields.put(matcher.group(1), matcher.group(2));
        }
        assertThat(fields).as("the page posts the signed fields").isNotEmpty();
        return fields;
    }

    /** Nothing was confirmed, failed or ticketed — the state after a refusal. */
    private void assertNothingSettled(int bookingId, String flightNo) {
        refresh();
        assertThat(require(bookingId).getBookingStatus()).isEqualTo(PENDING);
        assertThat(payment(payments.findByBookingId(bookingId)).getStatus())
                .as("still awaiting the gateway").isEqualTo(PENDING);
        assertThat(tickets.findByBookingId(bookingId)).isEmpty();
        assertThat(bookedSeats(flightNo)).isEqualTo(1);
    }

    /* ------------------------------------------------------------------ *
     *  fixtures                                                          *
     * ------------------------------------------------------------------ */

    /** Opens a transaction and answers the transaction reference it minted. */
    private String initiate(int bookingId) throws Exception {
        mockMvc.perform(initiateRequest(bookingId)).andExpect(status().isOk());
        refresh();
        return payment(payments.findByBookingId(bookingId)).getTxnId();
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder initiateRequest(int bookingId) {
        return post("/api/payments/initiate")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"bookingId\":%d,\"method\":\"esewa\",\"amount\":1}".formatted(bookingId));
    }

    private record Fixture(String tag, String flightNo, Airline airline, Destination from, Destination to) {

        String contactEmail() {
            return "hari-" + tag.toLowerCase() + "@example.com";
        }

        /** 10 digits, unique per test run, in the roster's stored form. */
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

    /** Creates a flight through the real admin endpoint, which also builds its seat map. */
    private String createFlight(Fixture fixture, int capacity) throws Exception {
        mockMvc.perform(post("/api/admin/flights")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"no":"%s","airlineId":%d,"from":"%s","to":"%s",
                                 "dep":"06:50","arr":"07:35","aircraft":"ATR 72","fare":%s,"seats":%d,"status":"Active"}
                                """.formatted(fixture.flightNo(), fixture.airline().getId(),
                                fixture.from().getCode(), fixture.to().getCode(), FARE, capacity)))
                .andExpect(status().isOk());

        return fixture.flightNo();
    }

    /** Books one passenger through the public endpoint and answers the new booking id. */
    private int book(Fixture fixture, String flightNo) throws Exception {
        String body = mockMvc.perform(post("/api/bookings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bookingBody(fixture, flightNo)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).get("bookingId").asInt();
    }

    private static String bookingBody(Fixture fixture, String flightNo) {
        return """
                {"contact":{"title":"Mr","firstName":"Hari%s","middleName":"Prasad","lastName":"Sharma",
                 "email":"%s","phone":"%s","invoiceParty":"Self","panNo":"","isPassenger":true},
                 "passengers":[{"title":"Mr","firstName":"Hari%s","middleName":"Prasad","lastName":"Sharma","nationality":"Nepali","type":"ADT"}],
                 "flight":{"from":"%s","to":"%s","date":"%s","depart":"06:50","arrive":"07:35","flightNo":"%s",
                 "airline":"Air","flightClass":"E Class","refundable":true,"pricePerPassenger":8299.99,
                 "passengerCount":1,"totalPrice":8299.99},
                 "amount":1}
                """.formatted(fixture.tag(), fixture.contactEmail(), fixture.contactPhone(),
                fixture.tag(), fixture.from().getCode(), fixture.to().getCode(),
                LocalDate.now(), flightNo);
    }

    private void refresh() {
        entityManager.flush();
        entityManager.clear();
    }

    private Booking require(int bookingId) {
        return bookings.findById(bookingId).orElseThrow();
    }

    private static Payment payment(Optional<Payment> row) {
        assertThat(row).as("a payment row must exist by now").isPresent();
        return row.orElseThrow();
    }

    private long bookedSeats(String flightNo) {
        int flightId = flights.findByFlightNo(flightNo).orElseThrow().getId();
        return seats.countByFlightIdAndStatus(flightId, "BOOKED");
    }

    private String adminToken() {
        return jwtUtil.generate(1, "Dikshya Ghising", "admin@gmail.com", ADMIN);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    private static String tag() {
        return "E" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String uniqueFlightNo() {
        for (int attempt = 0; attempt < 50; attempt++) {
            String candidate = "E9 " + String.format("%04d",
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

    private Destination seedDestination(String tag) {
        Destination destination = new Destination();
        destination.setCity("City " + tag);
        destination.setCode(uniqueAirportCode());
        destination.setAirport(tag + " Airport");
        destination.setStatus("Active");
        return destinations.save(destination);
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
