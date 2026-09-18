package io.virinchi.yatra;

import io.virinchi.yatra.Model.Airline;
import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Model.User;
import io.virinchi.yatra.Repository.AirlineRepository;
import io.virinchi.yatra.Repository.DestinationRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import io.virinchi.yatra.Repository.UserRepository;
import io.virinchi.yatra.Security.JwtUtil;
import jakarta.persistence.EntityManager;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET}/{@code POST}/{@code PUT /api/users/me} and
 * {@code GET /api/users/me/bookings} — the customer's own account, Fix-plan §7's
 * pre-fill source and §11's page backend.
 *
 * <p>DB-backed, {@code @Transactional} and rolled back, like the rest of the suite: the
 * live TiDB schema is never modified. Accounts are created <b>here</b> rather than taken
 * from the seed, because this controller resolves its subject from the JWT's {@code sub}
 * claim — the test needs tokens whose ids it knows to be real rows.
 *
 * <h2>What this class is really asserting</h2>
 * <ul>
 *   <li><b>The pages' contract.</b> {@code { user }} with the session spelling
 *       {@code userId} (what {@code profile.js} and {@code auth.js} read), and
 *       {@code { bookings }} with no null paging keys — the shapes {@code api.js}'s mock
 *       answers for the same paths, so mock and real mode stay interchangeable.</li>
 *   <li><b>The editable-field boundary is structural.</b> {@code role}, {@code status}
 *       and {@code id} are not fields of {@code ProfileUpdateRequest}, so sending them
 *       must be a no-op rather than a promotion. This is the security claim here.</li>
 *   <li><b>Authorization is the default rule, not a new one.</b> There is no role
 *       requirement on this route — every authenticated account reaches it, and an
 *       anonymous caller is refused by {@code anyRequest().authenticated()}. A
 *       {@code USER} token is therefore <i>supposed</i> to work, which is the opposite
 *       of the sibling admin route's assertion and the reason it is tested.</li>
 *   <li><b>The booking history is scoped to the caller.</b> The mock could not do this
 *       — its own comment says the shared store has <i>"no per-user scoping until JWT
 *       auth lands"</i>. Two accounts each book a seat and neither may see the other's
 *       row. That is the difference between a customer page and a data leak, and it is
 *       the one thing here that a passing render would not have caught.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class UserProfileApiTest {

    private static final String ADMIN = "ADMIN";
    private static final String USER = "USER";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtUtil jwtUtil;
    @Autowired private UserRepository users;
    @Autowired private AirlineRepository airlines;
    @Autowired private DestinationRepository destinations;
    @Autowired private FlightRepository flights;
    @Autowired private EntityManager entityManager;

    /* ================================================================== *
     *  the pages' contract                                               *
     * ================================================================== */

    /** The body is exactly {@code { user }} — the one key the page reads. */
    @Test
    void theBodyIsTheSignedInUsersOwnAccount() throws Exception {
        User me = customer();

        JsonNode body = profile(tokenFor(me));

        assertThat(body.size())
                .as("res.user is the whole contract — anything else is extra surface")
                .isEqualTo(1);
        assertThat(body.get("user").isObject()).as("res.user").isTrue();

        JsonNode user = body.get("user");
        assertThat(user.get("userId").asInt())
                .as("the session shape's key is userId — auth.js, profile.js and the navbar read it")
                .isEqualTo(me.getId());
        assertThat(user.get("name").asText()).isEqualTo(me.getName());
        assertThat(user.get("email").asText()).isEqualTo(me.getEmail());
        assertThat(user.get("phone").asText()).isEqualTo(me.getPhone());
        assertThat(user.get("role").asText()).isEqualTo(USER);
        assertThat(user.get("status").asText()).isEqualTo("Active");
        assertThat(user.has("registeredAt")).as("registeredAt is part of the session shape").isTrue();

        assertThat(user.has("password"))
                .as("UserResponse is the boundary that keeps the BCrypt hash off the wire")
                .isFalse();
    }

    /* ================================================================== *
     *  authorization — the default rule, and no role requirement          *
     * ================================================================== */

    /**
     * The deliberate contrast with {@code /api/admin/profile}: this route has <b>no</b>
     * role requirement, so a plain {@code USER} token must succeed. It is closed to
     * anonymous callers by {@code anyRequest().authenticated()} alone.
     */
    @Test
    void anyAuthenticatedAccountReachesItAndAnonymousDoesNot() throws Exception {
        User me = customer();

        mockMvc.perform(get("/api/users/me"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(me))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.userId").value(me.getId()));

        // An administrator is also an authenticated account, and this is their own row too.
        User admin = administrator();
        mockMvc.perform(get("/api/users/me").header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(admin))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.userId").value(admin.getId()));
    }

    /** The bookings read is behind the same rule — no token, no history. */
    @Test
    void theBookingHistoryIsNotReachableAnonymously() throws Exception {
        mockMvc.perform(get("/api/users/me/bookings"))
                .andExpect(status().isUnauthorized());
    }

    /* ================================================================== *
     *  the write — both verbs, one handler                                *
     * ================================================================== */

    /** The three fields a profile form owns are the three fields that change. */
    @Test
    void itEditsNameEmailAndPhone() throws Exception {
        User me = customer();
        String tag = tag();

        String newName = "Renamed Customer " + tag;
        String newEmail = "renamed-" + tag.toLowerCase() + "@yatra.com";
        String newPhone = mobile();

        JsonNode user = save(tokenFor(me), newName, newEmail, newPhone).get("user");

        assertThat(user.get("name").asText()).isEqualTo(newName);
        assertThat(user.get("email").asText()).isEqualTo(newEmail);
        assertThat(user.get("phone").asText()).isEqualTo(newPhone);
        assertThat(user.get("userId").asInt()).as("a profile edit never changes the id").isEqualTo(me.getId());

        User stored = reload(me.getId());
        assertThat(stored.getName()).as("a real write, not an echo").isEqualTo(newName);
        assertThat(stored.getEmail()).isEqualTo(newEmail);
        assertThat(stored.getPhone()).isEqualTo(newPhone);
    }

    /**
     * §11 asks for {@code PUT} and {@code profile.js} calls {@code POST}. Both must do
     * the same thing, because they are one handler — asserted rather than assumed, since
     * two verbs drifting apart is exactly what this design avoids.
     */
    @Test
    void putAndPostAreTheSameWrite() throws Exception {
        User viaPost = customer();
        User viaPut = customer();

        String tag = tag();
        String name = "Same Write " + tag;

        JsonNode posted = save(tokenFor(viaPost), name, viaPost.getEmail(), viaPost.getPhone()).get("user");

        String body = mockMvc.perform(put("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(viaPut)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload(name, viaPut.getEmail(), viaPut.getPhone())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").exists())
                .andReturn().getResponse().getContentAsString();

        JsonNode put = objectMapper.readTree(body).get("user");

        assertThat(posted.get("name").asText()).isEqualTo(name);
        assertThat(put.get("name").asText()).as("PUT answers the same envelope").isEqualTo(name);
        assertThat(put.get("userId").asInt()).isEqualTo(viaPut.getId());

        assertThat(reload(viaPut.getId()).getName()).as("and PUT really wrote").isEqualTo(name);
    }

    /**
     * The boundary this endpoint exists to hold: {@code role}, {@code status} and
     * {@code id} are not part of the request type, so sending them cannot promote the
     * caller, or move the write to another row.
     */
    @Test
    void roleStatusAndIdCannotBeChangedFromThisEndpoint() throws Exception {
        User me = customer();

        JsonNode user = save(tokenFor(me),
                "Still A Customer " + tag(),
                me.getEmail(),
                me.getPhone(),
                """
                ,"role":"ADMIN","status":"Inactive","id":999999,"seeded":true
                """)
                .get("user");

        assertThat(user.get("role").asText())
                .as("@JsonIgnoreProperties(ignoreUnknown = true) — an unknown key is ignored, never bound")
                .isEqualTo(USER);
        assertThat(user.get("status").asText())
                .as("a customer cannot deactivate themselves through their own profile")
                .isEqualTo("Active");
        assertThat(user.get("userId").asInt())
                .as("the id came from the JWT, not the body")
                .isEqualTo(me.getId());

        User stored = reload(me.getId());
        assertThat(stored.getRole()).isEqualTo(USER);
        assertThat(stored.getStatus()).isEqualTo("Active");
        assertThat(stored.isSeeded())
                .as("`seeded` is not writable from any endpoint — a hand-set marker would hide the row from reset")
                .isFalse();
    }

    /** Blank means "leave it alone" — never "clear it", and never a 409 against itself. */
    @Test
    void blankFieldsKeepTheirStoredValue() throws Exception {
        User me = customer();

        JsonNode user = save(tokenFor(me), "Renamed Only " + tag(), "", "").get("user");

        assertThat(user.get("name").asText()).startsWith("Renamed Only");
        assertThat(user.get("email").asText())
                .as("a blank email must not wipe the address this account signs in with")
                .isEqualTo(me.getEmail());
        assertThat(user.get("phone").asText()).isEqualTo(me.getPhone());
    }

    /** Another account's email is a 409 {@code EMAIL_EXISTS}, the code the page paints. */
    @Test
    void anEmailAnotherAccountOwnsIsRefused() throws Exception {
        User me = customer();
        User other = customer();

        mockMvc.perform(post("/api/users/me")
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
        User me = customer();
        User other = customer();

        mockMvc.perform(post("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(tokenFor(me)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload("Colliding " + tag(), me.getEmail(), other.getPhone())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("PHONE_EXISTS"));

        assertThat(reload(me.getId()).getPhone()).isEqualTo(me.getPhone());
    }

    /** Resubmitting the values the account already has is a success, not a collision. */
    @Test
    void savingItsOwnUnchangedValuesIsNotACollision() throws Exception {
        User me = customer();

        JsonNode user = save(tokenFor(me), me.getName(), me.getEmail(), me.getPhone()).get("user");

        assertThat(user.get("email").asText()).isEqualTo(me.getEmail());
        assertThat(user.get("phone").asText()).isEqualTo(me.getPhone());
    }

    /* ================================================================== *
     *  the booking history — the scoping is the point                    *
     * ================================================================== */

    /**
     * <b>Two accounts, two bookings, and neither may see the other's.</b> The mock reads
     * one shared store for every caller, so this is the property that only exists once
     * the endpoint is real.
     */
    @Test
    void theBookingHistoryHoldsOnlyTheCallersOwnBookings() throws Exception {
        User mine = customer();
        User theirs = customer();

        Fixture fixture = fixture(tag());
        createFlight(fixture, 6, "4200.00");

        String theirContact = "theirs-" + fixture.tag().toLowerCase() + "@example.com";
        book(fixture, tokenFor(mine), "1A", theirContact.replace("theirs", "mine"));
        book(fixture, tokenFor(theirs), "1B", theirContact);

        // Two bookings really exist on that flight — asserted through the admin read, so
        // a scoping test cannot pass merely because the second booking failed to save.
        JsonNode all = adminBookings(fixture.flightNo());
        assertThat(all.get("bookings").size())
                .as("both bookings exist; the customer endpoint is what filters")
                .isEqualTo(2);

        JsonNode myRows = bookings(tokenFor(mine)).get("bookings");
        JsonNode theirRows = bookings(tokenFor(theirs)).get("bookings");

        assertThat(myRows.size()).as("exactly my one booking").isEqualTo(1);
        assertThat(theirRows.size()).as("exactly theirs").isEqualTo(1);

        // The contact block distinguishes the rows — it is the wizard's data, not the
        // account's, which is why each caller was given its own above.
        assertThat(myRows.get(0).get("email").asText())
                .as("the row I see is mine, not whoever booked last")
                .isEqualTo(theirContact.replace("theirs", "mine"));
        assertThat(theirRows.get(0).get("email").asText()).isEqualTo(theirContact);

        // And the ids really are different rows rather than one row read twice.
        assertThat(myRows.get(0).get("id").asText())
                .isNotEqualTo(theirRows.get(0).get("id").asText());
    }

    /**
     * The other half of scoping: an account with no bookings gets an empty list, not
     * somebody else's. The seeded demo bookings belong to the seeded accounts, so a
     * freshly created fixture must not inherit them.
     */
    @Test
    void anAccountWithNoBookingsGetsAnEmptyList() throws Exception {
        User me = customer();

        JsonNode body = bookings(tokenFor(me));

        assertThat(body.get("bookings").isArray()).isTrue();
        assertThat(body.get("bookings").size()).as("nothing of my own to show").isEqualTo(0);

        // The unpaged shape the mock answers: no null paging keys for the pages to trip on.
        assertThat(body.has("page")).as("@JsonInclude(NON_NULL) omits the paging keys").isFalse();
        assertThat(body.has("totalElements")).isFalse();
    }

    /**
     * The row is the vocabulary the pages render — {@code myBookings.js} buckets on the
     * <b>display</b> spelling of {@code status} and sorts on {@code createdAt}, and
     * {@code profile.js} reads {@code flight.date} for its "Upcoming" count. A
     * {@code CONFIRMED} spelling or a missing {@code flight.date} would leave those pages
     * quietly wrong rather than erroring.
     */
    @Test
    void theBookingRowIsTheShapeThePagesRender() throws Exception {
        User me = customer();

        Fixture fixture = fixture(tag());
        createFlight(fixture, 6, "4200.00");
        String contact = "shape-" + fixture.tag().toLowerCase() + "@example.com";
        book(fixture, tokenFor(me), "1A", contact);

        JsonNode row = bookings(tokenFor(me)).get("bookings").get(0);

        assertThat(row.get("status").asText())
                .as("the display vocabulary myBookings.js buckets on")
                .isEqualTo("Pending");
        assertThat(row.get("id").isTextual()).as("the numeric key rendered as text").isTrue();
        assertThat(row.get("flight").get("flightNo").asText()).isEqualTo(fixture.flightNo());
        assertThat(row.get("flight").get("date").asText())
                .as("profile.js's Upcoming filter compares this to today")
                .isEqualTo(LocalDate.now().toString());
        assertThat(row.get("amount").isNumber()).as("myBookings.js sums this for 'Total spent'").isTrue();
        assertThat(row.has("createdAt")).as("myBookings.js sorts on this").isTrue();
        assertThat(row.get("email").asText())
                .as("the contact block the wizard collected — not the account's email")
                .isEqualTo(contact);
    }

    /* ================================================================== *
     *  helpers — the calls                                               *
     * ================================================================== */

    private JsonNode profile(String token) throws Exception {
        String body = mockMvc.perform(get("/api/users/me")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").exists())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body);
    }

    private JsonNode bookings(String token) throws Exception {
        String body = mockMvc.perform(get("/api/users/me/bookings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bookings").isArray())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body);
    }

    private JsonNode save(String token, String name, String email, String phone) throws Exception {
        return save(token, name, email, phone, "");
    }

    /** A successful save whose body carries {@code extra} verbatim before the brace. */
    private JsonNode save(String token, String name, String email, String phone, String extra)
            throws Exception {
        String body = mockMvc.perform(post("/api/users/me")
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

    /* ================================================================== *
     *  helpers — fixtures                                                *
     * ================================================================== */

    /** A {@code USER} row the token can point at. */
    private User customer() {
        return account(USER);
    }

    /** An {@code ADMIN} row — proving the route is not role-gated in the other direction. */
    private User administrator() {
        return account(ADMIN);
    }

    private User account(String role) {
        String tag = tag();

        User user = new User();
        user.setName("Test " + role + " " + tag);
        user.setEmail("test-" + role.toLowerCase() + "-" + tag.toLowerCase() + "@yatra.com");
        user.setPhone(mobile());
        user.setPassword("$2a$10$not-a-real-hash-this-suite-never-signs-in");
        user.setRole(role);
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
        return jwtUtil.generate(user.getId(), user.getName(), user.getEmail(), user.getRole());
    }

    /** A token that only needs to satisfy {@code hasRole('ADMIN')} for the flight fixture. */
    private String adminToken() {
        return jwtUtil.generate(1, "Dikshya Ghising", "admin@gmail.com", ADMIN);
    }

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

    /**
     * Creates a flight through the real admin endpoint (which also builds its seat map).
     *
     * <p>{@code date} is set to today on purpose: the booking row the pages render carries
     * the <b>flight entity's</b> date, not the date in the booking request, and
     * {@code FlightRequest.date} is optional — so leaving it out yields a null date and a
     * row that {@code profile.js}'s "Upcoming" filter cannot compare.
     */
    private void createFlight(Fixture fixture, int capacity, String fare) throws Exception {
        mockMvc.perform(post("/api/admin/flights")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"no":"%s","airlineId":%d,"from":"%s","to":"%s","date":"%s",
                                 "dep":"06:50","arr":"07:35","aircraft":"ATR 72","fare":%s,"seats":%d,"status":"Active"}
                                """.formatted(fixture.flightNo(), fixture.airline().getId(),
                                fixture.from().getCode(), fixture.to().getCode(), LocalDate.now(),
                                fare, capacity)))
                .andExpect(status().isOk());
    }

    /** The admin's own read of one flight's bookings — used to prove a scoping filter is filtering. */
    private JsonNode adminBookings(String flightNo) throws Exception {
        String body = mockMvc.perform(get("/api/admin/bookings").param("search", flightNo)
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(body);
    }

    /**
     * Books one seat with the account's own token — which is what makes the booking belong
     * to that user, since {@code BookingController} takes the owner from the JWT's
     * {@code sub} claim.
     *
     * <p>A second booking on the same flight needs its own seat (a clash is a 409
     * {@code SEAT_ALREADY_BOOKED}), and each caller needs its own contact block for the
     * row to be attributable to them.
     */
    private void book(Fixture fixture, String token, String seatNumber, String contactEmail) throws Exception {
        mockMvc.perform(post("/api/bookings")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contact":{"title":"Mr","firstName":"Hari","lastName":"%s",
                                 "email":"%s","phone":"%s","invoiceParty":"Self","panNo":"","isPassenger":true},
                                 "passengers":[{"title":"Mr","firstName":"Hari","lastName":"%s","nationality":"Nepali","type":"ADT","seatNumber":"%s"}],
                                 "flight":{"from":"%s","to":"%s","date":"%s","depart":"06:50","arrive":"07:35","flightNo":"%s",
                                 "airline":"Air","flightClass":"E Class","refundable":true,"pricePerPassenger":4200.00,
                                 "passengerCount":1,"totalPrice":4200.00},
                                 "amount":4200.00}
                                """.formatted(fixture.tag(), contactEmail, mobile(),
                                fixture.tag(), seatNumber, fixture.from().getCode(), fixture.to().getCode(),
                                LocalDate.now(), fixture.flightNo())))
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

    private static String bearer(String token) {
        return "Bearer " + token;
    }

    /** A 10-digit Nepali mobile number, unique enough for a rolled-back fixture. */
    private static String mobile() {
        return "98" + String.format("%08d",
                Math.abs(UUID.randomUUID().hashCode()) % 100_000_000);
    }

    private static String tag() {
        return "M" + UUID.randomUUID().toString().substring(0, 8);
    }
}
