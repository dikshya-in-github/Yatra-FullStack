package io.virinchi.yatra.Service;

import io.virinchi.yatra.Config.EsewaConfig;
import io.virinchi.yatra.Dto.EsewaStatusResponse;
import io.virinchi.yatra.Exception.ConflictException;
import io.virinchi.yatra.Exception.ResourceNotFoundException;
import io.virinchi.yatra.Exception.ServiceUnavailableException;
import io.virinchi.yatra.Exception.ValidationException;
import io.virinchi.yatra.Model.Booking;
import io.virinchi.yatra.Model.Payment;
import io.virinchi.yatra.Repository.BookingRepository;
import io.virinchi.yatra.Repository.PaymentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The real eSewa ePay v2 flow — fix-plan §10.
 *
 * <h2>What this replaces, and why nothing was patched</h2>
 * <p>Until this class existed, "pay with eSewa" walked four pages that recreated
 * eSewa's own login, OTP and balance screens as static Yatra HTML
 * ({@code esewaLogin.html} → {@code esewaOtp.html} → {@code esewaBalance.html} →
 * {@code esewaConfirm.html}), and the backend's {@code verify} was told the outcome
 * by the page. That is not an integration: eSewa's login, OTP and wallet balance are
 * eSewa's own UI on eSewa's own servers, and reproducing them meant there was never a
 * correct value to put on those screens. The flow below is the real one — the customer
 * <b>leaves this site</b> and comes back to a callback.
 *
 * <h2>The three steps, and what each one is allowed to trust</h2>
 * <pre>
 * 1. handoff   POST /api/payments/initiate minted the transaction_uuid and stored it
 *              on the payment row, then answered gatewayRedirect =
 *              /api/payments/esewa/checkout/{bookingId} (see PaymentInitiateResponse)
 *        ↓
 *    checkout({bookingId})  signs the eight fields and returns an HTML page that
 *              POSTs itself to eSewa. The customer is now on eSewa's domain; this
 *              application is not involved at all until the callback.
 *        ↓
 * 2. callback  eSewa redirects the browser to success_url / failure_url with a
 *              Base64 {@code ?data=} parameter.
 *        ↓
 *    success({bookingId}, data)  verifies the payload's signature against a
 *              re-signing of its own fields, then asks eSewa's status API what its
 *              ledger says, then settles through PaymentService.
 *    failure({bookingId}, data)  records a non-success outcome. No confirmation can
 *              come out of this path, which is why it tolerates a missing payload.
 *        ↓
 * 3. settle    PaymentService.settleFromGateway — the same confirm path the mock
 *              gateway and the admin's own button use (rule 6).
 * </pre>
 *
 * <h2>Two independent checks, because a callback is a browser</h2>
 * <p>The redirect is a GET to a public URL, so <b>anyone can call it</b>, with
 * whatever {@code data} they like. Three things therefore have to hold before a
 * booking is confirmed, and they fail independently:
 * <ol>
 *   <li><b>The signature.</b> The payload is re-signed with the shared secret over
 *       the exact field list it names, and a mismatch is refused. This proves the
 *       payload was produced by eSewa (or by whoever holds the key), not by the
 *       customer editing the URL.</li>
 *   <li><b>eSewa's own ledger.</b> {@link EsewaStatusClient} asks the status API.
 *       Only {@code COMPLETE} is paid. A payload that claims {@code COMPLETE} while
 *       eSewa says {@code NOT_FOUND} is exactly the shape a forgery takes, and the
 *       server-to-server answer wins. This is the check that keeps the signature from
 *       being the only lock on the door.</li>
 *   <li><b>Our own stored amount.</b> The transaction is looked up by
 *       {@code transaction_uuid} — the value <i>this</i> server minted for <i>this</i>
 *       booking — and the figure sent to the status API is the payment row's, never
 *       the callback's. A callback naming a cheaper amount cannot buy a booking
 *       cheaper, because the amount it names is never used.</li>
 * </ol>
 *
 * <h2>What the payment row keeps</h2>
 * <p>{@code payment.txn_id} holds the {@code transaction_uuid} — it is the reference
 * <i>this</i> server sent, it is what makes the callback idempotent (a second hit
 * finds the same row and settles to the same answer), and it is the only handle that
 * survives a refresh of the success URL. eSewa's own {@code transaction_code} and
 * {@code ref_id} are logged, not stored: they are eSewa's vocabulary for the same
 * transaction, and there is no column that would not then contradict this one.
 *
 * <h2>Not configured is a first-class answer</h2>
 * <p>With no signing key, {@link #checkout} answers
 * {@code 503 ESEWA_NOT_CONFIGURED} and every other payment endpoint behaves exactly
 * as it did — the same rule Cloudinary's credentials follow, for the same reason (the
 * test suite boots against a live schema and must not need third-party secrets).
 */
