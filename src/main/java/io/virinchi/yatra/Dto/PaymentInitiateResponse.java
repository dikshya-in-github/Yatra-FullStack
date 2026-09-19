package io.virinchi.yatra.Dto;

import io.virinchi.yatra.Model.Payment;

import java.math.BigDecimal;

/**
 * The body of {@code POST /api/payments/initiate} — the handoff the wizard needs
 * before it leaves for the gateway.
 *
 * <p><b>The mock's keys, so no page changes.</b> {@code payment.js} reads exactly
 * one field out of this response — {@code init.gatewayRedirect} — and navigates
 * there, keeping its static {@code ./esewaLogin.html} handoff when the call fails
 * or the key is absent. The other keys exist so Postman (and the report) can see
 * what was recorded: {@code paymentRef}, {@code bookingId}, {@code method} and
 * {@code amount} are the four the mock answers, in its own spelling
 * ({@code paymentRef}, camel case, amount as a number).
 *
 * <p><b>{@code gatewayRedirect} no longer names a Yatra page (fix-plan §10).</b> It
 * used to answer {@code esewaLogin.html}, one of the four pages that recreated
 * eSewa's own login/OTP/balance screens as static Yatra HTML. It now points at this
 * server's own handoff — {@code /api/payments/esewa/checkout/{bookingId}} — which
 * signs the transaction and POSTs the customer to eSewa's real hosted page. That is
 * the one value {@code payment.js} consumes, so the cut-over really is this line:
 * the page navigates where the server tells it to, and the server is what knows
 * whether the gateway is configured at all.
 *
 * <p>It is a <b>relative path beginning with {@code /}</b> rather than an absolute
 * URL: the handoff must happen on the origin the customer is already using, and
 * hard-coding {@code localhost:8080} here is how a demo on 8081 or a tunnel
 * silently sends the browser to the wrong instance — or to a stranger's. The same
 * reasoning is why the checkout page builds its own callback URLs from the request
 * that reached <i>it</i>.
 *
 * <p>The mock path is untouched: {@code api.js}'s route table still answers
 * {@code esewaLogin.html}, so a page in mock mode walks the demo screens exactly as
 * before. Only the real API's answer changed, and it is the answer that had to.
 *
 * <p><b>What {@code paymentRef} is here.</b> The mock mints a throwaway
 * {@code "PAY" + timestamp}. This API returns the <b>payment row's own id</b> in
 * the same {@code PAY}-prefixed, zero-padded shape — so the reference names a row
 * that exists and can be found again, instead of a timestamp that means nothing
 * once the response is gone. The format is deliberately kept, because
 * {@code payment.js} does not care but anything screenshotting the flow will look
 * the same as the mock's.
 *
 * <p><b>{@code method} is the stored spelling.</b> The page posts
 * {@code "esewa"}; the database stores {@code "eSewa"}, which is what
 * {@code admin-payments.html}'s method filter compares against (its option values
 * are exactly {@code eSewa} and {@code Linked Bank Account}). The response
 * reports the stored form so the caller never has to know which layer folds the
 * case.
 */
public record PaymentInitiateResponse(

        String paymentRef,
        int bookingId,
        String method,
        BigDecimal amount,
        String gatewayRedirect
) {

    /**
     * The real eSewa handoff's path, without the booking id.
     *
     * <p>A path, not a page name: {@code EsewaController} serves it, it answers HTML
     * (the self-submitting form), and it is public because the wizard lets a
     * signed-out visitor pay.
     */
    public static final String ESEWA_CHECKOUT = "/api/payments/esewa/checkout/";

    /**
     * The reference a caller sees: {@code "PAY" + the payment row's id, padded to
     * eight digits} — the mock's shape over a real key.
     */
    public static PaymentInitiateResponse of(Payment payment) {
        return new PaymentInitiateResponse(
                "PAY" + String.format("%08d", payment.getId()),
                payment.getBooking().getId(),
                payment.getMethod(),
                payment.getAmount(),
                ESEWA_CHECKOUT + payment.getBooking().getId());
    }
}
