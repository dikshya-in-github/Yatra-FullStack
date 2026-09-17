package io.virinchi.yatra.Dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * The body of {@code GET /api/admin/users}: {@code { "users": [ ... ] }}.
 *
 * <p><b>The wrapper is the contract, not a bare array.</b> The mock answers
 * {@code { users: MockDB.getUsers() }} and {@code admin-users.js}'s first render is
 * literally {@code apiGet('/api/admin/users').then(resp => resp.users)} — and its
 * {@code .catch()} path quietly falls back to the page's localStorage seeds when the
 * request fails. A bare array, or a serialised Spring {@code Page}
 * ({@code { content: [...] }}), leaves {@code resp.users} undefined, so the page
 * would render its demo roster as though it were the real one, with nothing in the
 * console to say so. Same shape as the airline, flight, destination, booking and
 * payment lists, for the same reason.
 *
 * <p><b>Paging metadata appears only when paging was asked for</b>
 * ({@code @JsonInclude(NON_NULL)}), so the unpaged response — the shape the page
 * consumes today, since it filters and pages its own 8 rows client-side — stays
 * byte-compatible with the mock. Adding keys is safe; changing {@code users} would
 * not.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminUserListResponse(

        List<AdminUserResponse> users,
        Integer page,
        Integer size,
        Long totalElements,
        Integer totalPages
) {

    /** The unpaged shape: every matching user, in the service's deterministic order. */
    public static AdminUserListResponse of(List<AdminUserResponse> users) {
        return new AdminUserListResponse(users, null, null, null, null);
    }

    /** The paged shape: one page plus the counts a client needs to walk the rest. */
    public static AdminUserListResponse of(Page<AdminUserResponse> page) {
        return new AdminUserListResponse(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
