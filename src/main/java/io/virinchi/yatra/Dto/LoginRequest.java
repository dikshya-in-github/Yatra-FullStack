package io.virinchi.yatra.Dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;

/**
 * The body of {@code POST /api/auth/login} — what {@code auth.js}'s
 * {@code login(loginId, password)} sends.
 *
 * <p>{@code loginId} is deliberately one field, not {@code email}/{@code phone}:
 * the sign-in page has a single input, and the mock resolves it as an email
 * (case-insensitively) <i>or</i> a mobile number. {@code Service/UserService}
 * keeps that behaviour, so an account created through either signup tab can sign
 * in through the one box.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LoginRequest(

        @NotBlank(message = "Email or mobile number is required.")
        String loginId,

        @NotBlank(message = "Password is required.")
        String password
) {
}
