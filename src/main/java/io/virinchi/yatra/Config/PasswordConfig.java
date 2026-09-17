package io.virinchi.yatra.Config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * The one place the password hashing algorithm is decided.
 *
 * <p><b>Why a bean rather than {@code new BCryptPasswordEncoder()} at each call
 * site:</b> the register path and the login path must use the *same* encoder, or
 * no password would ever verify. A single bean also means a future switch (e.g.
 * to a {@code DelegatingPasswordEncoder}) happens in one file instead of being
 * silently inconsistent between the two paths.
 *
 * <p>Strength is left at BCrypt's default (10). That is a cost factor, not a
 * format, and BCrypt embeds it in the hash string itself — so it can be raised
 * later without any data migration.
 *
 * <p>{@code users.password} is {@code varchar(255)} and holds only this hash;
 * the raw password is never persisted, logged, or returned (the login response
 * goes through {@link io.virinchi.yatra.Dto.UserResponse}, which has no password
 * field).
 */
@Configuration
public class PasswordConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
