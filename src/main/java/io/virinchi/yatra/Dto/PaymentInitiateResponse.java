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
     * The eSewa mock's entry page — the same relative path the mock returns, which
     * resolves against the flat {@code static/} page root the project serves.
     */
    public static final String ESEWA_GATEWAY = "esewaLogin.html";

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
                ESEWA_GATEWAY);
    }
}
