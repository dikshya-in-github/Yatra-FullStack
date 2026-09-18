package io.virinchi.yatra.Dto;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * What the abandoned-hold sweep reports: {@code POST /api/admin/bookings/expire-holds}.
 *
 * <p><b>Why this is a report and not a bare 200</b> — the same reason
 * {@link SeedResponse} is one. The sweep is a bulk, destructive operation whose
 * whole value is the seats it gave back, and "the request succeeded" says nothing
 * about whether anything was stale enough to touch. Counts make the checkpoint
 * checkable from Postman in one glance: run it twice, and the second run must
 * report {@code candidates: 0}.
 *
 * <h2>What the numbers mean</h2>
 *
 * <ul>
 *   <li>{@code candidates} — every {@code PENDING}, non-seeded booking older than the
 *       window that the query found. It is the honest denominator: a candidate that
 *       turned out to be a paid sale is <b>skipped</b>, so {@code candidates} can be
 *       larger than {@code expired}, and that difference is information rather than
 *       an inconsistency.</li>
 *   <li>{@code expired} — the bookings whose hold was actually ended, always
 *       {@code deleted + cancelled}. Kept as its own field because it is the number a
 *       caller reads first, and deriving it on every client is how two numbers about
 *       one operation drift apart.</li>
 *   <li>{@code deleted} — bookings removed outright (no payment row, no ticket), the
 *       same {@code BookingService.deleteBooking} rule: an abandoned draft with no
 *       sale records is not a record of anything.</li>
 *   <li>{@code cancelled} — bookings kept and flipped to {@code CANCELLED}, because a
 *       payment row exists (a declined or abandoned gateway attempt). R4 refuses to
 *       hard-delete a booking with a payment, and rightly: the failed transaction is
 *       the audit trail of what the customer tried to do. The <i>hold</i> is over
 *       either way — that is the part that must not outlive its window.</li>
 *   <li>{@code seatsReleased} — the sum of the conditional {@code UPDATE}s that
 *       actually changed a row from {@code BOOKED} to {@code AVAILABLE}. Not the
 *       number of seats the bookings <i>reference</i>: the claim/release pair returns
 *       a row count precisely so "I released it" is the database's verdict rather
 *       than this code's belief (R13). If it is smaller than expected, a seat was
 *       already free and there was nothing to give back.</li>
 *   <li>{@code windowMinutes} and {@code cutoff} — what the run was asked for, echoed
 *       back. A sweep that expired nothing is only meaningful next to the boundary it
 *       used, and a manual run with {@code ?minutes=0} is otherwise indistinguishable
 *       from a default one in a log.</li>
 * </ul>
 *
 * <p>{@code cutoff} is the instant the window ended, so a booking created after this
 * moment was deliberately not eligible. It is a {@link LocalDateTime} because that is
 * what {@code booking.created_at} stores (and what {@code AdminBookingResponse}
 * already serialises).
 */
public record HoldExpiryResponse(

        int windowMinutes,
        LocalDateTime cutoff,
        int candidates,
        int expired,
        int deleted,
        int cancelled,
        int seatsReleased
) {

    /** Assembles the report, deriving {@code expired} so the two can never disagree. */
    public static HoldExpiryResponse of(Duration window, LocalDateTime cutoff,
                                        int candidates, int deleted, int cancelled,
                                        int seatsReleased) {
        return new HoldExpiryResponse(
                (int) window.toMinutes(),
                cutoff,
                candidates,
                deleted + cancelled,
                deleted,
                cancelled,
                seatsReleased);
    }
}
