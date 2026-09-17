package io.virinchi.yatra.Dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * The body of {@code GET /api/admin/payments}: {@code { "bookings": [ ... ] }}.
 *
 * <h2>Why a payments endpoint answers with {@code bookings}</h2>
 * <p>Because the page says so, and this is risk R16 applied to a new surface.
 * {@code admin-payments.js} has no payments store: it derives one transaction per
 * booking, reading {@code resp.bookings} and then {@code b.payment.txnId},
 * {@code b.paymentStatus}, {@code b.customer}, {@code b.pnr}, {@code b.flight} off
 * each row — the same record {@code admin-bookings.html} renders. Answering with a
 * purpose-built payment row, or with {@code { payments: [...] }}, would leave
 * {@code resp.bookings} undefined and the page would fall back to its
 * localStorage demo seeds while looking correct — the exact silent failure R16
 * exists to prevent. So the ledger speaks the page's key and reuses
 * {@link AdminBookingResponse} as its row, which is also what keeps the two pages
 * showing the same numbers for the same booking.
 *
 * <p><b>The rows are still a payments ledger, not the bookings list.</b> Every row
 * here has a {@code payment} row behind it (that is the query's own filter), so a
 * booking the storefront created and abandoned before the gateway never appears —
 * "Transactions" counts gateway transactions. The page's four tiles then read
 * correctly: {@code payTxns} is the ledger's size, {@code payCollected} sums the
 * {@code Paid} rows, {@code payRefunded} the {@code Refunded} ones and
 * {@code payPending} the ones still awaiting an answer.
 *
 * <p><b>Paging metadata appears only when paging was asked for</b>
 * ({@code @JsonInclude(NON_NULL)}), so the unpaged response — the shape the page
 * consumes today, filtering and paging client-side — stays compatible with the
 * mock, while a caller that passes {@code size} gets real database pages. Same
 * choice, and the same reason, as {@link AdminBookingListResponse}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminPaymentListResponse(

        List<AdminBookingResponse> bookings,
        Integer page,
        Integer size,
        Long totalElements,
        Integer totalPages
) {

    /** The unpaged shape: every transaction, in the service's deterministic order. */
    public static AdminPaymentListResponse of(List<AdminBookingResponse> bookings) {
        return new AdminPaymentListResponse(bookings, null, null, null, null);
    }

    /** The paged shape: one page plus the counts a client needs to walk the rest. */
    public static AdminPaymentListResponse of(Page<AdminBookingResponse> page) {
        return new AdminPaymentListResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