@Slf4j
@Service
public class EsewaGatewayService {

    /**
     * The fields eSewa signs on the way <i>in</i>, in eSewa's own documented order.
     *
     * <p>Not alphabetical, and not the full field list: the form posts eleven values
     * and signs three. {@code signed_field_names} must name exactly these, in this
     * order, because that string is what the signature is computed over.
     */
    private static final List<String> SIGNED_FIELDS =
            List.of("total_amount", "transaction_uuid", "product_code");

    /** eSewa's status vocabulary for "the money is in". The only paid value. */
    private static final String COMPLETE = "COMPLETE";

    private final EsewaConfig config;
    private final EsewaStatusClient statusClient;
    private final PaymentService paymentService;
    private final PaymentRepository payments;
    private final BookingRepository bookings;
    private final ObjectMapper objectMapper;

    public EsewaGatewayService(EsewaConfig config,
                               EsewaStatusClient statusClient,
                               PaymentService paymentService,
                               PaymentRepository payments,
                               BookingRepository bookings,
                               ObjectMapper objectMapper) {
        this.config = config;
        this.statusClient = statusClient;
        this.paymentService = paymentService;
        this.payments = payments;
        this.bookings = bookings;
        this.objectMapper = objectMapper;
    }

    /* ------------------------------------------------------------------ *
     *  1. The handoff — the auto-submitting form                          *
     * ------------------------------------------------------------------ */

    /**
     * The signed form that sends the customer to eSewa.
     *
     * <p><b>An HTML page rather than a redirect, because the flow requires a POST.</b>
     * ePay v2 accepts the signed fields as a form POST to its own page; building the
     * form here (instead of having {@code payment.js} assemble it) keeps the signature
     * and the fields it covers in one place on the server, where the secret already
     * lives. The page is deliberately minimal and self-submitting, with a visible
     * manual button for the case JavaScript is off or slow — a customer staring at a
     * blank tab with a form they cannot submit is a worse failure than one extra
     * click.
     *
     * @param bookingId the booking whose pending transaction is being paid for
     * @param baseUrl   this deployment's own {@code scheme://host:port}, taken from the
     *                  request that asked for this page — so the callbacks it registers
     *                  point back at the host the customer is actually using (8080,
     *                  8081, a tunnel), with no configuration to keep in step
     * @throws ServiceUnavailableException 503 — this server has no eSewa configuration
     * @throws ResourceNotFoundException   404 — no such booking
     * @throws ConflictException           409 — no transaction initiated, one already
     *                                     completed, or a cancelled booking
     */
    @Transactional(readOnly = true)
    public String checkout(int bookingId, String baseUrl) {
        if (!config.isConfigured()) {
            throw new ServiceUnavailableException(
                    "ESEWA_NOT_CONFIGURED",
                    "eSewa payments are not configured on this server. Set the eSewa "
                            + "credentials (ESEWA_SECRET_KEY, plus yatra.esewa.form-url / "
                            + "status-url / product-code), or pay by a linked bank account.");
        }

        Booking booking = bookings.findById(bookingId)
                .orElseThrow(() -> ResourceNotFoundException.of("Booking", bookingId));

        Payment payment = payments.findByBookingId(bookingId)
                .orElseThrow(() -> ConflictException.paymentNotInitiated(bookingId));

        String state = upper(payment.getStatus());
        if ("SUCCESS".equals(state) || "REFUNDED".equals(state)) {
            throw ConflictException.paymentAlreadyCompleted(bookingId, state);
        }
        if ("CANCELLED".equals(upper(booking.getBookingStatus()))) {
            throw ConflictException.bookingCancelled(bookingId);
        }

        //The uuid initiate minted. Blank means a row from before §10, which cannot be
        //paid for through the signed flow — re-initiating mints one.
        String uuid = payment.getTxnId();
        if (uuid == null || uuid.isBlank()) {
            throw ConflictException.paymentNotInitiated(bookingId);
        }

        //The amount is the BOOKING's stored total, the same figure initiate recorded and
        //the same one the status API is later asked about. It never comes from a page.
        BigDecimal total = booking.getTotalAmount();

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("amount", total.toPlainString());
        fields.put("tax_amount", "0");
        fields.put("total_amount", total.toPlainString());
        fields.put("transaction_uuid", uuid);
        fields.put("product_code", config.productCode());
        fields.put("product_service_charge", "0");
        fields.put("product_delivery_charge", "0");
        fields.put("success_url", callbackUrl(baseUrl, "success", bookingId));
        fields.put("failure_url", callbackUrl(baseUrl, "failure", bookingId));
        fields.put("signed_field_names", String.join(",", SIGNED_FIELDS));
        fields.put("signature", EsewaSignature.sign(SIGNED_FIELDS, fields, config.secretKey()));

        log.info("eSewa handoff built: booking={} txn={} amount={} — customer is leaving for eSewa",
                bookingId, uuid, total);

        return autoSubmitForm(fields, total);
    }

