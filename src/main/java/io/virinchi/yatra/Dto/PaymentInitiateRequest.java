package io.virinchi.yatra.Dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/**
 * The body of {@code POST /api/payments/initiate} — exactly what
 * {@code payment.js} posts at wizard step 3, plus the two keys the mock accepts.
 *
 * <p><b>Shape taken from the page.</b> {@code payment.js} sends
 * {@code { bookingId, method, amount, promoCode }}. {@code method} is the radio
 * button's value ({@code "esewa"} — lower case) and {@code amount} is the
 * wizard's own payable figure. Both are kept as the page writes them, and the
 * nested field names are not invented anywhere: the whole payload is four keys.
 *
 * <p><b>{@code amount} and {@code promoCode} are accepted and ignored.</b> The
 * same rule {@link BookingRequest} applies to its own {@code amount}: the sum the
 * gateway is asked for is computed server-side from the booking's stored
 * {@code total_amount}, because a figure that arrives in a JSON body is one a
 * browser can edit. The response returns the figure that was <i>actually</i>
 * recorded, so a caller can see the difference instead of guessing at it.
 * {@code promoCode} is not ignored by accident: the mock's promo reduces the
 * payable inside the gateway pages ({@code esewaBalance.js}), and the discounted
 * total is the gateway's own concern until a promo/fare-class phase makes the
 * discount a server-side rule. Recording the undiscounted booking total is the
 * honest half — it is what the {@code booking} row says is owed.
 *
 * <p><b>{@code bookingId} is required.</b> The mock tolerates a null id (it has no
 * database and invents whatever it needs); the real API has to resolve the
 * booking the seats were held for, so an absent id is a 400 rather than a
 * silently orphaned payment row.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PaymentInitiateRequest(

        @NotNull(message = "The booking id is required.")
        Integer bookingId,

        /* The gateway the page picked. Blank means eSewa, the only integrated one. */
        String method,

        /* Accepted, never used to charge — see the class comment. */
        BigDecimal amount,

        /* Accepted, never used to price — see the class comment. */
        String promoCode
) {
}
