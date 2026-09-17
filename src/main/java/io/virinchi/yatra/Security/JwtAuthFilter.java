package io.virinchi.yatra.Security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads {@code Authorization: Bearer <token>}, and — when the token verifies —
 * puts an authenticated principal into the security context.
 *
 * <p><b>This is where R3 is actually fixed.</b> The token's {@code role} claim
 * holds the raw database value ({@code "ADMIN"}); passing that straight into a
 * {@code SimpleGrantedAuthority} would produce {@code "ADMIN"}, which
 * {@code hasRole('ADMIN')} never matches. {@link Authorities#of(String)} is the
 * single conversion point that yields {@code ROLE_ADMIN} (and is idempotent, so
 * it cannot double-prefix). Do not hand-roll an authority anywhere else.
 *
 * <p>Runs once per request, and is registered by {@code SecurityConfig} before
 * {@code UsernamePasswordAuthenticationFilter}.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtUtil jwtUtil;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            try {
                Claims claims = jwtUtil.parse(token);

                var authentication = new UsernamePasswordAuthenticationToken(
                        claims.getSubject(),
                        null,
                        Authorities.of(claims.get("role", String.class)));

                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);

            } catch (JwtException | IllegalArgumentException ex) {
                // Expired, tampered with, or malformed. Treat the request as
                // anonymous and carry on: the filter chain then answers 401 via
                // SecurityConfig's entry point. Never throw from here — a bad
                // token is a client problem, not a 500.
                SecurityContextHolder.clearContext();
            }
        }

        filterChain.doFilter(request, response);
    }
}
