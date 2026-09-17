package io.virinchi.yatra.Exception;

import org.springframework.http.HttpStatus;

/**
 * 401 — no valid identity, or the credentials did not match.
 *
 * The real login path answers {@link #invalidCredentials()}
 * (`INVALID_CREDENTIALS`) after the BCrypt compare fails, which is the code the
 * frontend's login page is already written against — `api.js`'s mock login
 * route documents it, and login.html's `.catch()` path renders it.
 *
 * {@link #notAuthenticated()} (`NOT_AUTHENTICATED`) mirrors the mock admin
 * gate: `admin-profile.js` checks `err.status === 401` to bounce back to the
 * admin sign-in page, and the mock `GET /api/admin/profile` already answers
 * exactly this code. Spring Security replaces the mock gate, and
 * the page keeps working because the status and code stay the same.
 */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String code, String message) {
        super(HttpStatus.UNAUTHORIZED, code, message);
    }

    public static UnauthorizedException invalidCredentials() {
        return new UnauthorizedException(
                "INVALID_CREDENTIALS", "Invalid email/mobile or password.");
    }

    public static UnauthorizedException notAuthenticated() {
        return new UnauthorizedException(
                "NOT_AUTHENTICATED", "Sign-in required.");
    }
}
