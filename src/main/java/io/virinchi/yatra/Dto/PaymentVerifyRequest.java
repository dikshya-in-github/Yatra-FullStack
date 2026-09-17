package io.virinchi.yatra.Dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * The body of {@code POST /api/payments/verify} — the callback that says the
 * gateway answered.
 *
 * <p><b>The mock's payload, minus its one piece of pragmatism.</b>
 * {@code esewaConfirm.js} posts {@code { txnId, bookingId, method, amount,
 * productAmount, customerName, booking }}. The last key is there because the mock
 * has no database to look the pending booking up in, so the page re-sends the
 * whole {@code bookingData} record for it to write into {@code yatra_bookings}.
 * The real API resolves the booking by {@code bookingId} — that is the entire
 * point of this endpoint — so {@code booking} is absorbed by
 * {@code ignoreUnknown} and never read. A caller that sends it (the page does,
 * unchanged in mock mode) is not rejected for it.
 *
 * <p><b>{@code amount} / {@code productAmount} are accepted and ignored.</b> The
 * gateway charged <i>something</i>, and the snapshot belongs to the gateway, not
 * to this API: the payment row records the booking's own stored total, which is
 * the only figure this service can vouch for. Refusing on a mismatch would be
 * worse than ignoring it — the mock's promo math ({@code esewaBalance.js}
 * discounts the payable) legitimately makes the charged total smaller than the
 * booking's, so a mismatch is not evidence of anything.
 *
 * <p><b>{@code outcome} is a mock-only affordance, and it is named as one.</b> A
 * real gateway never asks the integrator whether the payment succeeded — it
 * answers, and the answer is the input. The university build has no real
 * credentials and the mock page always succeeds, so without a switch there is no
 * way to produce the {@code FAILED} half of the roadmap's checkpoint ("simulate a
 * failure → booking stays PENDING, payment shows FAILED") except by writing a row
 * behind the API's back. So the field exists, defaults to success, and is
 * deliberately the only place the payment outcome is taken from the caller.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentVerifyRequest(

        /* The gateway's own transaction reference — the client-side mock's "9A" + 8 digits. */
        @NotBlank(message = "The gateway transaction id is required.")
        String txnId,

        @NotNull(message = "The booking id is required.")
        Integer bookingId,

        String method,

        /* Accepted, never used to charge — see the class comment. */
        BigDecimal amount,

        /* Accepted, never used to charge — see the class comment. */
        BigDecimal productAmount,

        /* Only ever echoed back by nothing today; kept because the page sends it. */
        String customerName,

        /* "SUCCESS" (default) or "FAILED" — the mock gateway's outcome switch. */
        @Pattern(regexp = "(?i)\\s*(SUCCESS|FAILED)?\\s*",
                message = "Outcome must be SUCCESS or FAILED.")
        String outcome
) {

    /** The requested outcome, defaulting to success — the mock gateway's normal answer. */
    public boolean failed() {
        return "FAILED".equalsIgnoreCase(String.valueOf(outcome == null ? "" : outcome).trim());
    }
}
