package io.virinchi.yatra.RestAPIController;

import io.virinchi.yatra.Dto.AdminBookingListResponse;
import io.virinchi.yatra.Dto.ProfileUpdateRequest;
import io.virinchi.yatra.Dto.UserProfileResponse;
import io.virinchi.yatra.Exception.UnauthorizedException;
import io.virinchi.yatra.Service.BookingService;
import io.virinchi.yatra.Service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The signed-in customer's own account — the backend behind {@code profile.html}
 * (Fix-plan §11's page, already built) and the refresh half of §7's
 * {@code booking.html} pre-fill.
 *
 * <h2>Three routes, and the last two exist because the plan asked for both verbs</h2>
 * <p>{@code GET /api/users/me} is §7's requirement verbatim. For the write, §11 asks
 * for {@code PUT /api/users/me} while {@code profile.js} — which was written first and
 * is not being changed for this — calls {@code apiPost('/api/users/me', payload)}. The
 * sibling {@link AdminProfileController} is also a {@code POST}. Mapping both verbs
 * onto one handler is the honest resolution: neither the plan nor the page is wrong,
 * the route is simply reachable either way, and the body and response are identical.
 * The alternative was rewriting working page code to satisfy a typo in a document.
 *
 * <h2>No identifier in the path, and that is the security property</h2>
 * <p>The caller is resolved from the JWT's {@code sub} claim — {@code JwtAuthFilter}
 * authenticates with the user id as the principal — so there is no {@code {id}} to
 * tamper with. This is the same reasoning {@link AdminProfileController} documents: an
 * id in the URL would make "read and edit somebody else's account" a one-character
 * change. That matters more here, not less, because this route has <b>no role
 * requirement</b>: every authenticated account reaches it, and the only thing keeping
 * it to one row is that the row is derived from the token rather than the URL.
 *
 * <h2>Authorization is the default rule, not a new one</h2>
 * <p>{@code SecurityConfig} ends with {@code anyRequest().authenticated()}, and no
 * earlier rule matches {@code /api/users/**} — {@code /api/auth/**} is the only
 * {@code /api} prefix opened to the public. So these routes are already closed to
 * anonymous callers by the existing configuration, and no {@code SecurityConfig} change
 * is needed to add them. The {@code userId} backstop below is therefore a broken-session
 * guard rather than the usual path.
 *
 * <h2>What is deliberately not here</h2>
 * <p><b>A password-change endpoint.</b> {@code profile.js} says its password block
 * <i>"will POST /api/users/me/password in Phase 3"</i> and ships that path stubbed, and
 * {@link AdminProfileController} declines the admin equivalent for the same stated
 * reason — it is the one write whose failure mode is a locked-out account.
 *
 * <p><b>A delete-account endpoint.</b> §11 lists one but explicitly says to
 * <i>"flag and ask"</i> what deleting an account with existing bookings should do
 * (hard delete vs. deactivate) before implementing it. The project's R4 rule already
 * governs the adjacent case — a paid booking is cancelled, never deleted — so the
 * semantics have to be decided, not assumed. Not built.
 */
@RestController
@RequestMapping("/api/users/me")
@RequiredArgsConstructor
public class UserProfileController {

    private final UserService userService;
    private final BookingService bookingService;

    /**
     * The caller's own account — §7's pre-fill source and {@code profile.html}'s load.
     *
     * <p>Answers {@code { user: { … } }} — the envelope and key names {@code profile.js}
     * reads ({@code res.user}, then {@code u.name}, {@code u.email}, {@code u.phone},
     * {@code u.registeredAt}) — see {@link UserProfileResponse}. The mock route for this
     * same path answers {@code { user: publicUser(record) }}, so mock and real mode stay
     * interchangeable without touching the page.
     *
     * @throws io.virinchi.yatra.Exception.ResourceNotFoundException 404
     *         {@code USER_NOT_FOUND} — a valid token whose account has since been
     *         deleted, i.e. a stale session rather than a reachable page state
     */
    @GetMapping
    public UserProfileResponse get(Authentication authentication) {
        return UserProfileResponse.of(userService.getOwnProfile(userId(authentication)));
    }

    /**
     * Saves the profile form — name, email and mobile number, and nothing else.
     *
     * <p>The body is {@link ProfileUpdateRequest}, which has no {@code role} or
     * {@code status} field, so a customer cannot promote themselves by adding a key to
     * the JSON. Blank means "leave it alone", never "clear it" — the convention that
     * keeps this endpoint from being able to remove an account's last identifier.
     *
     * <p>Reads 200 with the updated record, like every other write in this project. Two
     * refusals come back as 409 with the frontend's own codes, {@code EMAIL_EXISTS} /
     * {@code PHONE_EXISTS}, which {@code profile.js} paints onto the offending field.
     *
     * <p>The service performs the same uniqueness checks as the admin endpoint, using
     * {@code existsByEmailAndIdNot} / {@code existsByPhoneAndIdNot} so resubmitting an
     * unchanged form is not a 409.
     */
    @PostMapping
    public UserProfileResponse update(@Valid @RequestBody ProfileUpdateRequest request,
                                      Authentication authentication) {
        return UserProfileResponse.of(
                userService.updateOwnProfile(userId(authentication), request));
    }

    /**
     * The same write, reachable by {@code PUT} as §11 specifies.
     *
     * <p>Identical body, identical response, identical checks — one handler deliberately
     * behind two verbs rather than two implementations that could drift. Nothing in the
     * project calls it yet; it exists because the plan names {@code PUT} and a future
     * caller should not have to discover that the route answers only to {@code POST}.
     */
    @PutMapping
    public UserProfileResponse replace(@Valid @RequestBody ProfileUpdateRequest request,
                                       Authentication authentication) {
        return update(request, authentication);
    }

    /**
     * The caller's own booking history — {@code my-bookings.html}'s list and
     * {@code profile.js}'s "Total bookings" / "Upcoming" stats.
     *
     * <p><b>Why these rows reuse {@link io.virinchi.yatra.Dto.AdminBookingResponse}.</b>
     * Not because the customer sees an admin view, but because that record <i>is</i> the
     * booking vocabulary — the admin list, the admin detail modal, the admin's view of
     * one user's history ({@code GET /api/admin/users/{id}/bookings}, which already
     * calls the very same service method) and the mock's {@code yatra_bookings} record
     * all speak it, field for field: {@code id}, {@code pnr}, {@code ticketNo},
     * {@code status} in its <i>display</i> spelling ({@code "Cancelled"}, which is what
     * {@code myBookings.js} buckets on), the nested {@code flight} with its
     * {@code date}, plus {@code amount} and {@code createdAt}. Inventing a
     * customer-shaped duplicate would give the same data two definitions and one more
     * place for them to drift — which is R16, the risk this project keeps meeting.
     *
     * <p><b>The scoping is the point of the endpoint.</b> The mock could not do this:
     * its own comment says the shared store has <i>"no per-user scoping until JWT auth
     * lands"</i>. This reads {@code findByUserId}, so the list is one account's history
     * rather than every booking in the database — the difference between a customer
     * page and a data leak, and the reason this route is worth having even though the
     * mock already rendered something.
     *
     * <p>Unpaged on purpose, like the admin's view of one account: it is a history, not
     * a table the page pages through. {@link AdminBookingListResponse} is
     * {@code @JsonInclude(NON_NULL)}, so the response is exactly {@code { bookings: [ … ] }}
     * — no null paging keys for the pages to trip over, and the same shape the mock
     * answers.
     */
    @GetMapping("/bookings")
    public AdminBookingListResponse bookings(Authentication authentication) {
        return AdminBookingListResponse.of(
                bookingService.listBookingsForUser(userId(authentication)));
    }

    /**
     * The caller's own user id, from the JWT's {@code sub} claim.
     *
     * <p>The same resolution {@link AdminProfileController} performs, and deliberately
     * <b>not</b> the nullable {@code BookingController.userIdOf}: a guest booking is a
     * supported case on that route, while {@code anyRequest().authenticated()} has
     * already refused an anonymous caller here. An unreadable principal is therefore a
     * broken session, not a guest, and it answers 401 {@code NOT_AUTHENTICATED} rather
     * than 500ing or silently acting on nobody.
     */
    private static int userId(Authentication authentication) {
        if (authentication == null
                || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            throw UnauthorizedException.notAuthenticated();
        }

        String subject = authentication.getName();
        if (subject == null || subject.isBlank() || "anonymousUser".equals(subject)) {
            throw UnauthorizedException.notAuthenticated();
        }

        try {
            return Integer.parseInt(subject.trim());
        } catch (NumberFormatException ex) {
            throw UnauthorizedException.notAuthenticated();
        }
    }
}
