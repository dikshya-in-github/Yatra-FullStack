package io.virinchi.yatra.RestAPIController;

import io.virinchi.yatra.Service.EsewaGatewayService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * The real eSewa ePay v2 flow's three endpoints — fix-plan §10.
 *
 * <h2>Why these are GETs and why they are public</h2>
 * <p>One is a page and two are redirects, so GET is the verb eSewa's flow actually
 * uses: the customer's browser is sent to the checkout page, and eSewa sends it back
 * to the callbacks with a {@code ?data=} parameter. Both callbacks are reachable by
 * anyone who knows the URL, which is why neither of them trusts what it is given —
 * see {@link EsewaGatewayService}: the success callback requires a payload whose
 * signature it can reproduce <i>and</i> an independently confirmed
 * {@code COMPLETE} from eSewa's status API before a booking is confirmed.
 *
 * <p>The wizard lets a signed-out visitor pay, exactly as it lets one book
 * ({@code POST /api/bookings} is the API's other public write), so these cannot sit
 * behind a token — a customer who is not signed in would be bounced by their own
 * authentication mid-payment. The {@code SecurityConfig} grant is a GET-only rule on
 * this exact prefix, so no admin route and no write is opened with them.
 *
 * <h2>One controller, because it is one flow</h2>
 * <p>Two of these answer a redirect rather than JSON, which would normally argue for
 * a {@code @Controller} beside {@code PageController}. They are kept together here
 * because they share a prefix, a subject (one transaction) and a service, and
 * splitting them would put the handoff and its callbacks in different files with no
 * reader gaining anything. Every response says its own content type, so nothing
 * depends on how the class is annotated.
 *
 * <h2>Nothing is decided here</h2>
 * <p>Signing, payload verification, the status check and the settle all live in
 * {@link EsewaGatewayService}; this class turns HTTP in and HTTP out.
 */
@RestController
@RequestMapping("/api/payments/esewa")
@RequiredArgsConstructor
@Slf4j
public class EsewaController {

    private final EsewaGatewayService gateway;

    /**
     * The handoff: a page that signs the transaction and POSTs itself to eSewa.
     *
     * <p><b>Its own request supplies the callback host.</b> The success and failure
     * URLs are built from this request's scheme/host/port rather than from
     * configuration, so the customer is returned to the instance they are actually
     * using — 8080, 8081, or whatever a tunnel mapped — with nothing to keep in step.
     *
     * <p>Answers HTML, not JSON: a browser is expected here. It is reached because
     * {@code POST /api/payments/initiate} told the wizard to navigate to it, or by
     * hand from Postman for the live walk.
     *
     * @throws io.virinchi.yatra.Exception.ServiceUnavailableException 503 when this
     *         server has no eSewa credentials — see the class note on why that is a
     *         per-endpoint answer rather than a failure to boot
     */
    @GetMapping(value = "/checkout/{bookingId}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> checkout(@PathVariable int bookingId) {
        String page = gateway.checkout(bookingId,
                ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString());

        //No-store, and it matters: the page carries a signature over the amount and the
        //transaction, and a shared or back-button cache could hand a second customer a
        //form for the first one's payment.
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Cache-Control", "no-store")
                .body(page);
    }

    /**
     * Where eSewa sends the customer when it finishes.
     *
     * <p>The response is a redirect either way, which is what makes this the end of the
     * gateway flow rather than an API a page has to poll. On success the customer lands
     * on the e-ticket; on anything else, back on the payment page with the failure
     * named, which is where a retry belongs.
     *
     * <p><b>No ticket is rendered from the query string.</b> The redirect carries the
     * booking id only — never the PNR, which would put a customer-visible identifier in
     * a browser history for no reason. The id is all {@code eticket.html} needs: it
     * asks {@code GET /api/bookings/{id}} for the document, and that read is
     * owner-scoped, so the page shows the ticket to the customer who bought it and to
     * nobody who guessed an id.
     */
    @GetMapping("/success/{bookingId}")
    public ResponseEntity<Void> success(@PathVariable int bookingId,
                                        @RequestParam(required = false) String data) {
        EsewaGatewayService.Outcome outcome = gateway.success(bookingId, data);
        return seeOther(outcome.success()
                ? "/eticket.html?bookingId=" + bookingId + "&payment=success"
                : "/payment.html?bookingId=" + bookingId + "&payment=failed");
    }

    /**
     * Where eSewa sends the customer when it does not complete.
     *
     * <p>Tolerates a missing {@code data} on purpose — a cancellation on eSewa's own
     * page can arrive with nothing to read, and the booking should not be left claiming
     * a live attempt that the customer already abandoned. The outcome recorded is
     * non-success in every branch, so nothing here can confirm a booking.
     */
    @GetMapping("/failure/{bookingId}")
    public ResponseEntity<Void> failure(@PathVariable int bookingId,
                                        @RequestParam(required = false) String data) {
        EsewaGatewayService.Outcome outcome = gateway.fail(bookingId, data);
        return seeOther(outcome.success()
                //A failure callback for a transaction that actually succeeded (the customer
                //paid, then hit back) settles as a success — the money moved, so the
                //e-ticket is the honest destination rather than a "try again" page.
                ? "/eticket.html?bookingId=" + bookingId + "&payment=success"
                : "/payment.html?bookingId=" + bookingId + "&payment=failed");
    }

    /**
     * A redirect the browser performs once, without caching.
     *
     * <p>302 rather than 301 on purpose: a permanent redirect on a payment callback
     * would be cached by the browser and replayed for the next customer, and the URL it
     * points at carries a booking id.
     */
    private static ResponseEntity<Void> seeOther(String location) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header("Location", location)
                .header("Cache-Control", "no-store")
                .build();
    }
}
