package io.virinchi.yatra;

import io.virinchi.yatra.Dto.RegisterRequest;
import io.virinchi.yatra.Exception.DuplicateResourceException;
import io.virinchi.yatra.Exception.UnauthorizedException;
import io.virinchi.yatra.Model.User;
import io.virinchi.yatra.Repository.UserRepository;
import io.virinchi.yatra.Security.JwtUtil;
import io.virinchi.yatra.Service.UserService;
import io.jsonwebtoken.Claims;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Registration and sign-in against the real stack — entity, repository, BCrypt
 * and the HTTP layer.
 *
 * <p>{@code @Transactional} at class level, like {@code BookingDeletionTest}:
 * every row these tests create is rolled back, so the live TiDB Cloud database
 * is never modified by a test run. Identifiers are still made unique per run so
 * the assertions stay meaningful whichever order the tests run in.
 *
 * <p>Together with {@link AuthControllerTest} this covers the Roadmap Phase 3
 * checkpoint: the HTTP shapes and error codes are proved without a database, and
 * the storage rules — a hashed password, case-insensitive email, one 10-digit
 * form per mobile number — are proved against the real schema.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuthFlowTest {

    @Autowired private UserService userService;
    @Autowired private UserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private MockMvc mockMvc;

    @PersistenceContext private EntityManager em;

    /* ------------------------------------------------------------------ *
     *  what gets stored                                                   *
     * ------------------------------------------------------------------ */

    @Test
    void registerStoresABCryptHashAndNeverTheRawPassword() {
        String email = uniqueEmail();

        User user = userService.register(request("Dikshya", "Ghising", email, null, "secret123"));

        assertThat(user.getPassword())
                .as("a BCrypt hash, not the password")
                .isNotEqualTo("secret123")
                .startsWith("$2");
        assertThat(passwordEncoder.matches("secret123", user.getPassword()))
                .as("the hash verifies against what was typed")
                .isTrue();

        // Write the pending insert and detach everything, so this read comes back
        // from the database rather than out of the persistence context.
        em.flush();
        em.clear();

        assertThat(users.findById(user.getId()).orElseThrow().getPassword())
                .as("the row in the database holds the hash too")
                .startsWith("$2");
    }

    @Test
    void registerCreatesAnActiveAccountWithTheUserRole() {
        User user = userService.register(request("Dikshya", "", uniqueEmail(), null, "secret123"));

        assertThat(user.getRole()).isEqualTo("USER");
        assertThat(user.getStatus()).isEqualTo("Active");
        assertThat(user.getRegisteredAt()).as("registeredAt is stamped").isNotNull();
        assertThat(user.getName()).as("first/last joined the way the mock joins them")
                .isEqualTo("Dikshya");
    }

    @Test
    void registerJoinsTheThreeNamePartsAndDropsBlanks() {
        User user = userService.register(new RegisterRequest(
                "Ms", "Dikshya", "  ", "Ghising", uniqueEmail(), null, "secret123", "email"));

        assertThat(user.getName()).isEqualTo("Dikshya Ghising");
    }

    /* ------------------------------------------------------------------ *
     *  uniqueness                                                        *
     * ------------------------------------------------------------------ */

    @Test
    void registerRefusesADuplicateEmailEvenWhenTheCaseDiffers() {
        String email = uniqueEmail();
        userService.register(request("First", "User", email, null, "secret123"));

        // The column is COLLATE=utf8mb4_bin (case-SENSITIVE), so this would be a
        // *different* row to the database. Normalising on write is what stops it.
        String sameAddressDifferentCase = email.toUpperCase();

        assertThatThrownBy(() -> userService.register(
                request("Second", "User", sameAddressDifferentCase, null, "secret123")))
                .isInstanceOf(DuplicateResourceException.class);

        DuplicateResourceException ex = assertThrows(DuplicateResourceException.class,
                () -> userService.register(request("Third", "User", email, null, "secret123")));
        assertThat(ex.getCode()).isEqualTo("EMAIL_EXISTS");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void registerRefusesADuplicateMobileEvenWhenTheCountryCodeIsIncluded() {
        String phone = uniquePhone();
        userService.register(request("First", "User", uniqueEmail(), phone, "secret123"));

        // "+977 98…" normalises to the same 10 digits the first account stored.
        assertThatThrownBy(() -> userService.register(request(
                "Second", "User", uniqueEmail(), "+977 " + phone, "secret123")))
                .isInstanceOf(DuplicateResourceException.class)
                .hasFieldOrPropertyWithValue("code", "PHONE_EXISTS");
    }

    /**
     * The reason blanks are stored as {@code NULL} rather than {@code ""}: a unique
     * index tolerates many NULLs but only one empty string, so with {@code ""} the
     * <i>second</i> email-only account would be refused with a bogus PHONE_EXISTS.
     */
    @Test
    void twoAccountsWithoutAMobileNumberCanBothBeCreated() {
        User first = userService.register(request("First", "User", uniqueEmail(), null, "secret123"));
        User second = userService.register(request("Second", "User", uniqueEmail(), "", "secret123"));

        assertThat(first.getPhone()).as("no mobile number is stored as NULL").isNull();
        assertThat(second.getPhone()).isNull();
    }

    @Test
    void registerRefusesAMobileNumberThatIsNotTenDigits() {
        assertThatThrownBy(() -> userService.register(
                request("A", "B", uniqueEmail(), "12345", "secret123")))
                .hasMessageContaining("10-digit");
    }

    @Test
    void registerRefusesARequestWithNeitherAnEmailNorAMobileNumber() {
        assertThatThrownBy(() -> userService.register(request("A", "B", null, "  ", "secret123")))
                .hasMessageContaining("email address or a mobile number");
    }

    /* ------------------------------------------------------------------ *
     *  sign-in                                                            *
     * ------------------------------------------------------------------ */

    @Test
    void signInAcceptsTheEmailInAnyCase() {
        String email = uniqueEmail();
        userService.register(request("Dikshya", "Ghising", email, null, "secret123"));

        assertThat(userService.authenticate(email.toUpperCase(), "secret123").getEmail())
                .isEqualTo(email);
    }

    @Test
    void signInAcceptsTheMobileNumberWithOrWithoutTheCountryCode() {
        String phone = uniquePhone();
        userService.register(request("Dikshya", "Ghising", uniqueEmail(), "+977" + phone, "secret123"));

        assertThat(userService.authenticate(phone, "secret123").getPhone())
                .as("the stored 10-digit form works")
                .isEqualTo(phone);
        assertThat(userService.authenticate("+977 " + phone, "secret123").getPhone())
                .as("and so does the country-coded form")
                .isEqualTo(phone);
    }

    @Test
    void aWrongPasswordIsRefusedWith401InvalidCredentials() {
        String email = uniqueEmail();
        userService.register(request("Dikshya", "Ghising", email, null, "secret123"));

        UnauthorizedException ex = assertThrows(UnauthorizedException.class,
                () -> userService.authenticate(email, "not-the-password"));

        assertThat(ex.getCode()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(ex.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    /**
     * The two failure modes must be indistinguishable — same code, same message —
     * or the endpoint tells an attacker which addresses are registered.
     */
    @Test
    void anUnknownIdentifierIsRefusedExactlyLikeAWrongPassword() {
        String email = uniqueEmail();
        userService.register(request("Dikshya", "Ghising", email, null, "secret123"));

        UnauthorizedException wrongPassword = assertThrows(UnauthorizedException.class,
                () -> userService.authenticate(email, "not-the-password"));
        UnauthorizedException noSuchAccount = assertThrows(UnauthorizedException.class,
                () -> userService.authenticate("nobody-" + email, "not-the-password"));

        assertThat(noSuchAccount.getCode()).isEqualTo(wrongPassword.getCode());
        assertThat(noSuchAccount.getMessage()).isEqualTo(wrongPassword.getMessage());
    }

    /* ------------------------------------------------------------------ *
     *  the whole path over HTTP                                           *
     * ------------------------------------------------------------------ */

    @Test
    void overHttpARegisteredAccountCanSignInAndTheTokenCarriesTheFrozenClaims() throws Exception {
        String email = uniqueEmail();

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Dikshya","lastName":"Ghising","email":"%s",
                                 "password":"secret123","method":"email"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.userId").isNumber())
                .andExpect(jsonPath("$.user.role").value("USER"));

        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"%s","password":"secret123"}
                                """.formatted(email)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String token = body.replaceAll("(?s).*\"token\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        Claims claims = jwtUtil.parse(token);

        assertThat(claims.get("email", String.class)).isEqualTo(email);
        assertThat(claims.get("role", String.class))
                .as("raw role — JwtAuthFilter converts it via Authorities")
                .isEqualTo("USER");
    }

    /* ------------------------------------------------------------------ *
     *  fixtures                                                          *
     * ------------------------------------------------------------------ */

    private static RegisterRequest request(String first, String last,
                                           String email, String phone, String password) {
        return new RegisterRequest(null, first, null, last, email, phone, password, "email");
    }

    /** Unique per call, so a re-run or a shared database cannot collide. */
    private static String uniqueEmail() {
        return "auth-test-" + UUID.randomUUID() + "@example.com";
    }

    private static String uniquePhone() {
        return "98" + String.format("%08d", Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
    }
}
