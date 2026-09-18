package io.virinchi.yatra.RestAPIController;

import io.virinchi.yatra.Dto.AdminBookingListResponse;
import io.virinchi.yatra.Dto.AdminBookingResponse;
import io.virinchi.yatra.Dto.BookingStatusRequest;
import io.virinchi.yatra.Dto.HoldExpiryResponse;
import io.virinchi.yatra.Exception.ValidationException;
import io.virinchi.yatra.Service.BookingService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.LocalDate;

/**
 * The admin booking surface — Roadmap Phase 9.
 *
 * <p><b>A controller of its own rather than four more methods on
 * {@link BookingController}.</b> That one is the storefront's <i>public write</i>
 * ({@code POST /api/bookings}, no token, the API's only unauthenticated write) and
 * its whole class doc is about that risk. Mixing the admin reads and the status
 * change into it would put the one endpoint that must stay open next to the ones
 * that must never be, and someone reading the class later would have to work out
 * which annotation applies to which method. {@code SeedController} is the existing
 * precedent for an admin-only controller standing alone.
 *
 * <p><b>Every method is admin-only, twice over.</b> The {@code /api/admin/**} route
 * rule in {@code SecurityConfig} refuses the request before a controller is reached,
 * and {@code @PreAuthorize("hasRole('ADMIN')")} refuses it again inside the method —
 * which matters because the route rule alone would keep working if the annotation
 * were deleted, so {@code AdminRouteAuthorizationTest} asserts both layers on every
 * admin route it discovers, including these four.
 *
 * <p><b>Reads are {@code GET}, and the writes are a status change and the hold
 * sweep.</b> Bookings are financial records: they are never edited field by field and
 * never deleted here ({@code BookingService.deleteBooking} is the demo/abandoned path
 * and refuses anything with a payment or a ticket). What an admin genuinely does to a
 * booking is decide whether it is still pending, confirmed or cancelled — so that is
 * the one thing this controller writes — plus ending a hold nobody paid for, which is
 * the same kind of decision applied on a rule instead of an opinion (see
 * {@link #expireHolds(Integer)}).
 */
@RestController
@RequestMapping("/api/admin/bookings")
public class AdminBookingController {

    private final BookingService bookingService;

    /**
     * The window {@code POST /expire-holds} uses when the caller does not send one.
     *
     * <p>A constructor parameter because this class is hand-wired
     * ({@code @RequiredArgsConstructor} cannot carry a {@code @Value}): the same shape
     * {@code Security/JwtUtil} uses for its own configured values. The default is the
     * wizard's own 15-minute promise — changing it moves the hold without touching any
     * code, which is why it is a property rather than a constant.
     */
    private final int holdExpiryMinutes;

    public AdminBookingController(
            BookingService bookingService,
            @Value("${yatra.hold.expiry-minutes:15}") int holdExpiryMinutes) {
        this.bookingService = bookingService;
        this.holdExpiryMinutes = holdExpiryMinutes;
    }

    /**
     * The admin booking table.
     *
     * <p><b>Unpaged by default, paged on request</b> — the same choice the airline,
     * flight and destination lists make and for the same reason:
     * {@code admin-bookings.js} filters and pages client-side over the whole list
     * today, and a silent default page size would look like missing bookings on a
     * page that has no idea it was truncated. Passing {@code size} switches to a real
     * database page and adds {@code page}/{@code totalElements}/{@code totalPages}.
     *
     * @param search        PNR, ticket number, customer name/email/phone, flight
     *                      number — or the booking id when the term is numeric
     * @param status        {@code PENDING} / {@code CONFIRMED} / {@code CANCELLED},
     *                      any case; {@code ALL} or omit for every booking
     * @param paymentStatus {@code Pending} / {@code Paid} / {@code Failed} /
     *                      {@code Refunded}, any case; {@code ALL} or omit for any
     * @param flightId      one flight's bookings
     * @param date          the flight's <b>travel</b> date ({@code 2026-09-16}) — the
     *                      departures on that day
     * @param created       the booking's <b>creation</b> date — what was booked that
     *                      day, which is the daily-sales view
     * @param sort          one of {@code id|customer|status|payment|amount|created};
     *                      anything else falls back to {@code id}
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public AdminBookingListResponse list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String paymentStatus,
            @RequestParam(required = false) Integer flightId,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate created,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        if (size == null) {
            return AdminBookingListResponse.of(bookingService.listBookings(
                    search, status, paymentStatus, flightId, date, created, sort));
        }

        return AdminBookingListResponse.of(bookingService.listBookingPage(
                search, status, paymentStatus, flightId, date, created, sort,
                page == null ? 0 : page, size));
    }

    /**
     * One booking in full — contact, passengers, flight, payment and ticket.
     *
     * <p>Returns the same record the list does rather than a narrower "summary vs
     * detail" pair. The mock has always handed the page one complete record and the
     * detail modal renders it without a second request; two shapes would mean two
     * things to keep in step for no gain, since the list's own record already
     * carries everything the modal shows.
     */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminBookingResponse get(@PathVariable int id) {
        return bookingService.getBooking(id);
    }

