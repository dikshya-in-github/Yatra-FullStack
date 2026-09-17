package io.virinchi.yatra;

import io.jsonwebtoken.Claims;
import io.virinchi.yatra.Exception.DuplicateResourceException;
import io.virinchi.yatra.Exception.GlobalExceptionHandler;
import io.virinchi.yatra.Exception.UnauthorizedException;
import io.virinchi.yatra.Model.User;
import io.virinchi.yatra.RestAPIController.AuthController;
import io.virinchi.yatra.Security.JwtUtil;
import io.virinchi.yatra.Service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP contract of {@code /api/auth/**}, verified without a database.
 *
 * <p>Standalone MockMvc, like {@link GlobalExceptionHandlerTest}: no application
 * context, no Hibernate, no MySQL. {@code UserService} is a Mockito mock, so
 * these are contract tests — status codes, error codes and the exact JSON keys
 * {@code auth.js} reads — not behaviour tests. Behaviour (hashing, uniqueness,
 * normalisation) is proved against the real stack in {@link AuthFlowTest}.
 *
 * <p>One deliberate difference from {@code GlobalExceptionHandlerTest}'s setup:
 * a {@code LocalValidatorFactoryBean} is installed, because the point of several
 * of these tests is that {@code @Valid} actually rejects a bad body before the
 * service is reached. Without an explicit validator, standalone MockMvc would
 * skip bean validation entirely and those tests would pass for the wrong reason.
 *
 * <p>Mockito is already on the test classpath through the Boot test starters, so
 * this introduces no new dependency.
 */
class AuthControllerTest {

    /** A throwaway signing key. Never a real secret — it only exists inside this test. */
    private static final String TEST_SECRET = "test-only-secret-with-at-least-32-bytes!!";

    private UserService userService;
    private JwtUtil jwtUtil;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        userService = mock(UserService.class);
        jwtUtil = new JwtUtil(TEST_SECRET, 480);

        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new AuthController(userService, jwtUtil))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    /* ------------------------------------------------------------------ *
     *  register                                                          *
     * ------------------------------------------------------------------ */

    @Test
    void registerReturnsATokenAndThePublicUserShape() throws Exception {
        when(userService.register(any())).thenReturn(sampleUser(7, "Dikshya Ghising",
                "dikshya@example.com", "9812345678", "USER"));

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Ms","firstName":"Dikshya","middleName":"",
                                 "lastName":"Ghising","dob":"2003-01-01",
                                 "email":"dikshya@example.com","phone":"9812345678",
                                 "password":"secret123","method":"email"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.userId").value(7))
                .andExpect(jsonPath("$.user.name").value("Dikshya Ghising"))
                .andExpect(jsonPath("$.user.email").value("dikshya@example.com"))
                .andExpect(jsonPath("$.user.phone").value("9812345678"))
                .andExpect(jsonPath("$.user.role").value("USER"))
                .andExpect(jsonPath("$.user.status").value("Active"))
                .andExpect(jsonPath("$.user.registeredAt").isNotEmpty());
    }

    /**
     * {@code title}, {@code dob} and {@code method} are in the signup form but not
     * in the table. The contract must stay wider than the schema — a payload that
     * the page actually sends may never fail with "unknown field".
     */
    @Test
    void registerToleratesTheFormFieldsTheUsersTableHasNoColumnFor() throws Exception {
        when(userService.register(any())).thenReturn(sampleUser(1, "A B", "a@example.com", null, "USER"));

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"Mr","firstName":"A","lastName":"B","dob":"2000-05-05",
                                 "email":"a@example.com","password":"secret123","method":"email"}
                                """))
                .andExpect(status().isOk());
    }

    /** The response body must never carry the hash, at any depth. */
    @Test
    void registerNeverReturnsThePasswordHash() throws Exception {
        User stored = sampleUser(3, "A B", "a@example.com", null, "USER");
        stored.setPassword("$2a$10$abcdefghijklmnopqrstuv");
        when(userService.register(any())).thenReturn(stored);

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"A","lastName":"B","email":"a@example.com","password":"secret123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.password").doesNotExist())
                .andExpect(content().string(not(containsString("$2a$"))));
    }

    /** A mobile-only account has no email; the mock answers {@code ""}, not null. */
    @Test
    void registerAnswersBlankStringsForAbsentFieldsRatherThanNull() throws Exception {
        when(userService.register(any())).thenReturn(sampleUser(9, "A B", null, "9812345678", "USER"));

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"A","lastName":"B","phone":"9812345678","password":"secret123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(""));
    }

    @Test
    void registerRejectsAPasswordShorterThanTheSharedMinimum() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"A","lastName":"B","email":"a@example.com","password":"short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        verify(userService, never()).register(any());
    }

    @Test
    void registerRejectsMissingNames() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"  ","lastName":"","email":"a@example.com","password":"secret123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        verify(userService, never()).register(any());
    }

    @Test
    void registerRejectsAMalformedEmail() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"A","lastName":"B","email":"not-an-email","password":"secret123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void registerAnswers409ForADuplicateEmail() throws Exception {
        when(userService.register(any())).thenThrow(DuplicateResourceException.emailExists());

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"A","lastName":"B","email":"taken@example.com","password":"secret123"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EMAIL_EXISTS"))
                .andExpect(jsonPath("$.message").value("An account with this email already exists."));
    }

    @Test
    void registerAnswers409ForADuplicateMobileNumber() throws Exception {
        when(userService.register(any())).thenThrow(DuplicateResourceException.phoneExists());

        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"A","lastName":"B","phone":"9812345678","password":"secret123"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PHONE_EXISTS"));
    }

    /* ------------------------------------------------------------------ *
     *  login                                                             *
     * ------------------------------------------------------------------ */

    @Test
    void loginReturnsATokenAndThePublicUser() throws Exception {
        when(userService.authenticate(anyString(), anyString()))
                .thenReturn(sampleUser(4, "A B", "a@example.com", "9812345678", "USER"));

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"a@example.com","password":"secret123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.userId").value(4))
                .andExpect(jsonPath("$.user.role").value("USER"));
    }

    @Test
    void loginAnswers401InvalidCredentials() throws Exception {
        when(userService.authenticate(anyString(), anyString()))
                .thenThrow(UnauthorizedException.invalidCredentials());

        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"a@example.com","password":"wrong-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("Invalid email/mobile or password."));
    }

    @Test
    void loginRejectsABlankIdentifierWithoutReachingTheService() throws Exception {
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"   ","password":"secret123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        verify(userService, never()).authenticate(anyString(), anyString());
    }

    /* ------------------------------------------------------------------ *
     *  the token                                                         *
     * ------------------------------------------------------------------ */

    /**
     * The claim set is frozen: {@code api.js}'s mock mints {@code sub, name,
     * email, role, iat, exp}, and {@code auth.js} reads {@code name}/{@code email}
     * straight from the payload for the navbar chip. Changing it breaks the
     * frontend silently, so it gets an explicit guard.
     */
    @Test
    void theIssuedTokenCarriesExactlyTheFrozenClaimSet() throws Exception {
        when(userService.authenticate(anyString(), anyString()))
                .thenReturn(sampleUser(42, "Dikshya Ghising", "dikshya@example.com", null, "ADMIN"));

        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"loginId":"dikshya@example.com","password":"secret123"}
                                """))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Pull the token out of the JSON without a mapper (the project's MVC
        // Jackson is Jackson 3, so no Jackson 2 ObjectMapper import here).
        String token = body.replaceAll("(?s).*\"token\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        Claims claims = jwtUtil.parse(token);

        assertThat(claims.getSubject()).as("sub is the user id").isEqualTo("42");
        assertThat(claims.get("name", String.class)).isEqualTo("Dikshya Ghising");
        assertThat(claims.get("email", String.class)).isEqualTo("dikshya@example.com");
        assertThat(claims.get("role", String.class))
                .as("the token carries the RAW role — Authorities owns the ROLE_ prefix")
                .isEqualTo("ADMIN");
        assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
        assertThat(claims.keySet())
                .as("exactly the claims the mock mints")
                .containsExactlyInAnyOrder("sub", "name", "email", "role", "iat", "exp");
    }

    /* ------------------------------------------------------------------ *
     *  fixtures                                                          *
     * ------------------------------------------------------------------ */

    private static User sampleUser(int id, String name, String email, String phone, String role) {
        User user = new User();
        user.setId(id);
        user.setName(name);
        user.setEmail(email);
        user.setPhone(phone);
        user.setRole(role);
        user.setStatus("Active");
        user.setRegisteredAt(LocalDateTime.of(2026, 9, 17, 9, 21, 34));
        return user;
    }
}
