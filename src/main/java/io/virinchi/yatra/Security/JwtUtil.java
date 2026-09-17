package io.virinchi.yatra.Security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

/**
 * Signs and verifies the stateless JWT.
 *
 * <p><b>Claim set matches the frontend exactly.</b> {@code api.js}'s mock token
 * payload is {@code sub, name, email, role, iat, exp} — the claims the mock
 * mints are the claims this class mints, so `auth.js` and the page chrome
 * cannot tell mock mode from real mode.
 *
 * <p>jjwt is used as documented: {@code jjwt-api} at compile time, and
 * {@code jjwt-impl} + {@code jjwt-jackson} at runtime. Note the 0.12+/0.13 API —
 * {@code Jwts.parser().verifyWith(key).build()} and
 * {@code parseSignedClaims(...)}, <b>not</b> the deprecated
 * {@code parseClaimsJws(...)} that older tutorials use.
 */
@Component
public class JwtUtil {

    /**
     * HS256 is a 256-bit algorithm, so the key must be at least 32 bytes.
     * {@code Keys.hmacShaKeyFor} throws a bare {@code WeakKeyException} below
     * that; we check first so the failure names the property to fix.
     */
    private static final int MIN_SECRET_BYTES = 32;

    private final SecretKey key;
    private final long expiryMinutes;

    public JwtUtil(@Value("${yatra.jwt.secret:}") String secret,
                   @Value("${yatra.jwt.expiry-minutes:480}") long expiryMinutes) {
        byte[] bytes = String.valueOf(secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8);

        if (bytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "yatra.jwt.secret must be at least " + MIN_SECRET_BYTES
                            + " bytes for HS256 (got " + bytes.length + "). Set it in "
                            + "application-local.properties (git-ignored) or the JWT_SECRET "
                            + "environment variable.");
        }

        this.key = Keys.hmacShaKeyFor(bytes);
        this.expiryMinutes = expiryMinutes;
    }

    /**
     * Mints a token for a signed-in user. {@code role} is passed through raw
     * ({@code "ADMIN"}/{@code "USER"}) — {@link Authorities} owns the prefix, so
     * the token carries the same value the database stores.
     */
    public String generate(int userId, String name, String email, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("name", name)
                .claim("email", email)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiryMinutes, ChronoUnit.MINUTES)))
                .signWith(key)
                .compact();
    }

    /**
     * Verifies signature and expiry, then returns the claims.
     *
     * @throws io.jsonwebtoken.JwtException on an expired, tampered, malformed or
     *         wrong-signature token — callers treat that as "anonymous", never as
     *         a server error.
     */
    public Claims parse(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
