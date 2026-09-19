package io.virinchi.yatra.Dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
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
 * ({@code @JsonInclude(NON_NULL)}), so the unpaged response — the shape the mock
 * answers too — stays compatible, while a caller that passes {@code size} gets real
 * database pages. Same choice, and the same reason, as
 * {@link AdminBookingListResponse}.
 *
 * <h2>{@code stats}: the four tiles, and why they ride along with the page</h2>
 * <p>{@code admin-payments.html} leads with Transactions / Collected / Refunded /
 * Pending, and the page used to compute all four in the browser from the whole list
 * it held. That is only possible while the list IS the whole ledger — the moment the
 * table pages server-side, a client-side sum describes the rows on screen, not the
 * ledger ("Collected" would drop as the admin walked to page 2). So the totals come
 * from the database, attached to the same response that carries the page, and the
 * page renders them instead of deriving them.
 *
 * <p><b>They are totals for the whole ledger, deliberately independent of the
 * toolbar filters</b> — that is what the mock's tiles were too (they summed every
 * booking in the store while the table showed a filtered slice). The tile row answers
 * "what has the gateway taken, given back and not yet answered", not "what does this
 * search add up to"; a filtered total is already on screen as the result count beside
 * the search box.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminPaymentListResponse(

        List<AdminBookingResponse> bookings,
        Integer page,
        Integer size,
        Long totalElements,
        Integer totalPages,

        /** The four tiles, over the whole ledger — present only on the paged shape. */
        Stats stats
) {

    /**
     * The payments page's stat row, computed by {@code PaymentService} in SQL.
     *
     * <p>{@code transactions} is the ledger's size (every payment row, which is every
     * transaction the gateway saw), not the filtered count. {@code collected} and
     * {@code refunded} are the sums of the {@code SUCCESS} and {@code REFUNDED} rows;
     * {@code pending} counts the rows still awaiting an answer. The definitions are
     * pinned here rather than left to whoever writes the next query, for the reason
     * {@link AdminDashboardResponse.Stats} gives: a card whose meaning drifts is a card
     * that disagrees with the rows beside it.
     */
    public record Stats(
            long transactions,
            BigDecimal collected,
            BigDecimal refunded,
            long pending
    ) {
    }

    /** The unpaged shape: every transaction, in the service's deterministic order. */
    public static AdminPaymentListResponse of(List<AdminBookingResponse> bookings) {
        return new AdminPaymentListResponse(bookings, null, null, null, null, null);
    }

    /** The paged shape: one page, the counts a client needs to walk the rest, and the tiles. */
    public static AdminPaymentListResponse of(Page<AdminBookingResponse> page, Stats stats) {
        return new AdminPaymentListResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                stats);
    }
}
