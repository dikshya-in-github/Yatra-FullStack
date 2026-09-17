package io.virinchi.yatra;

import io.virinchi.yatra.Security.Authorities;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The regression guard for risk R3.
 *
 * <p>The bug this prevents: {@code hasRole('ADMIN')} matches the authority
 * {@code ROLE_ADMIN}, but the database stores {@code "ADMIN"}. Grant the raw
 * value and every admin endpoint returns 403 to a real admin — a failure that
 * looks like correct protection.
 *
 * <p>These are plain unit tests: no Spring context, no database, so they run in
 * milliseconds and cannot be blocked by the remote TiDB instance.
 */
class AuthoritiesTest {

    @Test
    void plainRoleFromTheDatabaseGetsTheRolePrefix() {
        assertThat(Authorities.of("ADMIN"))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void userRoleGetsThePrefixToo() {
        assertThat(Authorities.of("USER"))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_USER");
    }

    @Test
    void lowerCaseAndPaddedRolesAreNormalised() {
        assertThat(Authorities.of("  admin "))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void anAlreadyPrefixedRoleIsNotDoublePrefixed() {
        // The other half of R3: "ROLE_ROLE_ADMIN" would be just as broken.
        assertThat(Authorities.of("ROLE_ADMIN"))
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_ADMIN");
    }

    @Test
    void nullAndBlankYieldNoAuthorities() {
        assertThat(Authorities.of(null)).isEmpty();
        assertThat(Authorities.of("")).isEmpty();
        assertThat(Authorities.of("   ")).isEmpty();
    }
}
