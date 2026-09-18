package io.virinchi.yatra.RestAPIController;

import io.virinchi.yatra.Dto.AdminProfileResponse;
import io.virinchi.yatra.Dto.ProfileUpdateRequest;
import io.virinchi.yatra.Exception.UnauthorizedException;
import io.virinchi.yatra.Service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The administrator's own account — the tenth and last Master Plan §2.1 admin
 * page to get a backend endpoint.
 *
 * <p><b>Why this controller is the one that closes Phase 14's checkpoint.</b> The
 * checkpoint asks that <i>every</i> admin page have a working, protected backend
 * endpoint behind it. Nine of the ten already did — airlines, flights, destinations,
 * bookings, payments, users, tickets and the dashboard each serve their page — but
 * {@code admin-profile.html} was calling {@code GET}/{@code POST /api/admin/profile}
 * against the mock and nothing else, so a live run would have answered it with a
 * 404-shaped hole. This adds it.
 *
 * <p><b>No identifier in the path, and that is the security property rather than a
 * stylistic choice.</b> The caller is resolved from the JWT's {@code sub} claim, so
 * there is no {@code {id}} to tamper with: an administrator can read and edit their
 * own row and no other. Putting an id in the URL would have made "edit any account,
 * including another administrator's" a one-character change, and the role/status
 * protections in {@link UserService} would not have covered it. {@code api.js}'s own
 * comment states the same contract for the mock this replaces: <i>"/api/users/me and
 * /api/admin/profile take NO identifier: in Phase 3 Spring Security resolves the
 * principal from the JWT's sub claim, so the same page code sends the same empty
 * body."</i>
 *
 * <p><b>Admin-only twice over</b>, like every other admin route: the
 * {@code /api/admin/**} rule in {@code SecurityConfig} refuses first and
 * {@code @PreAuthorize("hasRole('ADMIN')")} refuses again inside the method.
 * {@code AdminRouteAuthorizationTest} discovers both mappings automatically and
 * asserts both layers, so neither can be skipped quietly — and both are in that
 * test's named-route floor, because a {@code GET} with no path variable and a
 * {@code POST} with no path variable are exactly the shapes a hand-written route list
 * would have dropped.
 *
 * <p><b>Its own controller rather than methods on {@code AuthController} or
 * {@code AdminUserController}.</b> {@code AuthController} is the <i>public</i> door
 * ({@code /api/auth/**} is {@code permitAll}) and its class doc is about the two
 * endpoints that mint a session; the roster controller is about <i>other</i> people's
 * accounts. This is a third thing — self-service on your own row — and
 * {@code AdminTicketController} and {@code AdminDashboardController} are the existing
 * precedent for giving a page its own controller.
 *
 * <p><b>What is deliberately not here:</b> a password-change endpoint.
 * {@code admin-profile.js} says in its own header that the password block
 * <i>"will POST /api/admin/profile/password in Phase 3"</i>, so the page ships with
 * that path stubbed rather than calling a route this project has not built. Adding a
 * half-specified credential change at the end of Phase 2 would be the one write in
 * the system whose failure mode is a locked-out administrator.
 */
@RestController
@RequestMapping("/api/admin/profile")
@RequiredArgsConstructor
public class AdminProfileController {

    private final UserService userService;

    /**
     * The caller's own account.
     *
     * <p>Answers {@code { user: { … } }} — the envelope and the key names
     * {@code admin-profile.js} reads ({@code res.user}, then {@code u.userId},
     * {@code u.name}, {@code u.email}, {@code u.phone}) — see
     * {@link AdminProfileResponse}.
     *
     * <p>Its only failure the page acts on is a 401: the page's guard
     * ({@code admin.js}) runs once at load, so an expired token discovered here is the
     * case it cannot catch, and {@code admin-profile.js} bounces back to the sign-in
     * page on exactly that status. Spring Security answers 401 before this method is
     * reached, so the branch in {@link #adminId} is a backstop rather than the usual
     * path.
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public AdminProfileResponse get(Authentication authentication) {
        return AdminProfileResponse.of(userService.getOwnProfile(adminId(authentication)));
    }

    /**
     * Saves the profile form — name, email and mobile number, and nothing else.
     *
     * <p>The body is {@link ProfileUpdateRequest}, which has no {@code role} or
     * {@code status} field, so this endpoint cannot be used to change them even though
     * the caller is an administrator editing an administrator. A blank field means
     * "leave it alone", never "clear it".
     *
     * <p>Reads 200 with the updated record, like every other write in this project
     * ({@code POST /api/admin/airlines}, {@code PUT /api/admin/users/{id}}) — the mock
     * resolves its writes with a normal success body and the page only inspects the
     * JSON. Two refusals come back as 409 with the frontend's own codes,
     * {@code EMAIL_EXISTS} / {@code PHONE_EXISTS}, which {@code admin-profile.js}
     * paints onto the offending field.
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public AdminProfileResponse update(@Valid @RequestBody ProfileUpdateRequest request,
                                       Authentication authentication) {
        return AdminProfileResponse.of(
                userService.updateOwnProfile(adminId(authentication), request));
    }

    /**
     * The caller's own user id, from the JWT's {@code sub} claim.
     *
     * <p>The same resolution {@code BookingController.userIdOf} performs, with one
     * deliberate difference: that method answers {@code null} for an anonymous caller
     * because a guest booking is a supported case, while here the caller is guaranteed
     * to be an authenticated administrator — both the route rule and
     * {@code @PreAuthorize} have already run — so an unreadable principal is a broken
     * session, not a guest, and it answers 401 {@code NOT_AUTHENTICATED} rather than
     * silently acting on nobody.
     *
     * <p>That is also the code the mock answered from this route, which is why the
     * page's existing 401 branch still works unchanged.
     */
    private static int adminId(Authentication authentication) {
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
