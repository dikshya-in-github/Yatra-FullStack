package io.virinchi.yatra.Dto;

/**
 * The success body of both auth endpoints: {@code { token, user }}.
 *
 * <p>This is the shape {@code api.js}'s mock routes already return, so
 * {@code auth.js}'s {@code saveSession} — which reads {@code res.token} and
 * {@code res.user} and stores both in sessionStorage — works unchanged against
 * the real API. Nothing else about the session flow needs to know which mode it
 * is in.
 */
public record AuthResponse(String token, UserResponse user) {
}
