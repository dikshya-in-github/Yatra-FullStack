package io.virinchi.yatra.Service;

import io.virinchi.yatra.Dto.RegisterRequest;
import io.virinchi.yatra.Exception.DuplicateResourceException;
import io.virinchi.yatra.Exception.UnauthorizedException;
import io.virinchi.yatra.Exception.ValidationException;
import io.virinchi.yatra.Model.User;
import io.virinchi.yatra.Repository.UserRepository;
import io.virinchi.yatra.Security.Authorities;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Account creation and sign-in — the data half of auth, with no knowledge of
 * JWT or HTTP.
 *
 * <p>Also the project's {@link UserDetailsService}: Spring Security's contract
 * needs one to exist, and {@link Authorities} is the single role conversion it
 * must go through.
 *
 * <h2>Two rules that are easy to get subtly wrong</h2>
 *
 * <p><b>1. Blank means {@code null} in the database, never {@code ""}.</b> Both
 * unique indexes are real, and MySQL/TiDB allow repeated {@code NULL}s in a
 * unique index but treat {@code ""} as a value. Storing the empty string for a
 * user who signed up with only a mobile number would make the <i>second</i> such
 * user fail with a bogus {@code 409 PHONE_EXISTS} — a bug that only appears once
 * two people register the same way.
 *
 * <p><b>2. The email is lower-cased before it is stored.</b> {@code users.email}
 * is {@code COLLATE=utf8mb4_bin}, i.e. case-<b>sensitive</b>, so the database
 * would happily accept {@code A@x.com} and {@code a@x.com} as two accounts.
 * Normalising on the way in is what makes "case-insensitive" true at the storage
 * layer rather than only in the lookup. Trade-off, stated plainly: the stored
 * value is not byte-identical to what was typed; the display name is unaffected,
 * and login stays case-insensitive either way.
 *
 * <p>{@code register} and {@code authenticate} return the entity so the
 * controller can shape the response ({@code Dto/UserResponse} is the public
 * shape, and {@code User.password} carries {@code @JsonIgnore} as a second
 * line of defence).
 */
