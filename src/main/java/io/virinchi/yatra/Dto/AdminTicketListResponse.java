package io.virinchi.yatra.Dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * The body of {@code GET /api/admin/tickets}: {@code { "bookings": [ ... ] }} —
 * Roadmap Phase 12.
 *
 * <h2>Why a tickets endpoint answers with {@code bookings}</h2>
 * <p>Because the page says so, and this is risk R16 on its fourth surface (admin
 * bookings, the payments ledger, the user roster, and now tickets).
 * {@code admin-tickets.js} has no ticket store: it derives one document per booking,
 * reading {@code resp.bookings} and then {@code b.pnr}, {@code b.ticketNo},
 * {@code b.status}, {@code b.paymentStatus}, {@code b.customer} and {@code b.flight}
 * off each row — the same record {@code admin-bookings.html} renders. Answering with
 * a purpose-built ticket row, or with {@code { tickets: [...] }}, would leave
 * {@code resp.bookings} undefined and the page would silently render its
 * localStorage demo seeds while looking correct. So the endpoint speaks the page's
 * key and reuses {@link AdminBookingResponse} as its row, which is also what keeps
 * the two pages showing the same numbers for the same booking.
 *
 * <p><b>The rows are still a ticket list, not the bookings list.</b> Every row here
 * has a {@code ticket} row behind it — that is the query's own inner join — so a
 * booking created and abandoned before the gateway never appears. The page's own
 * {@code tickets()} filter ({@code b.pnr || b.ticketNo}) then keeps every row, which
 * is the point: the two definitions of "a ticket" agree.
 *
 * <p><b>Paging metadata appears only when paging was asked for</b>
 * ({@code @JsonInclude(NON_NULL)}), so the unpaged response — the shape the page
 * consumes today, filtering and paging client-side — stays compatible with the mock,
 * while a caller that passes {@code size} gets real database pages. Same choice, and
 * the same reason, as {@link AdminBookingListResponse} and
 * {@link AdminPaymentListResponse}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminTicketListResponse(

        List<AdminBookingResponse> bookings,
        Integer page,
        Integer size,
        Long totalElements,
        Integer totalPages
) {

    /** The unpaged shape: every issued ticket, in the service's deterministic order. */
    public static AdminTicketListResponse of(List<AdminBookingResponse> bookings) {
        return new AdminTicketListResponse(bookings, null, null, null, null);
    }

    /** The paged shape: one page plus the counts a client needs to walk the rest. */
    public static AdminTicketListResponse of(Page<AdminBookingResponse> page) {
        return new AdminTicketListResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
