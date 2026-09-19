package io.virinchi.yatra.RestAPIController;

import io.virinchi.yatra.Dto.AdminBookingResponse;
import io.virinchi.yatra.Dto.AdminTicketListResponse;
import io.virinchi.yatra.Service.TicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin ticket surface — Roadmap Phase 12. Read-only, deliberately.
 *
 * <p><b>Why this controller exists at all.</b> The rows it serves already existed:
 * Phase 10's {@code PaymentService.verify} mints a ticket through
 * {@link TicketService} on a successful payment, so the documents were being created
 * with no way to read them back — the one endpoint the roadmap names as the next
 * dependency after user management. This closes it.
 *
 * <p><b>Read-only, and that is a decision rather than an omission.</b>
 * {@code admin-tickets.html}'s own header says the view is read-only: "issuing,
 * reissuing and voiding tickets belongs to the backend's TicketService. Cancelling a
 * ticket starts at admin-bookings.html." An admin cancels the <i>booking</i>
 * ({@code PUT /api/admin/bookings/{id}/status}) and voids money through the refund;
 * neither of those rewrites a ticket row, so nothing here writes one either. The
 * phase's own build list is generation (Phase 10) plus this read.
 *
 * <p><b>Both methods are admin-only twice over</b> — the {@code /api/admin/**} route
 * rule in {@code SecurityConfig} refuses first, {@code @PreAuthorize} refuses again
 * inside the method — and {@code AdminRouteAuthorizationTest} discovers both
 * automatically, so a route added here cannot quietly skip either layer.
 *
 * <p><b>Its own controller rather than methods on {@code PaymentController} or
 * {@code AdminBookingController}.</b> Tickets are their own table and their own page,
 * and the admin booking controller's class doc is about the booking rules; the same
 * split produced {@code AdminPaymentController} beside {@code PaymentController}.
 */
@RestController
@RequestMapping("/api/admin/tickets")
@RequiredArgsConstructor
public class AdminTicketController {

    private final TicketService ticketService;

    /**
     * The issued-ticket table.
     *
     * <p><b>Unpaged by default, paged on request</b> — the same choice every other
     * admin list makes, kept for the callers that want the whole list in one body
     * (Postman, a test). {@code admin-tickets.js} now sends {@code page}/{@code size}
     * like the other five wired modules, so the table, its count line and its paging are
     * the database's; a silent default page size would still be wrong for an unpaged
     * caller, so the switch stays explicit. Passing {@code size} adds
     * {@code page}/{@code totalElements}/{@code totalPages}.
     *
     * <p>The response nests its rows under {@code bookings} — not a slip: the page
     * derives a document per booking and reads {@code resp.bookings}, so the tickets
     * table must speak that key or fall back to its localStorage seeds. See
     * {@link AdminTicketListResponse}.
     *
     * @param search       PNR, ticket number, booking id, contact name/email/phone or
     *                     flight number
     * @param status       the <b>booking's</b> status — {@code Confirmed} (the page's
     *                     "Issued"), {@code Cancelled} (its "Voided"), {@code Pending}
     *                     — in any case; {@code ALL} or omit for every ticket. This is
     *                     the dropdown the page actually sends, so it must keep
     *                     speaking the page's vocabulary (R16)
     * @param ticketStatus the <b>ticket row's own</b> {@code ISSUED}/{@code CANCELLED},
     *                     in any case; {@code ALL} or omit for every state. The state
     *                     {@code SeedService} writes for a refunded booking's ticket
     * @param sort         one of {@code id|pnr|ticket|issued|status}; anything else
     *                     falls back to {@code id}
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public AdminTicketListResponse list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String ticketStatus,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        if (size == null) {
            return AdminTicketListResponse.of(
                    ticketService.listTickets(search, status, ticketStatus, sort));
        }

        return AdminTicketListResponse.of(ticketService.listTicketPage(
                search, status, ticketStatus, sort, page == null ? 0 : page, size));
    }

    /**
     * One ticket in full — PNR, ticket number, passengers with their seats, the
     * flight, the payment and the amount.
     *
     * <p>Returns the same record the list does rather than a narrower summary, exactly
     * as {@code GET /api/admin/bookings/{id}} does, so there is only one row shape to
     * keep in step. {@code admin-tickets.js}'s detail modal reads it <b>fresh</b> rather
     * than rendering the row it already holds — the endpoint had been built for this page
     * and never called (the gap §12/§13 found on Users and Payments too), which is how a
     * ticket voided since the list was drawn stayed invisible to the admin.
     *
     * <p><b>Keyed by booking id</b>, because that is the id the page's rows carry
     * ({@code data-view}) and a ticket is 1:1 with its booking — see
     * {@link TicketService#getTicket(int)}.
     *
     * @throws io.virinchi.yatra.Exception.ResourceNotFoundException 404
     *         {@code TICKET_NOT_FOUND} when the booking has no ticket
     */
    @GetMapping("/{bookingId}")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminBookingResponse get(@PathVariable int bookingId) {
        return ticketService.getTicket(bookingId);
    }
}
