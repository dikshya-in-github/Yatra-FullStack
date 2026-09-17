package io.virinchi.yatra.Dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * The body of {@code GET /api/admin/bookings}: {@code { "bookings": [ ... ] }}.
 *
 * <p><b>The wrapper is the contract, not a bare array.</b> The mock answers
 * {@code { bookings: MockDB.getBookings() }} and {@code admin-bookings.js} reads
 * {@code resp.bookings} — starting with {@code Array.isArray(resp.bookings)} —
 * then falls back to its localStorage seeds when that is empty. A bare array, or a
 * Spring {@code Page} serialised directly ({@code { content: [...] }}), leaves
 * {@code resp.bookings} undefined and the page would silently render its fallback
 * demo rows as though they were the real admin data. Same shape as the airline,
 * flight and destination lists, for the same reason.
 *
 * <p><b>Paging metadata appears only when paging was asked for</b>
 * ({@code @JsonInclude(NON_NULL)}), so the unpaged response — the shape the page
 * consumes today, since it filters and pages client-side — stays byte-compatible
 * with the mock. Adding keys is safe; changing {@code bookings} would not.
 *
 * <p>The page's own counters ({@code resultCount}, {@code pageInfo}) are computed
 * from the array it holds, so a <i>paged</i> response genuinely needs
 * {@code totalElements}/{@code totalPages} for the page to ever move off
 * client-side paging. That is a frontend change (Phase 3), named here so the keys
 * are already in place when it happens.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminBookingListResponse(

        List<AdminBookingResponse> bookings,
        Integer page,
        Integer size,
        Long totalElements,
        Integer totalPages
) {

    /** The unpaged shape: every matching booking, in the service's deterministic order. */
    public static AdminBookingListResponse of(List<AdminBookingResponse> bookings) {
        return new AdminBookingListResponse(bookings, null, null, null, null);
    }

    /** The paged shape: one page plus the counts a client needs to walk the rest. */
    public static AdminBookingListResponse of(Page<AdminBookingResponse> page) {
        return new AdminBookingListResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
