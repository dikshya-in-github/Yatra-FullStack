package io.virinchi.yatra.RestAPIController;

import io.virinchi.yatra.Dto.PaymentInitiateRequest;
import io.virinchi.yatra.Dto.PaymentInitiateResponse;
import io.virinchi.yatra.Dto.PaymentVerifyRequest;
import io.virinchi.yatra.Dto.PaymentVerifyResponse;
import io.virinchi.yatra.Service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The gateway flow the wizard walks: {@code POST /api/payments/initiate} and
 * {@code POST /api/payments/verify}.
 *
 * <p><b>Public, and that is a decision with a known cost.</b> The storefront lets a
 * signed-out visitor book — {@code POST /api/bookings} is already the API's public
 * write, on the recorded grounds that requiring a token would break the flow the
 * pages have — and a booking that cannot be paid for is not much of a booking. So
 * these two are public as well, and they are the <i>only</i> public writes besides
 * the booking itself. What that costs, stated plainly because it belongs in the
 * report rather than in a comment nobody reads:
 *
 * <ul>
 *   <li><b>{@code verify} is the gateway callback with no callback authentication.</b>
 *       A real eSewa integration posts to a webhook and signs the payload (a
 *       signature, an HMAC, a shared secret); this mock gateway <i>is</i> the browser,
 *       so the "callback" comes from the page and there is nothing to verify it
 *       against. The refusal that still holds is structural, and it is why
 *       {@code PAYMENT_NOT_INITIATED} exists: a caller cannot mark an arbitrary
 *       booking paid, because it must first have initiated a transaction for that
 *       booking, and it cannot pay for a cancelled one.</li>
 *   <li><b>No rate limit.</b> Same limitation the booking write carries — one
 *       unauthenticated write per request. Everything a caller can do here is bounded
 *       by bookings that already exist and hold seats.</li>
 * </ul>
 *
 * <p><b>Nothing is decided in this class.</b> Which methods are integrated, what an
 * initiated transaction may look like, whether a payment may be verified twice, what
 * a success does to the booking and its ticket — all of it lives in
 * {@link PaymentService}. The controller translates HTTP and stops.
 */
@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    /**
     * Starts a transaction for the wizard's pending booking.
     *
     * <p>Answers the mock's {@code { paymentRef, bookingId, method, amount,
     * gatewayRedirect }} — {@code payment.js} reads only {@code gatewayRedirect},
     * and keeps its own static handoff when this call fails, so a failure here never
     * dead-ends the demo.
     */
    @PostMapping("/initiate")
    public PaymentInitiateResponse initiate(@Valid @RequestBody PaymentInitiateRequest request) {
        return paymentService.initiate(request);
    }

    /**
     * Records the gateway's answer and completes the sale when it approved.
     *
     * <p>Answers <b>200 with {@code status: "FAILED"}</b> for a declined payment —
     * the verification itself succeeded and the payment did not. That is deliberate:
     * a non-2xx would read as "the API broke" to anything watching the call, and
     * {@code esewaConfirm.js} proceeds to the e-ticket on either branch anyway
     * ({@code .then(goEticket, goEticket)}).
     */
    @PostMapping("/verify")
    public PaymentVerifyResponse verify(@Valid @RequestBody PaymentVerifyRequest request) {
        return paymentService.verify(request);
    }
}