    /**
     * Moves a booking between {@code PENDING}, {@code CONFIRMED} and {@code CANCELLED}.
     *
     * <p><b>What this endpoint will and will not do</b>, in one place, because the
     * rules are the point of it:
     * <ul>
     *   <li>Cancel is always allowed and idempotent, and touches nothing else — the
     *       seats stay counted and the refund stays the payments flow's job (R4).</li>
     *   <li>Confirm requires a <b>successful payment row</b>, not merely the word
     *       "Paid" on the booking: a booking is created before the gateway is called,
     *       so without this gate an unpaid draft could be marked sold.</li>
     *   <li>Nothing may go back to {@code PENDING} — a confirmed booking has a paid
     *       payment and usually a ticket, and a cancelled one has already been
     *       surrendered. Rebooking is a new booking.</li>
     * </ul>
     * A status the booking already has is a no-op, so the admin's double-click is
     * harmless.
     */
    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminBookingResponse updateStatus(@PathVariable int id,
                                             @Valid @RequestBody BookingStatusRequest request) {
        return bookingService.updateStatus(id, request.status());
    }

    /**
     * Ends every abandoned hold older than the window and gives its seats back —
     * {@code POST /api/admin/bookings/expire-holds}.
     *
     * <p><b>The manual half of a job that also runs on a timer.</b>
     * {@code Config/HoldSweepConfig} triggers {@link BookingService#expireHolds} on an
     * interval when {@code yatra.hold.sweep.enabled} is armed, which is what keeps a
     * live deployment tidy without anyone remembering to press anything. This endpoint
     * is how the same operation is <i>observed</i>: a scheduled job that quietly
     * releases seats is nearly impossible to demonstrate in a viva or to verify from
     * Postman, and a sweep whose effects cannot be checked is a sweep nobody can trust.
     *
     * <p><b>{@code minutes} overrides the window, and it is what makes the checkpoint
     * runnable.</b> Omitted, the configured window is used
     * ({@code yatra.hold.expiry-minutes}, the 15 minutes the wizard promises).
     * {@code ?minutes=0} expires <i>every</i> open hold, which is the only way to prove
     * the rule in a test run or a demo without waiting a quarter of an hour — and it is
     * safe to expose because the route is admin-only and the worst case is exactly what
     * the sweep does anyway, just sooner.
     *
     * <p><b>Re-running is harmless and is the settlement check:</b> the second call
     * reports {@code candidates: 0}. Releasing a seat is a conditional
     * {@code UPDATE} whose row count decides, so a repeated release changes nothing
     * rather than double-writing (R13).
     *
     * @param minutes how old a hold must be, or omit for the configured window
     * @throws ValidationException 400 {@code INVALID_HOLD_WINDOW} — a negative window
     */
    @PostMapping("/expire-holds")
    @PreAuthorize("hasRole('ADMIN')")
    public HoldExpiryResponse expireHolds(@RequestParam(required = false) Integer minutes) {
        int window = minutes == null ? holdExpiryMinutes : minutes;
        if (window < 0) {
            throw new ValidationException("INVALID_HOLD_WINDOW",
                    "The hold window must be zero minutes or more.");
        }
        return bookingService.expireHolds(Duration.ofMinutes(window));
    }
}
