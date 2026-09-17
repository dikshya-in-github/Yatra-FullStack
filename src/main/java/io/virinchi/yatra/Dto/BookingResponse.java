package io.virinchi.yatra.Dto;

import io.virinchi.yatra.Model.Booking;

import java.math.BigDecimal;

/**
 * The body of {@code POST /api/bookings} — the pending booking's handle.
 *
 * <p><b>The mock's keys, so the page needs no change.</b> {@code booking.js}
 * reads {@code pending.bookingId} and {@code pending.status} and writes both into
 * its existing {@code bookingData} sessionStorage key; {@code payment.js} then
 * shows that id as the booking code. The mock's own handler answers
 * {@code { bookingId, status }} (plus an echo of the request, which nothing
 * reads), so this record matches the two keys that matter.
 *
 * <p><b>One key is added: {@code amount}.</b> It is the total the server
 * <i>stored</i>, which is not necessarily the total the request claimed —
 * {@link BookingRequest} accepts and ignores a client-supplied amount, so echoing
 * the real figure back is what makes that divergence visible to the caller (and
 * to Postman) instead of silent. The mock sends no such key, and an extra key
 * breaks nothing: every page reads named fields.
 *
 * <p>The id is a <b>number</b> here where the mock mints a string
 * ({@code "BKG12345678"}). Both ride through {@code bookingData.bookingId}
 * untouched; the difference only becomes visible once Phase 10 looks the row up
 * by that id, which is the real API's business anyway.
 */
public record BookingResponse(

        int bookingId,
        String status,
        BigDecimal amount
) {

    public static BookingResponse of(Booking booking) {
        return new BookingResponse(
                booking.getId(),
                booking.getBookingStatus() == null ? "" : booking.getBookingStatus(),
                booking.getTotalAmount());
    }
}
