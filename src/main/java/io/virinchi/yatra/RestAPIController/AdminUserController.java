package io.virinchi.yatra.RestAPIController;

import io.virinchi.yatra.Dto.AdminBookingListResponse;
import io.virinchi.yatra.Dto.AdminUserListResponse;
import io.virinchi.yatra.Dto.AdminUserRequest;
import io.virinchi.yatra.Dto.AdminUserResponse;
import io.virinchi.yatra.Dto.UserStatusRequest;
import io.virinchi.yatra.Service.BookingService;
import io.virinchi.yatra.Service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin user surface — Roadmap Phase 11.
 *
 * <p><b>A controller of its own, not more methods on {@code AuthController}.</b> That one
 * is the <i>public</i> door — {@code /api/auth/**} is {@code permitAll}, because a
 * signed-out visitor has to be able to register and sign in — and its whole class doc is
 * about the two endpoints that mint a session. Putting the roster beside it would place
 * the endpoints that must never be reachable without a role next to the ones that must
 * always be, and a reader would have to check each annotation to know which was which.
 * {@code AdminBookingController} is the existing precedent.
 *
 * <p><b>Every method is admin-only, twice over.</b> The {@code /api/admin/**} route rule in
 * {@code SecurityConfig} refuses the request before a controller is reached, and
 * {@code @PreAuthorize("hasRole('ADMIN')")} refuses it again inside the method — which
 * matters because the route rule alone would keep working if the annotation were deleted,
 * so {@code AdminRouteAuthorizationTest} asserts both layers on every admin route it
 * discovers, including these seven.
 *
 * <p><b>The roster's id key is {@code id}, and the page is why.</b>
 * {@code admin-users.js} builds every row action from {@code u.id}
 * ({@code data-edit}, {@code data-toggle}, {@code data-delete}) and matches the modal back
 * to the row with {@code String(x.id) === id}. The session shape ({@code UserResponse})
 * calls the same column {@code userId} because {@code auth.js} reads that — the two shapes
 * genuinely differ, and {@link AdminUserResponse} documents what emitting the wrong one
 * would do to the page.
 *
 * <p><b>What is not here:</b> a password <i>field</i>. The panel deliberately has none (its
 * own header: passwords belong to the backend's BCrypt and are never stored or displayed
 * here), so {@link AdminUserRequest} carries an optional one for provisioning and nothing
 * in this controller asks for it. Also absent: any endpoint that changes a user's
 * <i>role</i> or <i>status</i> for an {@code ADMIN} account — those are refusals in
 * {@code UserService}, with the lockout reasoning written next to them.
 */
@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final UserService userService;

    /**
     * The other half of the delete guard's answer ({@code GET /{id}/bookings} returns
     * bookings), so this controller reaches into the booking service for exactly one read
     * rather than duplicating the booking-to-DTO mapping — see
     * {@code BookingService.listBookingsForUser}.
     */
    private final BookingService bookingService;

    /**
     * The admin user table.
     *
     * <p><b>Unpaged by default, paged on request</b> — the same choice every other admin
     * list makes, for the same reason: {@code admin-users.js} filters and pages its own
     * eight rows client-side today, and a silent default page size would look like missing
     * users on a page that has no idea it was truncated. Passing {@code size} switches to a
     * real database page and adds {@code page}/{@code totalElements}/{@code totalPages}.
     *
     * @param search name, email, mobile number — or the account id when the term is numeric
     * @param role   {@code ADMIN} / {@code USER}, any case; {@code ALL} or omit for every role
     * @param status {@code Active} / {@code Inactive}, any case; {@code ALL} or omit for any
     * @param sort   one of {@code id|name|email|role|status|registered}; anything else falls
     *               back to {@code id}
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public AdminUserListResponse list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {

        if (size == null) {
            return AdminUserListResponse.of(userService.listUsers(search, role, status, sort));
        }

        return AdminUserListResponse.of(userService.listUserPage(
                search, role, status, sort, page == null ? 0 : page, size));
    }

    /** One account, in the same shape the list returns — the edit modal's canonical read. */
    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminUserResponse get(@PathVariable int id) {
        return userService.getUser(id);
    }

    /**
     * The bookings this account owns — what makes the delete refusal actionable.
     *
     * <p>Returns {@code { bookings: [...] }} in the admin booking shape (the same records
     * {@code GET /api/admin/bookings} returns for one account) rather than a bespoke summary,
     * so a page can render it with the code it already has and the two lists cannot drift.
     *
     * <p>The user is looked up first on purpose: without it, an unknown id would answer 200
     * with an empty list, which reads as "this account has no bookings" instead of "there is
     * no such account".
     */
    @GetMapping("/{id}/bookings")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminBookingListResponse bookings(@PathVariable int id) {
        userService.getUser(id);
        return AdminBookingListResponse.of(bookingService.listBookingsForUser(id));
    }

    /**
     * Adds an account to the roster.
     *
     * <p>Reads 200 with the created record, like every other admin create in this project
     * ({@code POST /api/admin/airlines}, {@code /api/admin/flights}) — the mock resolves its
     * writes with a normal success body and the pages only inspect the JSON.
     *
     * <p>The account it creates has <b>no credential unless the request supplies one</b>:
     * that is the panel's own rule, and the consequences (it cannot sign in, and it now owns
     * the address so the person cannot register either) are documented where they are decided
     * — {@code AdminUserRequest} and {@code UserService.createUser}.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public AdminUserResponse create(@Valid @RequestBody AdminUserRequest request) {
        return userService.createUser(request);
    }

    /**
     * Edits an account's name, email, mobile, role, status and (optionally) password.
     *
     * <p>A field left out, or sent blank, is <b>left alone</b> rather than cleared — the panel
     * submits all five of its fields on every save, so blank only comes from a hand-written
     * caller, and "blank means clear" would let a typo wipe an account's mobile number.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminUserResponse update(@PathVariable int id,
                                    @Valid @RequestBody AdminUserRequest request) {
        return userService.updateUser(id, request);
    }

    /**
     * Activates or deactivates an account — the row's power toggle.
     *
     * <p>A separate endpoint from {@link #update} because it is a different decision with a
     * different guard: this is the one write that can stop somebody signing in, so it is the
     * one the {@code ADMIN_ACCOUNT_PROTECTED} rule is really about. Idempotent, and it
     * touches nothing but {@code status} — bookings, payments, tickets and seats stay exactly
     * as they are.
     */
    @PutMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public AdminUserResponse updateStatus(@PathVariable int id,
                                          @Valid @RequestBody UserStatusRequest request) {
        return userService.updateStatus(id, request.status());
    }

    /**
     * Removes an account.
     *
     * <p>204 with no body, like the airline, flight and destination deletes. Two refusals
     * come back as 409 rather than as a database error: an {@code ADMIN} account, and an
     * account whose bookings still reference it (the remedy in both messages is the same —
     * set it Inactive instead).
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable int id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }
}
