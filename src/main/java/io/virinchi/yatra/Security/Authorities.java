package io.virinchi.yatra.Security;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;
import java.util.Locale;

/**
 * **The one and only place a role string becomes a Spring Security authority.**
 *
 * <h2>Why this class exists (risk R3)</h2>
 * {@code User.role} stores plain {@code "ADMIN"} / {@code "USER"} — matching the
 * frontend roster in {@code mock-data.js}. But
 * {@code @PreAuthorize("hasRole('ADMIN')")} does <b>not</b> look for
 * {@code "ADMIN"}: {@code hasRole(...)} prepends {@code ROLE_} internally and
 * checks for the authority {@code "ROLE_ADMIN"}. Granting the database value
 * straight through therefore makes every admin endpoint a <b>403 for actual
 * admins</b>, while still *looking* correctly protected.
 *
 * <p>That leaves two ways to get it wrong: forgetting the prefix at one call
 * site, and adding it twice ({@code "ROLE_ROLE_ADMIN"}). Centralising the
 * conversion here closes both — {@link #of(String)} is the only supported path,
 * and it is idempotent, so a future {@code UserDetailsService} can pass either
 * {@code "ADMIN"} or {@code "ROLE_ADMIN"} and get the same authority.
 *
 * <p>Per the author's instruction the project stays on the {@code hasRole(...)}
 * convention everywhere; {@code hasAuthority("ADMIN")} is deliberately NOT used,
 * so nothing else may bypass this normalisation.
 */
public final class Authorities {

    private static final String ROLE_PREFIX = "ROLE_";

    private Authorities() {
        // static helper only
    }

    /**
     * Converts a stored role into the authority list Spring Security matches
     * against. {@code "ADMIN"} → {@code [ROLE_ADMIN]}; {@code "ROLE_ADMIN"} →
     * {@code [ROLE_ADMIN]}; {@code null}/blank → no authorities (an anonymous
     * principal with no role must never accidentally satisfy a role check).
     */
    public static List<GrantedAuthority> of(String role) {
        String authority = normalize(role);
        return authority.isEmpty()
                ? List.of()
                : List.of(new SimpleGrantedAuthority(authority));
    }

    /** Trims, upper-cases, and applies the {@code ROLE_} prefix exactly once. */
    private static String normalize(String role) {
        String value = String.valueOf(role == null ? "" : role).trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) {
            return "";
        }
        return value.startsWith(ROLE_PREFIX) ? value : ROLE_PREFIX + value;
    }
}
