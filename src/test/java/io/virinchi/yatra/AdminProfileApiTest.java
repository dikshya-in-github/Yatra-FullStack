package io.virinchi.yatra;

import io.virinchi.yatra.Model.User;
import io.virinchi.yatra.Repository.UserRepository;
import io.virinchi.yatra.Security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET}/{@code POST /api/admin/profile} — the endpoint that closed Phase 14's
 * checkpoint, and the tenth of the ten Master Plan §2.1 admin pages.
 *
 * <p>DB-backed, {@code @Transactional} and rolled back, like the other admin suites:
 * the live TiDB schema is never modified. The administrator is created <b>here</b>
 * rather than borrowed from the seed, because this endpoint resolves its subject from
 * the JWT's {@code sub} claim — so the test needs a token whose id it knows to be a
 * real, existing, {@code ADMIN} row, and a hard-coded id ({@code 1}) would silently
 * prove nothing on a database seeded in a different order.
 *
 * <h2>What this class is really asserting</h2>
 * <ul>
 *   <li><b>The page's contract, not a general one.</b> The body is
 *       {@code { user: { userId, name, email, phone, role, status, registeredAt } }} —
 *       the envelope and the {@code userId} key {@code admin-profile.js} reads, since
 *       it re-saves the record straight into {@code yatra_admin_session}. A numeric or
 *       bare body, or an {@code id} key (the roster's spelling), would leave the page
 *       with an undefined admin session and no error anywhere.</li>
 *   <li><b>The editable-field boundary is structural.</b> {@code role}, {@code status}
 *       and {@code id} are not fields of {@code ProfileUpdateRequest}, so sending them
 *       must be a no-op rather than a 400 or — far worse — a promotion. This is the
 *       one test here with a genuine security claim in it.</li>
 *   <li><b>{@code blank} means "unchanged", never "cleared".</b> The rule that lets the
 *       endpoint skip an "at least one identifier" check, asserted so it cannot be
 *       quietly reversed.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminProfileApiTest {

    private static final String ADMIN = "ADMIN";
    private static final String USER = "USER";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private UserRepository users;
    @Autowired private jakarta.persistence.EntityManager entityManager;

    /* ================================================================== *
     *  the page contract                                                 *
     * ================================================================== */

    /**
     * The body is exactly {@code { user }} — the one key the page reads — and the
     * record inside is the session shape, {@code userId} and all.
     */
    @Test
    void theBodyIsTheAdministratorsOwnAccount() throws Exception {
        User me = admin();

        JsonNode body = profile(tokenFor(me));

        assertThat(body.size())
                .as("res.user is the whole contract — anything else is extra surface")
                .isEqualTo(1);
        assertThat(body.get("user").isObject()).as("res.user").isTrue();

        JsonNode user = body.get("user");
        assertThat(user.get("userId").asInt())
                .as("the session shape's key is userId — auth.js and the navbar read it")
                .isEqualTo(me.getId());
        assertThat(user.get("name").asText()).isEqualTo(me.getName());
        assertThat(user.get("email").asText()).isEqualTo(me.getEmail());
        assertThat(user.get("phone").asText()).isEqualTo(me.getPhone());
        assertThat(user.get("role").asText()).as("the caller really is an administrator").isEqualTo(ADMIN);
        assertThat(user.get("status").asText()).isEqualTo("Active");
        assertThat(user.has("registeredAt")).as("registeredAt is part of the session shape").isTrue();

        assertThat(user.has("password"))
                .as("UserResponse is the boundary that keeps the BCrypt hash off the wire")
                .isFalse();
    }

    /* ================================================================== *
     *  the editable-field boundary                                       *
     * ================================================================== */

    /** The three fields a profile form owns are the three fields that change. */
    @Test
    void itEditsNameEmailAndPhone() throws Exception {
        User me = admin();
        String tag = tag();

        String newName = "Renamed Admin " + tag;
        String newEmail = "renamed-" + tag.toLowerCase() + "@yatra.com";
        String newPhone = mobile();

        JsonNode user = save(tokenFor(me), newName, newEmail, newPhone).get("user");

        assertThat(user.get("name").asText()).isEqualTo(newName);
        assertThat(user.get("email").asText()).isEqualTo(newEmail);
        assertThat(user.get("phone").asText()).isEqualTo(newPhone);

        // And it is a real write, not just an echo.
        User stored = reload(me.getId());
        assertThat(stored.getName()).isEqualTo(newName);
        assertThat(stored.getEmail()).isEqualTo(newEmail);
        assertThat(stored.getPhone()).isEqualTo(newPhone);

        assertThat(user.get("userId").asInt()).as("a profile edit never changes the id").isEqualTo(me.getId());
    }

    /**
     * The boundary this endpoint exists to hold: {@code role}, {@code status} and
     * {@code id} are not part of the request type, so sending them cannot promote the
     * caller, reactivate them, or move the write to another row.
     */
    @Test
    void roleStatusAndIdCannotBeChangedFromThisEndpoint() throws Exception {
        User me = admin();

        JsonNode user = save(tokenFor(me),
                "Still An Admin " + tag(),
                me.getEmail(),
                me.getPhone(),
                // The attack, spelled out: pretend to be a customer, deactivated, and
                // editing somebody else's record.
                """
                ,"role":"USER","status":"Inactive","id":999999,"seeded":true
                """)
                .get("user");

        assertThat(user.get("role").asText())
                .as("@JsonIgnoreProperties(ignoreUnknown = true) — an unknown key is ignored, never bound")
                .isEqualTo(ADMIN);
        assertThat(user.get("status").asText())
                .as("an administrator cannot deactivate themselves through their own profile")
                .isEqualTo("Active");
        assertThat(user.get("userId").asInt()).as("the id came from the JWT, not the body").isEqualTo(me.getId());

        User stored = reload(me.getId());
        assertThat(stored.getRole()).isEqualTo(ADMIN);
        assertThat(stored.getStatus()).isEqualTo("Active");
        assertThat(stored.isSeeded())
                .as("`seeded` is not writable from any endpoint — a hand-set marker would hide the row from reset")
                .isFalse();
    }

    /** Blank means "leave it alone" — never "clear it", and never a 409 against itself. */
    @Test
    void blankFieldsKeepTheirStoredValue() throws Exception {
        User me = admin();

        JsonNode user = save(tokenFor(me), "Renamed Only " + tag(), "", "").get("user");

        assertThat(user.get("name").asText()).startsWith("Renamed Only");
        assertThat(user.get("email").asText())
                .as("a blank email must not wipe the address this account signs in with")
                .isEqualTo(me.getEmail());
        assertThat(user.get("phone").asText()).isEqualTo(me.getPhone());
    }

    /* ================================================================== *
     *  uniqueness — the page's own 409 codes                             *
     * ================================================================== */

    /** Another account's email is a 409 {@code EMAIL_EXISTS}, the code the page paints. */
    @Test
    void anEmailAnotherAccountOwnsIsRefused() throws Exception {
        User me = admin();
        User other = admin();

        mockMvc.perform(post("/api/admin/profile")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(me)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("Colliding " + tag(), other.getEmail(), me.getPhone())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EMAIL_EXISTS"));

        assertThat(reload(me.getId()).getEmail())
                .as("a refused save must not have written anything")
                .isEqualTo(me.getEmail());
    }

    /** Another account's mobile number is a 409 {@code PHONE_EXISTS}. */
    @Test
    void aMobileAnotherAccountOwnsIsRefused() throws Exception {
        User me = admin();
        User other = admin();

        mockMvc.perform(post("/api/admin/profile")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(me)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("Colliding " + tag(), me.getEmail(), other.getPhone())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PHONE_EXISTS"));

        assertThat(reload(me.getId()).getPhone()).isEqualTo(me.getPhone());
    }

    /**
     * Resubmitting the values the account already has is a success, not a collision —
     * the reason the checks are {@code existsByEmailAndIdNot}. A profile form posts
     * every field on every save, so a plain existence check would make "save" fail on
     * an untouched form.
     */
    @Test
    void savingItsOwnUnchangedValuesIsNotACollision() throws Exception {
        User me = admin();

        JsonNode user = save(tokenFor(me), me.getName(), me.getEmail(), me.getPhone()).get("user");

        assertThat(user.get("email").asText()).isEqualTo(me.getEmail());
        assertThat(user.get("phone").asText()).isEqualTo(me.getPhone());
    }

    /* ================================================================== *
     *  authorization — both layers                                        *
     * ================================================================== */

    @Test
    void onlyAnAdminReachesTheProfile() throws Exception {
        mockMvc.perform(get("/api/admin/profile"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/admin/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("Nobody", "nobody@yatra.com", mobile())))
                .andExpect(status().isUnauthorized());

        String userToken = jwtUtil.generate(2, "Anju Karki", "anju.karki@example.com", USER);
        mockMvc.perform(get("/api/admin/profile").header(HttpHeaders.AUTHORIZATION, bearer(userToken)))
                .andExpect(status().isForbidden());
    }

    /* ================================================================== *
     *  helpers                                                            *
     * ================================================================== */

    /** The profile as the given token's owner, parsed. */
    private JsonNode profile(String token) throws Exception {
        String body = mockMvc.perform(get("/api/admin/profile")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").exists())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body);
    }

    /** A successful save, parsed. */
    private JsonNode save(String token, String name, String email, String phone) throws Exception {
        return save(token, name, email, phone, "");
    }

    /** A successful save whose body carries {@code extra} verbatim before the brace. */
    private JsonNode save(String token, String name, String email, String phone, String extra)
            throws Exception {
        String body = mockMvc.perform(post("/api/admin/profile")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(name, email, phone, extra)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body);
    }

    private static String payload(String name, String email, String phone) {
        return payload(name, email, phone, "");
    }

    /** Built by concatenation so an "extra keys" test can pass raw JSON in the middle. */
    private static String payload(String name, String email, String phone, String extra) {
        return """
                {"name":"%s","email":"%s","phone":"%s"%s}
                """.formatted(name, email, phone, extra);
    }

    /**
     * An {@code ADMIN} row the token can point at, committed to the transaction's view
     * so the service's own {@code require(userId)} finds it.
     */
    private User admin() {
        String tag = tag();

        User user = new User();
        user.setName("Test Admin " + tag);
        user.setEmail("test-admin-" + tag.toLowerCase() + "@yatra.com");
        user.setPhone(mobile());
        user.setPassword("$2a$10$not-a-real-hash-this-suite-never-signs-in");
        user.setRole(ADMIN);
        user.setStatus("Active");
        user.setRegisteredAt(LocalDateTime.now());
        // seeded stays false: a test fixture is not the demo roster (R14).

        User saved = users.save(user);
        entityManager.flush();
        return saved;
    }

    private User reload(int id) {
        entityManager.flush();
        entityManager.clear();
        return users.findById(id).orElseThrow();
    }

    private String tokenFor(User user) {
        return jwtUtil.generate(user.getId(), user.getName(), user.getEmail(), ADMIN);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    /** A 10-digit Nepali mobile number, unique enough for a rolled-back fixture. */
    private static String mobile() {
        return "98" + String.format("%08d",
                Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
    }

    private static String tag() {
        return "P" + UUID.randomUUID().toString().substring(0, 8);
    }
}
