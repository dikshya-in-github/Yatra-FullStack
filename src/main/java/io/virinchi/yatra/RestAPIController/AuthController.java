package io.virinchi.yatra.RestAPIController;

import io.virinchi.yatra.Dto.AuthResponse;
import io.virinchi.yatra.Dto.LoginRequest;
import io.virinchi.yatra.Dto.RegisterRequest;
import io.virinchi.yatra.Dto.UserResponse;
import io.virinchi.yatra.Model.User;
import io.virinchi.yatra.Security.JwtUtil;
import io.virinchi.yatra.Service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/auth/**} — the two endpoints that mint a session.
 *
 * <p>Thin on purpose: no try/catch and no error bodies. Failures are thrown as
 * {@code Exception/} subclasses and shaped by {@code GlobalExceptionHandler},
 * which is why the 409 {@code EMAIL_EXISTS} / 401 {@code INVALID_CREDENTIALS}
 * bodies match the mock exactly without this class knowing their format.
 *
 * <p><b>Both endpoints answer 200, not 201.</b> The mock resolves register with
 * a normal success body and {@code api.js} only inspects the JSON, so a 201 would
 * be a gratuitous difference between the two modes.
 *
 * <p>{@code SecurityConfig} already permits {@code /api/auth/**} — no change was
 * needed there beyond the skeleton that landed with the security work.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final JwtUtil jwtUtil;

    /**
     * Creates the account and signs it in, exactly as the mock does — the signup
     * page lands the new customer on homeLogged.html, so it needs a session back.
     */
    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) {
        return tokenFor(userService.register(request));
    }

    /** Signs in with an email or a mobile number plus password. */
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) {
        return tokenFor(userService.authenticate(request.loginId(), request.password()));
    }

    /**
     * Mints the token and the public user together.
     *
     * <p>Every claim is passed null-safe: a mobile-only account has no email, and
     * the mock mints {@code email: ""} rather than an absent claim. A null claim
     * would make the two token shapes differ, and {@code auth.js} reads
     * {@code name}/{@code email} straight out of the payload for the navbar chip.
     */
    private AuthResponse tokenFor(User user) {
        String token = jwtUtil.generate(
                user.getId(),
                blankIfNull(user.getName()),
                blankIfNull(user.getEmail()),
                user.getRole());

        return new AuthResponse(token, UserResponse.of(user));
    }

    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }
}
