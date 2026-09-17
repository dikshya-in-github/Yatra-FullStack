package io.virinchi.yatra.Exception;

import org.springframework.http.HttpStatus;

/**
 * 403 — the caller <i>is</i> who they say they are, and still may not proceed.
 *
 * <p>Until this class existed the project had no 403 of its own: every 403 came from
 * Spring Security ({@code SecurityConfig}'s access-denied handler for the route rule,
 * {@code GlobalExceptionHandler}'s {@code AccessDeniedException} handler for
 * {@code @PreAuthorize}). This one is different in kind — it is not about a missing
 * role but about the <b>state of the account</b> the request is coming from, which is
 * exactly what Roadmap Phase 11 introduces by making deactivation possible.
 *
 * <p>The status choice is deliberate: <b>403, not 401.</b> The password was correct.
 * Answering 401 {@code INVALID_CREDENTIALS} would tell a deactivated customer they had
 * mistyped their password and send them round the login loop again, which is the one
 * thing they cannot fix. A distinct status and code lets login.html render the one
 * message that is actually true.
 */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String code, String message) {
        super(HttpStatus.FORBIDDEN, code, message);
    }

    /**
     * The account exists, the credentials matched, and the account is
     * {@code Inactive} — so sign-in is refused.
     *
     * <p><b>This is the half of deactivation that was missing.</b> {@code status} was
     * already consulted by {@code UserService.loadUserByUsername} (the Spring Security
     * path, where {@code disabled} maps to {@code DisabledException}), but the real
     * login flow goes through {@code AuthController} → {@code UserService.authenticate},
     * which until Phase 11 never looked at the column at all — so an admin could
     * "deactivate" an account and the person could keep signing in. A toggle that does
     * nothing is worse than no toggle.
     *
     * <p>No page branches on {@code ACCOUNT_DISABLED} — login.html's submit handler
     * shows {@code err.message} for any {@code ApiError}, so the message is the
     * user-facing contract and is written as one: what happened and who can undo it.
     */
    public static ForbiddenException accountDisabled() {
        return new ForbiddenException(
                "ACCOUNT_DISABLED",
                "This account has been deactivated. Contact an administrator to have it restored.");
    }
}
