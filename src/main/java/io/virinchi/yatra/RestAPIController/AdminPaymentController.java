package io.virinchi.yatra.RestAPIController;

import io.virinchi.yatra.Dto.AdminBookingResponse;
import io.virinchi.yatra.Dto.AdminPaymentListResponse;
import io.virinchi.yatra.Service.PaymentService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin payments surface — Roadmap Phase 10's monitoring half.
 *
 * <p><b>Its own controller, not two more methods on {@link PaymentController}.</b>
 * That one is the storefront's <i>public</i> gateway flow and its class doc is about
 * the cost of being public; putting admin reads and a refund next to it would put
 * the endpoints that must never be open beside the two that must be. Same split, and
 * the same reason, as {@code AdminBookingController} beside {@code BookingController}.
 *
 * <p><b>Both methods are admin-only twice over</b> — the {@code /api/admin/**} route
 * rule in {@code SecurityConfig} refuses first, {@code @PreAuthorize} refuses again
 * inside the method — and {@code AdminRouteAuthorizationTest} discovers both
 * automatically, so a new route here cannot quietly skip either layer.
 *
 * <p><b>The reads are shaped for the page, and the refund is the page's only write.</b>
 * A payment row is never edited field by field and never deleted: it is the record of
 * what a gateway answered, and the two things an admin genuinely decides about it are
 * "does this show up in the ledger" (it does, once initiated) and "was this money
 * given back" (the refund). Amounts, transaction ids and dates are the gateway's.
 */
@RestController
@RequestMapping("/api/admin/payments")
@RequiredArgsConstructor
public class AdminPaymentController {

    private final PaymentService paymentService;

    /**
     * The transaction ledger.
     *
     * <p><b>Unpaged by default, paged on request</b> — the same choice every other
     * admin list makes, kept for the callers that want the whole ledger in one body
     * (Postman, a test). {@code admin-payments.js} now sends {@code page}/{@code size}
     * like the other five wired modules, so the table, its count line and its paging are
     * the database's; a silent default page size would still be wrong for an unpaged
     * caller, so the switch stays explicit. Passing {@code size} adds
     * {@code page}/{@code totalElements}/{@code totalPages} <i>and</i> {@code stats},
     * the four tiles — see {@link AdminPaymentListResponse}.
     *
     * <p>The response nests its rows under {@code bookings} — not a slip: the page
     * derives a transaction per booking and reads {@code resp.bookings}, so the
     * ledger must speak that key or fall back to its localStorage seeds. See
     * {@link AdminPaymentListResponse}.
     *
     * @param search txn id, booking id, contact name/email/phone, flight number, PNR,
     *               ticket number
     * @param method {@code eSewa} or {@code Linked Bank Account}, any case;
     *               {@code ALL} or omit for every method
     * @param status {@code Paid}/{@code SUCCESS}, {@code Pending}, {@code Failed},
     *               {@code Refunded} — either vocabulary, any case; {@code ALL} or
     *               omit for every state
     * @param sort   one of {@code id|amount|status|method|created|paid}; anything else
     *               falls back to {@code id}
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public AdminPaymentListResponse list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String method,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        if (size == null) {
            return AdminPaymentListResponse.of(paymentService.listPayments(search, method, status, sort));
        }

        return paymentService.listPaymentPage(
                search, method, status, sort, page == null ? 0 : page, size);
    }

    /**
     * Marks a successful payment refunded.
     *
     * <p><b>Addressed by booking id</b>, not by payment id, because that is the key
     * the payments page holds ({@code data-refund} carries the row's booking id) and
     * because a payment is 1:1 with its booking — naming either one names the same
     * transaction, and the booking is the id every other admin page speaks. The
     * response is the updated booking record, the same shape the ledger lists, so
     * the caller can re-render without a second request.
     *
     * <p>A {@code POST} on {@code /refund} rather than a status {@code PUT}: this is
     * a named money action with its own preconditions, not an editable field. It
     * refuses a payment that never succeeded (409
     * {@code PAYMENT_NOT_REFUNDABLE}), is idempotent for one already refunded, and
     * deliberately does nothing else — the booking is not cancelled and its seats
     * stay counted (R4's policy), so a refund and a cancellation remain two separate
     * admin decisions.
     */
    @PostMapping("/{bookingId}/refund")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminBookingResponse refund(@PathVariable int bookingId) {
        return paymentService.refund(bookingId);
    }
}