@Service
public class UserService implements UserDetailsService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    /**
     * A real BCrypt hash, compared against when the identifier does not exist so
     * that "no such account" costs the same as "wrong password". Without it the
     * not-found path returns in a few milliseconds while a real compare takes
     * ~100ms of BCrypt — a difference large enough to enumerate registered
     * emails with. Computed once at startup, not per request.
     */
    private final String timingDefenceHash;

    /*
     * Written out rather than @RequiredArgsConstructor: the third field needs the
     * encoder to produce a hash, which Lombok's generated constructor cannot do.
     */
    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.timingDefenceHash = passwordEncoder.encode("yatra-timing-defence-not-a-credential");
    }

    /* ------------------------------------------------------------------ *
     *  Registration                                                       *
     * ------------------------------------------------------------------ */

    /**
     * Creates an {@code Active}/{@code USER} account with a BCrypt hash.
     *
     * @throws ValidationException       no email and no mobile number, or a
     *                                   mobile number that is not 10 digits
     * @throws DuplicateResourceException 409 {@code EMAIL_EXISTS} /
     *                                   {@code PHONE_EXISTS} — the codes the
     *                                   frontend already paints onto the field
     */
    @Transactional
    public User register(RegisterRequest request) {
        String email = blankToNull(request.email());
        String phone = blankToNull(normalizePhone(request.phone()));

        if (email == null && phone == null) {
            throw new ValidationException("Provide an email address or a mobile number.");
        }

        if (email != null) {
            email = email.toLowerCase(Locale.ROOT);
            if (userRepository.existsByEmail(email)) {
                throw DuplicateResourceException.emailExists();
            }
        }

        if (phone != null) {
            if (!phone.matches("\\d{10}")) {
                throw new ValidationException("Enter a 10-digit mobile number.");
            }
            if (userRepository.existsByPhone(phone)) {
                throw DuplicateResourceException.phoneExists();
            }
        }

        User user = new User();
        user.setName(fullName(request));
        user.setEmail(email);
        user.setPhone(phone);
        user.setPassword(passwordEncoder.encode(request.password()));
        user.setRole("USER");
        user.setStatus("Active");
        user.setRegisteredAt(LocalDateTime.now());

        return userRepository.save(user);
    }

    /* ------------------------------------------------------------------ *
     *  Sign-in                                                            *
     * ------------------------------------------------------------------ */

    /**
     * Verifies an identifier (email or mobile) and a password.
     *
     * <p>Both failure modes throw the <b>same</b> {@code 401 INVALID_CREDENTIALS}
     * — a caller must not be able to tell "no such account" from "wrong
     * password", which is also why the not-found branch still performs a BCrypt
     * compare.
     */
    @Transactional(readOnly = true)
    public User authenticate(String loginId, String rawPassword) {
        Optional<User> found = findByIdentifier(loginId);
        String password = String.valueOf(rawPassword == null ? "" : rawPassword);

        if (found.isEmpty()) {
            passwordEncoder.matches(password, timingDefenceHash);
            throw UnauthorizedException.invalidCredentials();
        }

        User user = found.get();
        String stored = user.getPassword();
        if (stored == null || stored.isBlank()
                || !passwordEncoder.matches(password, stored)) {
            throw UnauthorizedException.invalidCredentials();
        }

        return user;
    }

    /* ------------------------------------------------------------------ *
     *  Spring Security integration                                        *
     * ------------------------------------------------------------------ */

    /**
     * Loads a principal for Spring Security. The role goes through
     * {@link Authorities#of(String)} and nowhere else, so {@code hasRole(...)}
     * matches; {@code status} maps to {@code disabled} so an Inactive account
     * cannot authenticate through the framework path.
     */
    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String loginId) throws UsernameNotFoundException {
        User user = findByIdentifier(loginId).orElseThrow(() -> new UsernameNotFoundException(
                "No account matches the supplied identifier."));

        String username = user.getEmail() != null && !user.getEmail().isBlank()
                ? user.getEmail()
                : String.valueOf(user.getPhone());

        return org.springframework.security.core.userdetails.User
                .withUsername(username)
                .password(String.valueOf(user.getPassword() == null ? "" : user.getPassword()))
                .authorities(Authorities.of(user.getRole()))
                .disabled(!"Active".equalsIgnoreCase(String.valueOf(user.getStatus())))
                .build();
    }

    /* ------------------------------------------------------------------ *
     *  Helpers                                                            *
     * ------------------------------------------------------------------ */

    /**
     * Resolves the single sign-in box to a row: anything containing {@code @} is
     * treated as an email (case-insensitive, matching {@code findByEmailIgnoreCase}
     * and the mock's {@code findUser}), everything else as a mobile number in its
     * stored 10-digit form.
     */
    private Optional<User> findByIdentifier(String loginId) {
        String value = String.valueOf(loginId == null ? "" : loginId).trim();
        if (value.isEmpty()) {
            return Optional.empty();
        }
        if (value.contains("@")) {
            return userRepository.findByEmailIgnoreCase(value);
        }
        String digits = normalizePhone(value);
        return digits.isEmpty() ? Optional.empty() : userRepository.findByPhone(digits);
    }

    /** {@code null} and {@code ""} both mean "not supplied" — see the class docs. */
    private static String blankToNull(String value) {
        String trimmed = String.valueOf(value == null ? "" : value).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Mirrors {@code MockDB.normalizePhone}: digits only, and a {@code +977}
     * country code is stripped, so {@code "+977 9812345678"} and
     * {@code "9812345678"} are one account rather than two.
     */
    private static String normalizePhone(String value) {
        String digits = String.valueOf(value == null ? "" : value).replaceAll("\\D", "");
        return digits.length() == 13 && digits.startsWith("977") ? digits.substring(3) : digits;
    }

    /** The full name exactly as the mock builds it: first / middle / last, blanks dropped. */
    private static String fullName(RegisterRequest request) {
        List<String> parts = Arrays.asList(request.firstName(), request.middleName(),
                        request.lastName()).stream()
                .map(UserService::blankToNull)
                .filter(part -> part != null)
                .toList();
        return String.join(" ", parts);
    }
}
