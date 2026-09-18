package io.virinchi.yatra;

import io.virinchi.yatra.Model.Airline;
import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Repository.AirlineRepository;
import io.virinchi.yatra.Repository.DestinationRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import io.virinchi.yatra.Security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Roadmap Phase 11 — admin user management: the roster read with its filters and paging,
 * create, edit, activate/deactivate, delete and the per-account booking read.
 *
 * <p>DB-backed and {@code @Transactional} like {@code AdminBookingApiTest}, so every row is
 * rolled back and the live TiDB schema is never modified. Every account this suite touches
 * is created <b>through the API under test</b> — that is the point of the phase: an admin
 * can now populate the roster, and a test that inserted users through the repository would
 * prove the opposite of what it claims.
 *
 * <h2>The two assertions that are about the <i>contract</i>, not the data</h2>
 * <ul>
 *   <li><b>{@code id}, not {@code userId}.</b> {@code admin-users.js} builds every row
 *       action from {@code u.id} — {@code data-edit}, {@code data-toggle},
 *       {@code data-delete} — and matches the modal back to the row with
 *       {@code String(x.id) === id}. The session shape ({@code UserResponse}) calls the same
 *       column {@code userId}, so getting this wrong would leave the roster rendering
 *       perfectly with every action pointing at a fabricated id. {@link
 *       #theAdminRosterAnswersTheMockRecordShape()} pins the key.</li>
 *   <li><b>The wrapper is {@code { users: [...] }}.</b> {@code admin-users.js}'s first
 *       render reads {@code resp.users} and its {@code .catch()} falls back to the page's
 *       localStorage seeds, so a bare array or a serialised {@code Page} would show demo
 *       rows as though they were real ones, silently.</li>
 * </ul>
 *
 * <h2>The end-to-end ones, which is what this phase is really about</h2>
 * <p>The interesting behaviour of "deactivate" is not that a column changed — it is that
 * the account can no longer sign in, and {@code UserService.authenticate} did not check
 * {@code status} at all before this phase. So the headline test creates an account through
 * the admin API, signs it in through the real login endpoint, deactivates it, proves the
 * sign-in is refused with {@code ACCOUNT_DISABLED}, reactivates it and signs in again. The
 * delete guard is proved the same way: a booking made <b>by that account</b> through
 * {@code POST /api/bookings} with its own token is what makes the delete a 409.
 *
 * <h2>Uniqueness, so a live database cannot make this flaky</h2>
 * <p>Every searchable value carries the run's {@link #tag()} — name, email and phone — and
 * every assertion is scoped by that tag rather than by a global count, because the author's
 * live schema may hold seeded demo users.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminUserApiTest {

    private static final String ADMIN = "ADMIN";
    private static final String USER = "USER";
    private static final String ACTIVE = "Active";
    private static final String INACTIVE = "Inactive";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private AirlineRepository airlines;
    @Autowired private DestinationRepository destinations;
    @Autowired private FlightRepository flights;
    @Autowired private JwtUtil jwtUtil;

    /* ------------------------------------------------------------------ *
     *  the roster read                                                    *
     * ------------------------------------------------------------------ */

    /**
     * The page's record shape, key for key — and the one key that would break it silently.
     */
    @Test
    void theAdminRosterAnswersTheMockRecordShape() throws Exception {
        String tag = tag();
        String email = email(tag);
        String phone = phone(tag);
        int id = createId(tag, USER, ACTIVE, "secret123");

        mockMvc.perform(get("/api/admin/users").param("search", tag)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users").isArray())
                .andExpect(jsonPath("$.users.length()").value(1))
                // R16 on this surface: the row actions are built from `id`.
                .andExpect(jsonPath("$.users[0].id").value(id))
                .andExpect(jsonPath("$.users[0].name").value("Roster " + tag))
                .andExpect(jsonPath("$.users[0].email").value(email))
                .andExpect(jsonPath("$.users[0].phone").value(phone))
                .andExpect(jsonPath("$.users[0].role").value(USER))
                .andExpect(jsonPath("$.users[0].status").value(ACTIVE))
                .andExpect(jsonPath("$.users[0].registeredAt").isNotEmpty())
                // Provisioned with a password, so the account is usable straight away.
                .andExpect(jsonPath("$.users[0].canSignIn").value(true))
                // The hash never leaves the server.
                .andExpect(jsonPath("$.users[0].password").doesNotExist())
                // Unpaged by default, so the paging keys must be absent, not null.
                .andExpect(jsonPath("$.page").doesNotExist())
                .andExpect(jsonPath("$.size").doesNotExist())
                .andExpect(jsonPath("$.totalElements").doesNotExist());
    }

    /**
     * One search box, four kinds of value. The page's own filter matches
     * {@code u.name}/{@code u.email}/{@code u.phone}/{@code String(u.id)}, so the server
     * has to answer all four or pasting an id back would look like an empty roster.
     */
    @Test
    void searchMatchesNameEmailPhoneAndId() throws Exception {
        String tag = tag();
        String email = email(tag);
        String phone = phone(tag);
        int id = createId(tag, USER, ACTIVE, null);

        assertThat(count("search", tag)).as("name").isEqualTo(1);
        assertThat(count("search", email)).as("email, exact").isEqualTo(1);
        assertThat(count("search", phone)).as("mobile number").isEqualTo(1);
        assertThat(count("search", String.valueOf(id))).as("id").isEqualTo(1);
        assertThat(count("search", "no-such-" + tag)).as("a term that matches nothing").isZero();
    }

    /**
     * Both vocabularies are accepted, because the filter chips and the storage differ.
     *
     * <p>Every count is scoped by this run's tag. A bare {@code role=USER} is a question
     * about the whole {@code users} table — the demo seed fills it and a live Postman
     * walk adds to it — so an unscoped count is only 1 while the database happens to be
     * otherwise empty. That is how this test failed once the seed had run: it was
     * measuring the ambient table, not vocabulary-insensitivity, which is what the name
     * promises and what the tagged counts below still prove.
     */
    @Test
    void roleAndStatusFiltersAreCaseInsensitive() throws Exception {
        String tag = tag();
        create(tag, USER, ACTIVE, null);
        createAdmin(tag, "c"); // an inactive admin is refused, so this one is Active

        assertThat(count("search", tag, "role", "USER")).as("role=USER, the storage spelling").isEqualTo(1);
        assertThat(count("search", tag, "role", "user")).as("role=user, the page's filter spelling").isEqualTo(1);
        assertThat(count("search", tag, "role", "ADMIN")).as("role=ADMIN").isEqualTo(1);
        assertThat(count("search", tag, "status", ACTIVE)).as("status=Active").isEqualTo(2);
        assertThat(count("search", tag, "status", "active")).as("status=active").isEqualTo(2);
        assertThat(count("search", tag, "status", INACTIVE)).as("status=Inactive").isZero();
    }

    /**
     * {@code ALL} is what the page's two dropdowns send for "no filter", so it must mean
     * every row — not a role called "ALL" that matches nothing.
     */
    @Test
    void theAllKeywordMeansNoFilter() throws Exception {
        String tag = tag();
        create(tag, USER, ACTIVE, null);

        mockMvc.perform(get("/api/admin/users")
                        .param("search", tag).param("role", "ALL").param("status", "ALL")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users.length()").value(1));
    }

    /** Passing {@code size} switches to a real page — and adds the counts it needs. */
    @Test
    void pagingAddsTheCountsThePageNeedsToWalkTheRest() throws Exception {
        String tag = tag();
        // Two accounts whose *names* share the tag (the second has to differ in the
        // identifier columns — the unique indexes are real, even in a rollback-only test).
        create(tag, USER, ACTIVE, null);
        create(tag, USER, ACTIVE, null, email(tag + "p2"), phone(tag + "p2"));

        mockMvc.perform(get("/api/admin/users")
                        .param("search", tag).param("page", "0").param("size", "1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.users.length()").value(1))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2));
    }

    /* ------------------------------------------------------------------ *
     *  create                                                             *
     * ------------------------------------------------------------------ */

    /**
     * The panel has no password field, so an account it creates has no credential — and
     * the honest answer to a sign-in attempt is {@code INVALID_CREDENTIALS}, not a crash
     * and not a session.
     */
    @Test
    void anAccountCreatedWithoutAPasswordCannotSignIn() throws Exception {
        String tag = tag();
        String email = email(tag);
        int id = createId(tag, USER, ACTIVE, null);

        mockMvc.perform(get("/api/admin/users/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canSignIn").value(false));

        login(email, "secret123").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));
    }

    /**
     * Provisioning a usable login: the optional password is hashed by the same encoder
     * {@code /api/auth/register} uses, so the account signs in through the real endpoint
     * with no further step.
     */
    @Test
    void anAccountCreatedWithAPasswordCanSignInImmediately() throws Exception {
        String tag = tag();
        String email = email(tag);
        int id = createId(tag, USER, ACTIVE, "secret123");

        login(email, "secret123").andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.userId").value(id))
                .andExpect(jsonPath("$.user.email").value(email));
    }

    /**
     * The address is the same one the first account stored, the mobile number is a new one —
     * so the refusal has to be about the email and nothing else.
     */
    @Test
    void createRefusesAnEmailAnotherAccountAlreadyHas() throws Exception {
        String tag = tag();
        create(tag, USER, ACTIVE, null);

        mockMvc.perform(post("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Roster %s twin","email":"%s","phone":"%s","role":"USER","status":"Active"}
                                """.formatted(tag, email(tag), phone(tag + "f2"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EMAIL_EXISTS"));
    }

    @Test
    void createRefusesAMobileNumberAnotherAccountAlreadyHas() throws Exception {
        String tag = tag();
        String phone = phone(tag + "g");
        create(tag + "g-one", USER, ACTIVE, null, email(tag + "g1"), phone);

        mockMvc.perform(post("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Roster %s","email":"%s","phone":"%s","role":"%s","status":"%s"}
                                """.formatted(tag, email(tag + "g2"), phone, USER, ACTIVE)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PHONE_EXISTS"));
    }

    /** The same cross-field rule registration enforces — one of the two is required. */
    @Test
    void createRefusesAnAccountWithNeitherEmailNorMobile() throws Exception {
        mockMvc.perform(post("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Nobody","email":"","phone":"","role":"USER","status":"Active"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void createRejectsARoleTheSchemaCannotStore() throws Exception {
        String tag = tag();

        mockMvc.perform(post("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Roster %s","email":"%s","role":"MANAGER","status":"Active"}
                                """.formatted(tag, email(tag + "h"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    /**
     * The email is lower-cased and the mobile number reduced to its 10-digit national form,
     * exactly as registration does it — so the same person cannot end up with two accounts
     * because one was typed with a {@code +977} dial prefix and a caps-lock email.
     *
     * <p>The mobile number is sent as {@code "+977 981-234-5607"} on purpose: the signup
     * form's dial prefix and the login page's free-text field both produce forms like it, and
     * {@code UserService.normalizePhone} is what makes them one account rather than two.
     */
    @Test
    void createNormalisesTheEmailAndTheMobileNumber() throws Exception {
        String tag = tag();
        String mixed = "Roster-" + tag.toUpperCase();

        mockMvc.perform(post("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Roster %s","email":"%s@Example.COM","phone":"+977 981-234-5607","role":"USER","status":"Active"}
                                """.formatted(tag, mixed)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(mixed.toLowerCase() + "@example.com"))
                .andExpect(jsonPath("$.phone").value("9812345607"));
    }

    /**
     * An administrator who cannot reach the panel is a contradiction, and every other admin
     * rule ("an admin may never be deactivated") depends on no such row existing.
     */
    @Test
    void createRefusesAnInactiveAdministrator() throws Exception {
        String tag = tag();

        mockMvc.perform(post("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(tag, ADMIN, INACTIVE, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ADMIN_ACCOUNT_PROTECTED"));
    }

    /* ------------------------------------------------------------------ *
     *  edit                                                               *
     * ------------------------------------------------------------------ */

    @Test
    void updateChangesTheNameEmailAndMobile() throws Exception {
        String tag = tag();
        int id = createId(tag, USER, ACTIVE, null);
        String newEmail = email(tag + "i");

        mockMvc.perform(put("/api/admin/users/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Renamed %s","email":"%s","phone":"%s","role":"%s","status":"%s"}
                                """.formatted(tag, newEmail, phone(tag + "i"), USER, ACTIVE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed " + tag))
                .andExpect(jsonPath("$.email").value(newEmail))
                .andExpect(jsonPath("$.phone").value(phone(tag + "i")));
    }

    /** The edit modal posts every field on every save, so the row's own email must be allowed. */
    @Test
    void updateKeepsTheAccountsOwnEmailAndMobile() throws Exception {
        String tag = tag();
        String email = email(tag);
        String phone = phone(tag);
        int id = createId(tag, USER, ACTIVE, null);

        mockMvc.perform(put("/api/admin/users/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Roster %s","email":"%s","phone":"%s","role":"%s","status":"%s"}
                                """.formatted(tag, email, phone, USER, ACTIVE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email))
                .andExpect(jsonPath("$.phone").value(phone));
    }

    @Test
    void updateRefusesAnEmailAnotherAccountAlreadyHas() throws Exception {
        String tag = tag();
        int id = createId(tag, USER, ACTIVE, null);
        String taken = email(tag + "k2");

        mockMvc.perform(post("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Other %s","email":"%s","phone":"%s","role":"USER","status":"Active"}
                                """.formatted(tag, taken, phone(tag + "k2"))))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/admin/users/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Roster %s","email":"%s","phone":"%s","role":"USER","status":"Active"}
                                """.formatted(tag, taken, phone(tag + "k1"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EMAIL_EXISTS"));
    }

    /** Blank means "leave it alone" — a typo in a hand-written body must not wipe a field. */
    @Test
    void updateTreatsABlankFieldAsUnchanged() throws Exception {
        String tag = tag();
        String phone = phone(tag);
        int id = createId(tag, USER, ACTIVE, null);

        mockMvc.perform(put("/api/admin/users/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Roster %s","email":"","phone":"","role":"","status":""}
                                """.formatted(tag)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(email(tag)))
                .andExpect(jsonPath("$.phone").value(phone))
                .andExpect(jsonPath("$.role").value(USER))
                .andExpect(jsonPath("$.status").value(ACTIVE));
    }

    /**
     * Promotion is allowed and the protection follows the role: once a row is an
     * {@code ADMIN}, the toggle refuses it. That is the same rule the panel's own header
     * states for admin rows.
     */
    @Test
    void updateCanPromoteAnAccountToAdminAndTheProtectionFollows() throws Exception {
        String tag = tag();
        int id = createId(tag, USER, ACTIVE, null);

        mockMvc.perform(put("/api/admin/users/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Roster %s","email":"%s","phone":"%s","role":"ADMIN","status":"Active"}
                                """.formatted(tag, email(tag + "m"), phone(tag + "m"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value(ADMIN));

        mockMvc.perform(put("/api/admin/users/" + id + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Inactive\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ADMIN_ACCOUNT_PROTECTED"));
    }

    /**
     * The mock's form still lets an admin demote itself; the API refuses, because no call
     * exists that could undo it and the panel would be left with nobody who can manage it.
     * Echoing the current values back — what the edit modal does — stays a no-op.
     */
    @Test
    void updateRefusesToDemoteAnAdministrator() throws Exception {
        String tag = tag();
        int id = createAdmin(tag, "n");

        mockMvc.perform(put("/api/admin/users/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Roster %s","email":"%s","phone":"%s","role":"USER","status":"Active"}
                                """.formatted(tag, email(tag + "n"), phone(tag + "n"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ADMIN_ACCOUNT_PROTECTED"));

        // ...but the same request with the role it already has is a plain edit.
        mockMvc.perform(put("/api/admin/users/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Renamed %s","email":"%s","phone":"%s","role":"ADMIN","status":"Active"}
                                """.formatted(tag, email(tag + "n"), phone(tag + "n"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Renamed " + tag))
                .andExpect(jsonPath("$.role").value(ADMIN));
    }

    @Test
    void updateCannotTouchAnAccountThatDoesNotExist() throws Exception {
        mockMvc.perform(put("/api/admin/users/99999999")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Ghost","email":"ghost-%s@example.com","role":"USER","status":"Active"}
                                """.formatted(tag())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
    }

    /* ------------------------------------------------------------------ *
     *  activate / deactivate                                              *
     * ------------------------------------------------------------------ */

    /**
     * The phase's headline, end to end: an account that can sign in, then cannot, then can
     * again. Before this phase the middle step did nothing at all, because
     * {@code authenticate} never read {@code status}.
     */
    @Test
    void deactivatingAnAccountStopsItSigningInAndActivatingRestoresIt() throws Exception {
        String tag = tag();
        String email = email(tag);
        int id = createId(tag, USER, ACTIVE, "secret123");

        login(email, "secret123").andExpect(status().isOk());

        mockMvc.perform(put("/api/admin/users/" + id + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Inactive\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(INACTIVE))
                .andExpect(jsonPath("$.canSignIn").value(false));

        login(email, "secret123")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCOUNT_DISABLED"));

        mockMvc.perform(put("/api/admin/users/" + id + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"active\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(ACTIVE))
                .andExpect(jsonPath("$.canSignIn").value(true));

        login(email, "secret123").andExpect(status().isOk());
    }

    /** A double-click on the toggle is harmless — setting the current status is a no-op. */
    @Test
    void theStatusToggleIsIdempotent() throws Exception {
        String tag = tag();
        int id = createId(tag, USER, ACTIVE, null);

        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(put("/api/admin/users/" + id + "/status")
                            .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"status\":\"Inactive\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value(INACTIVE));
        }
    }

    @Test
    void theStatusToggleRefusesAnUnknownValue() throws Exception {
        String tag = tag();
        int id = createId(tag, USER, ACTIVE, null);

        mockMvc.perform(put("/api/admin/users/" + id + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Deleted\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));
    }

    @Test
    void anAdministratorCannotBeDeactivated() throws Exception {
        String tag = tag();
        int id = createAdmin(tag, "p");

        mockMvc.perform(put("/api/admin/users/" + id + "/status")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"Inactive\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ADMIN_ACCOUNT_PROTECTED"));
    }

    /* ------------------------------------------------------------------ *
     *  delete                                                             *
     * ------------------------------------------------------------------ */

    @Test
    void deleteRemovesAnAccountThatOwnsNothing() throws Exception {
        String tag = tag();
        int id = createId(tag, USER, ACTIVE, null);

        mockMvc.perform(delete("/api/admin/users/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/admin/users/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
    }

    /**
     * The guard the booking FK would otherwise answer as a driver error: an account with a
     * booking is deactivated, never erased — which is also what keeps the admin page's own
     * delete dialog ("their past bookings are kept") true.
     */
    @Test
    void deleteRefusesAnAccountThatHasBookings() throws Exception {
        String tag = tag();
        String email = email(tag);
        int id = createId(tag, USER, ACTIVE, "secret123");
        String token = tokenFor(email, "secret123");

        Fixture fixture = fixture(tag);
        String flightNo = createFlight(fixture, 6, "4200.00");
        book(fixture, flightNo, token);

        // The booking really is attributed to the account.
        mockMvc.perform(get("/api/admin/users/" + id + "/bookings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookings").isArray())
                .andExpect(jsonPath("$.bookings.length()").value(1))
                .andExpect(jsonPath("$.bookings[0].customer").value("Mr Hari " + tag));

        mockMvc.perform(delete("/api/admin/users/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("USER_HAS_BOOKINGS"));

        // ...and the account is still there, which is what "deactivate instead" needs.
        mockMvc.perform(get("/api/admin/users/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(ACTIVE));
    }

    @Test
    void deleteRefusesAnAdministrator() throws Exception {
        String tag = tag();
        int id = createAdmin(tag, "r");

        mockMvc.perform(delete("/api/admin/users/" + id)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ADMIN_ACCOUNT_PROTECTED"));
    }

    /** An unknown account answers 404, not "an empty list of bookings". */
    @Test
    void theBookingReadAnswers404ForAnUnknownAccount() throws Exception {
        mockMvc.perform(get("/api/admin/users/99999999/bookings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("USER_NOT_FOUND"));
    }

    /* ------------------------------------------------------------------ *
     *  helpers — requests                                                 *
     * ------------------------------------------------------------------ */

    /** Creates an account through the admin API and answers the record it returned. */
    private JsonNode create(String tag, String role, String status, String password) throws Exception {
        return create(tag, role, status, password, email(tag), phone(tag));
    }

    private JsonNode create(String tag, String role, String status, String password,
                            String email, String phone) throws Exception {
        String body = mockMvc.perform(post("/api/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Roster %s","email":"%s","phone":"%s","role":"%s","status":"%s","password":"%s"}
                                """.formatted(tag, email, phone, role, status,
                                password == null ? "" : password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body);
    }

    /** The created account's id — the roster's own key, and the path for every later call. */
    private int createId(String tag, String role, String status, String password) throws Exception {
        return create(tag, role, status, password).get("id").asInt();
    }

    /** An {@code ADMIN} row, whose email/mobile carry the suffix so it cannot clash. */
    private int createAdmin(String tag, String suffix) throws Exception {
        return create(tag, ADMIN, ACTIVE, null, email(tag + suffix), phone(tag + suffix)).get("id").asInt();
    }

    /** Every user whose name/email/mobile/id matches one filter value. */
    /**
     * How many rows the list answers with, for any combination of filters: name/value
     * pairs, so {@code count("role", "USER")} and
     * {@code count("search", tag, "role", "USER")} both read naturally. The scoped form
     * is the one to reach for — an unscoped count is a claim about the whole table.
     */
    private int count(String... filter) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/admin/users");
        for (int i = 0; i < filter.length; i += 2) {
            request = request.param(filter[i], filter[i + 1]);
        }

        String body = mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).get("users").size();
    }

    private org.springframework.test.web.servlet.ResultActions login(String loginId, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"loginId":"%s","password":"%s"}
                        """.formatted(loginId, password)));
    }

    private String tokenFor(String loginId, String password) throws Exception {
        String body = login(loginId, password)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body).get("token").asText();
    }

    private static String body(String tag, String role, String status, String password) {
        return """
                {"name":"Roster %s","email":"%s","phone":"%s","role":"%s","status":"%s","password":"%s"}
                """.formatted(tag, email(tag), phone(tag), role, status, password == null ? "" : password);
    }

    /* ------------------------------------------------------------------ *
     *  helpers — a flight to book                                         *
     * ------------------------------------------------------------------ */

    private record Fixture(String tag, String flightNo, Airline airline, Destination from, Destination to) {
    }

    private Fixture fixture(String tag) {
        Airline airline = new Airline();
        airline.setName(tag + " Air");
        airline.setIata(uniqueIata());
        airline.setStatus("Active");

        return new Fixture(tag, uniqueFlightNo(), airlines.save(airline),
                seedDestination(tag), seedDestination(tag));
    }

    /** Creates a flight through the real admin endpoint (which also builds its seat map). */
    private String createFlight(Fixture fixture, int capacity, String fare) throws Exception {
        mockMvc.perform(post("/api/admin/flights")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"no":"%s","airlineId":%d,"from":"%s","to":"%s","dep":"06:50","arr":"07:35",
                                 "aircraft":"ATR 72","fare":%s,"seats":%d,"status":"Active"}
                                """.formatted(fixture.flightNo(), fixture.airline().getId(),
                                fixture.from().getCode(), fixture.to().getCode(), fare, capacity)))
                .andExpect(status().isOk());

        return fixture.flightNo();
    }

    /**
     * Books one seat with the account's own token, which is what makes the booking belong
     * to that user — {@code BookingController} takes the owner from the JWT's {@code userId}
     * claim, so this is also the assertion that the admin-created account is a real,
     * usable identity.
     */
    private void book(Fixture fixture, String flightNo, String token) throws Exception {
        mockMvc.perform(post("/api/bookings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contact":{"title":"Mr","firstName":"Hari","lastName":"%s",
                                 "email":"booker-%s@example.com","phone":"%s","invoiceParty":"Self","panNo":"","isPassenger":true},
                                 "passengers":[{"title":"Mr","firstName":"Hari","lastName":"%s","nationality":"Nepali","type":"ADT","seatNumber":"1A"}],
                                 "flight":{"from":"%s","to":"%s","date":"%s","depart":"06:50","arrive":"07:35","flightNo":"%s",
                                 "airline":"Air","flightClass":"E Class","refundable":true,"pricePerPassenger":4200.00,
                                 "passengerCount":1,"totalPrice":4200.00},
                                 "amount":4200.00}
                                """.formatted(fixture.tag(), fixture.tag().toLowerCase(), phone(fixture.tag() + "bk"),
                                fixture.tag(), fixture.from().getCode(), fixture.to().getCode(),
                                LocalDate.now(), flightNo)))
                .andExpect(status().isOk());
    }

    private Destination seedDestination(String tag) {
        Destination destination = new Destination();
        destination.setCity("City " + tag);
        destination.setCode(uniqueAirportCode());
        destination.setAirport(tag + " Airport");
        destination.setStatus("Active");
        return destinations.save(destination);
    }

    private String uniqueFlightNo() {
        for (int attempt = 0; attempt < 50; attempt++) {
            String candidate = "T9 " + String.format("%04d",
                    Math.abs(UUID.randomUUID().hashCode()) % 10_000);
            if (flights.findByFlightNo(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not find an unused flight number");
    }

    private String uniqueIata() {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int attempt = 0; attempt < 500; attempt++) {
            java.util.Random random = new java.util.Random();
            String candidate = "" + alphabet.charAt(random.nextInt(alphabet.length()))
                    + alphabet.charAt(random.nextInt(alphabet.length()));
            if (airlines.findByIata(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not find an unused IATA code");
    }

    private String uniqueAirportCode() {
        for (int attempt = 0; attempt < 50; attempt++) {
            String candidate = UUID.randomUUID().toString().replaceAll("[^a-z]", "")
                    .substring(0, 3).toUpperCase();
            if (destinations.findByCode(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not find an unused airport code");
    }

    /* ------------------------------------------------------------------ *
     *  helpers — values                                                   *
     * ------------------------------------------------------------------ */

    /** Isolates each test's rows from other tests and from the seeded demo data. */
    private static String tag() {
        return "U" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String email(String seed) {
        return "admin-" + seed.toLowerCase() + "@example.com";
    }

    /** 10 digits, unique per run, in the roster's stored form. */
    private static String phone(String seed) {
        return "97" + String.format("%08d", Math.abs(seed.hashCode()) % 100_000_000);
    }

    private String adminToken() {
        return jwtUtil.generate(1, "Dikshya Ghising", "admin@yatra.com", ADMIN);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
