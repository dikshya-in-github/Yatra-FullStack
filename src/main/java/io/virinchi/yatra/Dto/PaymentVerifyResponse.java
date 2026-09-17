package io.virinchi.yatra.Dto;

import io.virinchi.yatra.Model.Payment;
import io.virinchi.yatra.Model.Ticket;

import java.time.LocalDateTime;

/**
 * The body of {@code POST /api/payments/verify}: the gateway's verdict, recorded.
 *
 * <p><b>The mock's four keys, plus two.</b> The mock answers
 * {@code { status, txnId, bookingId, persisted, verifiedAt }} and nothing in
 * {@code esewaConfirm.js} reads any of them — the page navigates to the e-ticket
 * on either branch. So this is free to carry the two facts a Postman run (and the
 * report's evidence table) actually want to see next: the {@code pnr} and
 * {@code ticketNo} the success path minted. Extra keys break nothing — every page
 * reads named fields — and without them the only way to prove a ticket was issued
 * is a second request to another endpoint.
 *
 * <p><b>{@code status} is the payment row's own vocabulary</b> ({@code SUCCESS} /
 * {@code FAILED}), not the booking's title-case {@code paymentStatus}. The mock
 * answers {@code "SUCCESS"} here, and this is the payment resource's own status —
 * {@code AdminBookingResponse} is where the display vocabulary belongs, and it
 * keeps emitting {@code Paid} / {@code Failed} / {@code Refunded}.
 *
 * <p><b>{@code persisted} means "the sale is complete".</b> In the mock it meant
 * "the storefront row was written" (it is {@code false} when the page sent no
 * booking record to write). Here it is true exactly when the booking was
 * confirmed and ticketed, i.e. when there is a persisted sale to show — and false
 * on a failed payment, which is the honest answer to the same question. A
 * response is still HTTP 200 in both cases: the <i>verification</i> succeeded.
 */
public record PaymentVerifyResponse(

        String status,
        String txnId,
        int bookingId,
        boolean persisted,
        String pnr,
        String ticketNo,
        LocalDateTime verifiedAt
) {

    /** A completed sale: the booking is confirmed and its ticket exists. */
    public static PaymentVerifyResponse success(Payment payment, Ticket ticket, LocalDateTime at) {
        return new PaymentVerifyResponse(
                payment.getStatus(),
                payment.getTxnId(),
                payment.getBooking().getId(),
                true,
                ticket == null ? "" : ticket.getPnr(),
                ticket == null ? "" : ticket.getTicketNo(),
                at);
    }

    /**
     * A failed attempt: the payment row carries the gateway's reference and
     * {@code FAILED}, and the booking stays where it was — no ticket, no
     * confirmation, and the seats it is holding stay held.
     */
    public static PaymentVerifyResponse failure(Payment payment, LocalDateTime at) {
        return new PaymentVerifyResponse(
                payment.getStatus(),
                payment.getTxnId(),
                payment.getBooking().getId(),
                false,
                "",
                "",
                at);
    }
}