    /**
     * The callback URLs.
     *
     * <p><b>The booking id is a path segment, not a query parameter.</b> eSewa appends
     * its own {@code ?data=} to whatever it is given, so a URL that already carried a
     * query string would arrive as {@code …?bookingId=7?data=…} — one question mark too
     * many, and the booking id would be lost with the payload still looking fine. A path
     * segment survives that append untouched, which is the whole reason for the shape.
     */
    private static String callbackUrl(String baseUrl, String outcome, int bookingId) {
        String base = String.valueOf(baseUrl == null ? "" : baseUrl).trim();
        //A trailing slash on the base would produce "//api/...", which some proxies
        //treat as a different path.
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/api/payments/esewa/" + outcome + "/" + bookingId;
    }

    /**
     * The self-submitting page.
     *
     * <p><b>Every value is escaped</b> even though all of them are numbers, a UUID or a
     * product code this server chose: {@code success_url} is built from the request's
     * own host, so it is the one value in the set that an attacker can influence (a
     * forged {@code Host} header). Escaping here is what stops that from being an
     * attribute-injection into the form, which would be a script-injection into the
     * customer's browser mid-payment.
     */
    private String autoSubmitForm(Map<String, String> fields, BigDecimal total) {
        StringBuilder inputs = new StringBuilder();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            inputs.append("      <input type=\"hidden\" name=\"").append(escape(field.getKey()))
                    .append("\" value=\"").append(escape(field.getValue())).append("\">\n");
        }

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Redirecting to eSewa…</title>
                  <style>
                    body { font-family: system-ui, -apple-system, "Segoe UI", sans-serif;
                           background: #f4f6f9; color: #1f2937; display: flex;
                           align-items: center; justify-content: center; min-height: 100vh; margin: 0; }
                    .card { background: #fff; border-radius: 12px; padding: 32px 40px; text-align: center;
                            box-shadow: 0 10px 30px rgba(15, 23, 42, .08); max-width: 420px; }
                    h1 { font-size: 18px; margin: 0 0 8px; }
                    p { color: #6b7280; font-size: 14px; margin: 0 0 20px; }
                    .amount { font-size: 26px; font-weight: 700; color: #111827; display: block; margin: 0 0 20px; }
                    button { background: #60bb46; border: 0; border-radius: 8px; color: #fff; cursor: pointer;
                             font-size: 15px; font-weight: 600; padding: 12px 22px; }
                    noscript p { color: #b45309; }
                  </style>
                </head>
                <body>
                  <div class="card">
                    <h1>Taking you to eSewa</h1>
                    <p>Please wait — do not close this tab.</p>
                    <span class="amount">NPR %s</span>
                    <form id="esewaForm" method="POST" action="%s">
                %s      <button type="submit">Continue to eSewa</button>
                    </form>
                    <noscript><p>JavaScript is off, so press the button above to pay.</p></noscript>
                  </div>
                  <script>document.getElementById("esewaForm").submit();</script>
                </body>
                </html>
                """.formatted(total.toPlainString(), escape(config.formUrl()), inputs.toString());
    }

    private static String escape(String value) {
        return String.valueOf(value == null ? "" : value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /* ------------------------------------------------------------------ *
     *  2. The callbacks                                                   *
     * ------------------------------------------------------------------ */

    /**
     * What the customer is redirected with when eSewa finishes.
     *
     * @param success   whether the booking ended up paid and confirmed
     * @param bookingId the booking the transaction belongs to
     * @param pnr       the ticket's PNR on success, blank otherwise
     * @param detail    one line for the log and the report — eSewa's own status word
     */
    public record Outcome(boolean success, int bookingId, String pnr, String detail) {
    }

    /**
     * The success callback: verify, confirm with eSewa's own ledger, settle.
     *
     * <p>A missing or unverified payload is refused rather than worked around. The
     * tempting alternative — trust the booking id in the path and ask the status API
     * anyway — would confirm bookings from a URL anyone can call, and the status API
     * would happily say {@code COMPLETE} for a transaction that <i>is</i> complete,
     * which is exactly the case an attacker would pick. Confirmation requires the
     * signed payload, so this path refuses when it is absent.
     *
     * @throws ValidationException 400 — no payload, unreadable payload, a failed
     *                             signature, or a payment the payload does not match
     * @throws ConflictException   409 — an unknown transaction, or an amount that is
     *                             not the one owed
     * @throws ServiceUnavailableException 503 — eSewa's status API could not be reached.
     *                             Nothing is confirmed and nothing is failed: this is
     *                             "unknown", and the payment stays pending.
     */
    @Transactional
    public Outcome success(int bookingId, String data) {
        Map<String, String> payload = verifiedPayload(data);
        return settle(bookingId, payload, true);
    }

    /**
     * The failure callback: record a non-success outcome.
     *
     * <p><b>It tolerates a missing payload, deliberately.</b> A customer who cancels on
     * eSewa's page can be sent back with no {@code data} at all, and there is nothing
     * to verify when there is nothing to read. Refusing here would leave the payment
     * row saying {@code PENDING} about an attempt the customer already abandoned. The
     * asymmetry with {@link #success} is the point: this path can only record a
     * non-success outcome, so the worst a forged URL can do is mark an unpaid,
     * still-pending booking as failed — which the customer's own next initiate
     * reverses, and which never touches money, seats or tickets.
     *
     * <p>A payload that <i>is</i> present is verified like any other, and a stale one —
     * naming a transaction that is no longer the booking's current attempt — is
     * refused, so a replayed old failure URL cannot fail a fresh attempt.
     */
    @Transactional
    public Outcome fail(int bookingId, String data) {
        Map<String, String> payload = decoded(data).orElse(null);
        if (payload != null) {
            verifySignature(payload);
        }
        return settle(bookingId, payload, false);
    }

    /**
     * The shared second half of both callbacks: find the transaction, ask eSewa, settle.
     *
     * <p>{@code confirming} says which callback this is; the truth about the money comes
     * from the status API in the success case, and is a given in the failure case.
     */
    private Outcome settle(int bookingId, Map<String, String> payload, boolean confirming) {
        Booking booking = bookings.findById(bookingId)
                .orElseThrow(() -> ResourceNotFoundException.of("Booking", bookingId));

        Payment payment = payments.findByBookingId(bookingId)
                .orElseThrow(() -> ConflictException.paymentNotInitiated(bookingId));

        String uuid = transactionUuid(payload, payment, bookingId);

        if (!confirming) {
            //Nothing to ask eSewa: an abandoned or declined attempt is not a paid one,
            //and a callback for a transaction that DID succeed is caught by settle's own
            //idempotency guard (it answers success rather than failing a paid booking).
            log.info("eSewa reported a non-success outcome: booking={} txn={} — booking left "
                    + "PENDING, seats still held", bookingId, uuid);
            return outcome(paymentService.settleFromGateway(bookingId, uuid, false), bookingId);
        }

        //Our stored amount, not the callback's. If they disagree, the status answer is
        //about a different figure than this booking owes and proves nothing about it.
        EsewaStatusResponse status = statusClient.status(uuid, payment.getAmount());

        String eSewaStatus = upper(status == null ? null : status.status());
        String callbackStatus = upper(payload.get("status"));

        if (!COMPLETE.equals(eSewaStatus)) {
            log.warn("eSewa did not confirm payment: booking={} txn={} callbackStatus={} "
                            + "gatewayStatus={} ref={} — settling as unanswered, not as paid",
                    bookingId, uuid, callbackStatus, eSewaStatus, status == null ? "" : status.ref_id());

            //A callback that CLAIMS success while eSewa's ledger disagrees is the shape a
            //forgery takes, so it is logged loudly and refused — the booking is left
            //exactly as it was rather than being failed on the strength of a bad claim.
            if (COMPLETE.equals(callbackStatus)) {
                throw ConflictException.esewaPaymentNotConfirmed(bookingId, eSewaStatus);
            }
            return outcome(paymentService.settleFromGateway(bookingId, uuid, false), bookingId);
        }

        assertAmountMatches(status == null ? null : status.total_amount(), payment, bookingId);

        if (!callbackStatus.equals(eSewaStatus)) {
            //Worth a line: it is the only observable sign of a tampered payload that still
            //passed both checks, and it costs nothing to record.
            log.warn("eSewa status disagreement for booking={} txn={}: callback said {} and the "
                    + "status API said {} — the status API wins", bookingId, uuid, callbackStatus, eSewaStatus);
        }

        log.info("eSewa confirmed payment: booking={} txn={} ref={} amount={} -> settling",
                bookingId, uuid, status.ref_id(), status.total_amount());

        return outcome(paymentService.settleFromGateway(bookingId, uuid, true), bookingId);
    }

    /** One line of translation from the payment response to what the browser is told. */
    private static Outcome outcome(io.virinchi.yatra.Dto.PaymentVerifyResponse settled, int bookingId) {
        boolean success = "SUCCESS".equalsIgnoreCase(String.valueOf(settled.status()));
        return new Outcome(success, bookingId,
                settled.pnr() == null ? "" : settled.pnr(),
                String.valueOf(settled.status()));
    }

    /* ------------------------------------------------------------------ *
     *  Payload handling                                                   *
     * ------------------------------------------------------------------ */

    /**
     * The payload's fields, re-signed and checked.
     *
     * <p>Returns the raw field map rather than the typed record because verification
     * has to hash <i>exactly</i> what arrived, and the field list is driven by the
     * payload's own {@code signed_field_names}. A typed record would have to guess
     * which fields a future eSewa payload adds, and a wrong guess fails closed (fine)
     * or silently drops a field from the message (not fine).
     *
     * @throws ValidationException 400 — no {@code data}, unreadable Base64/JSON, or a
     *                             signature that does not match
     */
    private Map<String, String> verifiedPayload(String data) {
        Map<String, String> payload = decoded(data).orElseThrow(() -> new ValidationException(
                "ESEWA_PAYLOAD_MISSING",
                "eSewa's response could not be read, so this payment was not confirmed. "
                        + "The booking is unchanged and its transaction is still pending."));

        verifySignature(payload);
        return payload;
    }

    /** The Base64 {@code ?data=} parameter as a field map, or empty when there is none. */
    private Optional<Map<String, String>> decoded(String data) {
        String encoded = String.valueOf(data == null ? "" : data).trim();
        if (encoded.isEmpty()) {
            return Optional.empty();
        }

        byte[] decoded;
        try {
            decoded = base64(encoded);
        } catch (IllegalArgumentException ex) {
            log.warn("eSewa's response was not valid Base64 ({} characters)", encoded.length());
            throw new ValidationException("ESEWA_PAYLOAD_UNREADABLE",
                    "eSewa's response could not be decoded, so this payment was not confirmed.");
        }

        try {
            Map<String, String> fields = new LinkedHashMap<>();
            objectMapper.readValue(new String(decoded, StandardCharsets.UTF_8), Map.class)
                    .forEach((key, value) -> fields.put(
                            String.valueOf(key),
                            value == null ? null : String.valueOf(value)));
            return Optional.of(fields);
        } catch (Exception ex) {
            log.warn("eSewa's response was not JSON: {}", ex.getMessage());
            throw new ValidationException("ESEWA_PAYLOAD_UNREADABLE",
                    "eSewa's response could not be read, so this payment was not confirmed.");
        }
    }

    /**
     * eSewa's Base64, with the one browser trap it always carries.
     *
     * <p>{@code +} is a legal Base64 character and is <b>also</b> what a form decoder
     * turns into a space, so a payload that arrived through a query string can have its
     * {@code +}s already replaced. Three attempts, cheapest first: as-is, with spaces
     * restored to {@code +}, and URL-safe. Without this a perfectly valid signature is
     * refused — intermittently, depending on which characters eSewa's own encoding
     * produced — which is the worst kind of bug to debug from a log.
     */
    private static byte[] base64(String encoded) {
        try {
            return Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException first) {
            try {
                return Base64.getDecoder().decode(encoded.replace(' ', '+'));
            } catch (IllegalArgumentException second) {
                return Base64.getUrlDecoder().decode(encoded);
            }
        }
    }

    /** Re-signs what arrived and compares. The one thing a callback cannot fake. */
    private void verifySignature(Map<String, String> payload) {
        List<String> names = EsewaSignature.names(payload.get("signed_field_names"));
        String provided = payload.get("signature");

        if (provided == null || provided.isBlank()) {
            throw new ValidationException("ESEWA_SIGNATURE_MISSING",
                    "eSewa's response carried no signature, so this payment was not confirmed.");
        }

        String expected;
        try {
            expected = EsewaSignature.sign(names, payload, config.secretKey());
        } catch (IllegalArgumentException ex) {
            //signed_field_names names something the payload does not carry — a payload
            //this code cannot honestly verify.
            throw new ValidationException("ESEWA_SIGNATURE_INVALID",
                    "eSewa's response did not match its own signature, so this payment was "
                            + "not confirmed.");
        }

        if (!EsewaSignature.matches(expected, provided)) {
            log.warn("eSewa callback signature did not match over fields [{}]", String.join(",", names));
            throw new ValidationException("ESEWA_SIGNATURE_INVALID",
                    "eSewa's response did not match its own signature, so this payment was "
                            + "not confirmed.");
        }
    }

    /**
     * Which transaction this callback is about.
     *
     * <p>The payload's uuid is the key — it is what ties the callback to the row this
     * server wrote at initiate — and it must be the row's <i>current</i> one, which is
     * what refuses a replayed callback from an earlier attempt. With no payload the
     * row's own uuid is used, which is only reachable from the failure path.
     */
    private String transactionUuid(Map<String, String> payload, Payment payment, int bookingId) {
        String stored = String.valueOf(payment.getTxnId() == null ? "" : payment.getTxnId()).trim();
        String claimed = payload == null
                ? ""
                : String.valueOf(payload.getOrDefault("transaction_uuid", "")).trim();

        if (claimed.isEmpty()) {
            if (stored.isEmpty()) {
                throw ConflictException.paymentNotInitiated(bookingId);
            }
            return stored;
        }

        if (!claimed.equals(stored)) {
            throw ConflictException.esewaTransactionNotCurrent(bookingId, claimed, stored);
        }
        return claimed;
    }

    /**
     * The last check before money is declared received: eSewa's amount is the one owed.
     *
     * <p>It compares the status API's figure — the authoritative one — with the payment
     * row's, and it is the check that closes the "pay one rupee, confirm a 16,599.98
     * booking" hole from the other end: the amount sent to the status API is already
     * ours, so a mismatch here means eSewa's ledger is about a different figure and the
     * answer proves nothing about this booking. A {@code null} from eSewa skips the
     * check rather than failing it — the status word itself came back, and refusing a
     * confirmed payment over an omitted echo would be the worse trade.
     *
     * <p>Compared with {@code compareTo}, never {@code equals}: scale is not part of an
     * amount, and eSewa's own examples send {@code 100.0} where a booking holds
     * {@code 100.00}.
     */
    private void assertAmountMatches(BigDecimal gatewayAmount, Payment payment, int bookingId) {
        if (gatewayAmount == null) {
            log.warn("eSewa's status answer carried no amount for booking={} — cannot cross-check "
                    + "the {} it was asked about", bookingId, payment.getAmount());
            return;
        }

        if (gatewayAmount.compareTo(payment.getAmount()) != 0) {
            throw ConflictException.esewaAmountMismatch(
                    bookingId, payment.getAmount().toPlainString(), gatewayAmount.toPlainString());
        }
    }

    private static String upper(String value) {
        return String.valueOf(value == null ? "" : value).trim().toUpperCase(Locale.ROOT);
    }
}
