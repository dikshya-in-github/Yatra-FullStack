package io.virinchi.yatra;

import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Repository.DestinationRepository;
import io.virinchi.yatra.Security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The destination module — Roadmap Phase 8.
 *
 * <p>DB-backed and {@code @Transactional}, so every row is rolled back and the
 * live schema is never modified. Rows carry a per-run tag and are always read back
 * through {@code ?search=}, so the assertions hold even though Phase 7 seeds
 * eleven real airports into the same table.
 *
 * <p>Two tests exist specifically because of what Phase 8 is <i>for</i>:
 * <ul>
 *   <li>{@link #theImageIsAUrlAndNothingElseIsStored()} — the contrast with the
 *       airline BLOB. There is no bytes endpoint here and the URL round-trips
 *       verbatim; proving that is the demonstrable half of rule 3.</li>
 *   <li>{@link #theUploadEndpointIsAdminOnly()} — the one endpoint that talks to
 *       Cloudinary still refuses an anonymous and a non-admin caller, so the
 *       protection does not depend on the image service being configured.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DestinationApiTest {

    /** A real 1×1 PNG — used only to prove the upload endpoint runs its guards first. */
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==");

    private static final String CLOUDINARY_URL =
            "https://res.cloudinary.com/demo/image/upload/v1700000000/yatra/destinations/pokhara.jpg";

    @Autowired private MockMvc mockMvc;
    @Autowired private DestinationRepository destinations;
    @Autowired private JwtUtil jwtUtil;

    /* ------------------------------------------------------------------ *
     *  public reads                                                       *
     * ------------------------------------------------------------------ */

    /**
     * With no token at all — the storefront case. {@code destinations.html} draws a
     * card per airport and {@code homeLogged} builds its arrival dropdown from this
     * list, both for signed-out visitors.
     */
    @Test
    void destinationReadsArePublicSoTheStorefrontPagesCannot401() throws Exception {
        Destination destination = seed();

        mockMvc.perform(get("/api/destinations").param("search", destination.getCity()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/destinations/" + destination.getId()))
                .andExpect(status().isOk());
    }

    /** The wrapper the mock returns and both admin scripts read. */
    @Test
    void theListIsWrappedInADestinationsKeyAndUnpagedByDefault() throws Exception {
        Destination destination = seed();

        mockMvc.perform(get("/api/destinations").param("search", destination.getCity()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destinations").isArray())
                .andExpect(jsonPath("$.destinations[0].city").value(destination.getCity()))
                .andExpect(jsonPath("$.destinations[0].code").value(destination.getCode()))
                .andExpect(jsonPath("$.destinations[0].airport").value(destination.getAirport()))
                .andExpect(jsonPath("$.destinations[0].status").value("Active"))
                // Paging keys must be absent, not null: the unpaged body has to stay
                // byte-compatible with the mock's shape.
                .andExpect(jsonPath("$.page").doesNotExist())
                .andExpect(jsonPath("$.size").doesNotExist())
                .andExpect(jsonPath("$.totalElements").doesNotExist());
    }

    /**
     * The contrast Phase 8 exists to show: a destination image is a URL the browser
     * fetches from a CDN, and the database holds only that string.
     *
     * <p>Compare {@code AirlineApiTest.theListNeverCarriesTheBase64Blob}, which
     * asserts the opposite journey — the airline's response carries a path to an
     * endpoint that decodes bytes out of a column, because those bytes <i>are</i>
     * in the database. Here there is nothing to decode: the value is returned
     * verbatim, and no bytes are ever stored or served.
     */
    @Test
    void theImageIsAUrlAndNothingElseIsStored() throws Exception {
        Destination destination = seed(CLOUDINARY_URL);

        String body = mockMvc.perform(get("/api/destinations").param("search", destination.getCity()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destinations[0].imageUrl").value(CLOUDINARY_URL))
                // The storage handle is server-side only: nothing in the UI reads it,
                // and an opaque handle in a body is the field that gets echoed back.
                .andExpect(jsonPath("$.destinations[0].imagePublicId").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .as("no image bytes and no data URL — the file was never in the database")
                .doesNotContain("data:image")
                .doesNotContain("iVBORw0KGgo");

        assertThat(destinations.findById(destination.getId()).orElseThrow().getImageUrl())
                .as("the column holds the URL string itself")
                .isEqualTo(CLOUDINARY_URL);
    }

    /** A destination with no image reports the mock's empty string, not null. */
    @Test
    void aDestinationWithoutAnImageReportsAnEmptyString() throws Exception {
        Destination destination = seed();

        mockMvc.perform(get("/api/destinations").param("search", destination.getCity()))
                .andExpect(jsonPath("$.destinations[0].imageUrl").value(""));
    }

    @Test
    void oneUnknownDestinationIsA404WithTheProjectsErrorShape() throws Exception {
        mockMvc.perform(get("/api/destinations/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("DESTINATION_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("No Destination exists with id 999999."));
    }

    /* ------------------------------------------------------------------ *
     *  ordering, paging and the search box                                *
     * ------------------------------------------------------------------ */

    /**
     * The order is explicit, {@code sort} is honoured, and an unlisted property
     * falls back to {@code id} rather than reaching the query — R6's rule. Two
     * destinations sharing a status must not come back in whatever order the
     * engine felt like, or a page can show one twice and the next none.
     */
    @Test
    void theListOrderIsExplicitAndTheSortParameterIsHonoured() throws Exception {
        String tag = tag();
        Destination zeta = seedNamed(tag + " Zeta");
        Destination alpha = seedNamed(tag + " Alpha");

        // Default: id ascending, i.e. insertion order — Zeta was created first.
        mockMvc.perform(get("/api/destinations").param("search", tag))
                .andExpect(jsonPath("$.destinations[0].id").value(zeta.getId()))
                .andExpect(jsonPath("$.destinations[1].id").value(alpha.getId()));

        mockMvc.perform(get("/api/destinations").param("search", tag).param("sort", "city"))
                .andExpect(jsonPath("$.destinations[0].id").value(alpha.getId()));

        mockMvc.perform(get("/api/destinations").param("search", tag).param("sort", "imageUrl"))
                .andExpect(jsonPath("$.destinations[0].id").value(zeta.getId()));
    }

    /** Two pages of one row each must not overlap — the failure R6 describes. */
    @Test
    void pagingReturnsDisjointPagesWithCounts() throws Exception {
        String tag = tag();
        seedNamed(tag + " One");
        seedNamed(tag + " Two");

        MvcResult first = mockMvc.perform(get("/api/destinations")
                        .param("search", tag).param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destinations.length()").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andReturn();

        MvcResult second = mockMvc.perform(get("/api/destinations")
                        .param("search", tag).param("page", "1").param("size", "1"))
                .andExpect(jsonPath("$.destinations.length()").value(1))
                .andReturn();

        assertThat(idOf(second)).as("the second page holds the other row")
                .isNotEqualTo(idOf(first));
    }

    /**
     * One search box over three columns — the same reach the page's own filter has
     * ("Search city, airport or code…"). The airport <i>name</i> is included
     * because a marker typing "Tribhuvan" would otherwise get nothing.
     *
     * <p><b>"Tribhuvan" is asserted as membership, not as a count, and it is the one
     * term here that has to be.</b> The row is tagged, so the tag, the city and the code
     * belong to it alone — but Phase 7 seeds a real "Tribhuvan International Airport",
     * so that one term matches two rows the moment the demo dataset is loaded. Counting
     * it would have been a statement about whether the database happens to be seeded
     * rather than about whether the name column is searched; it passed only while the
     * database was empty. The collection's tickets folder was fixed for exactly this in
     * Session 45 — assert the run's own row is <i>among</i> the matches.
     */
    @Test
    void theSearchBoxMatchesCityAirportNameAndCode() throws Exception {
        String tag = tag();
        Destination destination = destinations.save(
                row(tag + " Searchable", tag + " Tribhuvan International Airport", null));

        for (String term : new String[]{tag, "searchable", destination.getCode().toLowerCase()}) {
            mockMvc.perform(get("/api/destinations").param("search", term))
                    .andExpect(jsonPath("$.destinations.length()").value(1))
                    .andExpect(jsonPath("$.destinations[0].id").value(destination.getId()));
        }

        mockMvc.perform(get("/api/destinations").param("search", "tribhuvan"))
                .andExpect(jsonPath("$.destinations[?(@.id == " + destination.getId() + ")].code")
                        .value(contains(destination.getCode())));
    }

    @Test
    void searchAndStatusFilterIndependently() throws Exception {
        String tag = tag();
        Destination active = seedNamed(tag + " Active One");
        Destination retired = seedNamed(tag + " Retired One");
        retired.setStatus("Inactive");
        destinations.save(retired);

        mockMvc.perform(get("/api/destinations").param("search", tag))
                .andExpect(jsonPath("$.destinations.length()").value(2));

        mockMvc.perform(get("/api/destinations").param("search", tag).param("status", "Inactive"))
                .andExpect(jsonPath("$.destinations.length()").value(1))
                .andExpect(jsonPath("$.destinations[0].id").value(retired.getId()));

        assertThat(active.getStatus()).isEqualTo("Active");
    }

    /* ------------------------------------------------------------------ *
     *  authorization                                                      *
     * ------------------------------------------------------------------ */

    /**
     * The admin read and every write needs ADMIN. Enforced by the route rule and
     * {@code @PreAuthorize} — not by the page hiding a sidebar link.
     */
    @Test
    void writesAndTheAdminReadAreForbiddenWithoutAnAdminToken() throws Exception {
        String body = """
                {"city":"%s","code":"%s","airport":"Guarded Airport","status":"Active"}
                """.formatted(tag() + " Guarded", code());

        mockMvc.perform(get("/api/admin/destinations"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("NOT_AUTHENTICATED"));

        mockMvc.perform(post("/api/admin/destinations")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        String userToken = jwtUtil.generate(2, "A Customer", "customer@example.com", "USER");
        mockMvc.perform(post("/api/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));

        mockMvc.perform(get("/api/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    /** The admin table's own read path, behind ADMIN — the same body as the public one. */
    @Test
    void theAdminReadServesTheSameShapeThePageExpects() throws Exception {
        Destination destination = seed();

        mockMvc.perform(get("/api/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .param("search", destination.getCity()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.destinations").isArray())
                .andExpect(jsonPath("$.destinations[0].city").value(destination.getCity()));
    }

    /**
     * The upload endpoint is the one that talks to Cloudinary, and it is guarded
     * like every other write.
     *
     * <p>No ADMIN token means the request never reaches the service, so this holds
     * whether or not the machine has credentials — which is precisely why it can
     * live in the suite while a real upload cannot (see {@code CloudinaryServiceTest}).
     */
    @Test
    void theUploadEndpointIsAdminOnly() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "pokhara.png", "image/png", PNG);

        mockMvc.perform(multipart("/api/admin/destinations/image").file(file))
                .andExpect(status().isUnauthorized());

        String userToken = jwtUtil.generate(2, "A Customer", "customer@example.com", "USER");
        mockMvc.perform(multipart("/api/admin/destinations/image").file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    /* ------------------------------------------------------------------ *
     *  admin writes                                                       *
     * ------------------------------------------------------------------ */

    /** The create path, including the code normalisation the search depends on. */
    @Test
    void creatingADestinationStoresItAndUppercasesTheCode() throws Exception {
        String tag = tag();
        String airportCode = code();

        MvcResult result = mockMvc.perform(post("/api/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"city":"%s City","code":"%s","airport":"%s Airport",
                                 "description":"Test fixture","status":"Active","imageUrl":"%s"}
                                """.formatted(tag, airportCode.toLowerCase(), tag, CLOUDINARY_URL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(airportCode))
                .andExpect(jsonPath("$.city").value(tag + " City"))
                .andExpect(jsonPath("$.imageUrl").value(CLOUDINARY_URL))
                .andReturn();

        int id = idOf(result);

        // Readable back through the public list, which is how the storefront gets it.
        mockMvc.perform(get("/api/destinations").param("search", tag))
                .andExpect(jsonPath("$.destinations[0].id").value(id))
                .andExpect(jsonPath("$.destinations[0].code").value(airportCode));

        /* ...and searchable by the lower-case code, because the column is utf8mb4_bin.
           Assert the row is FOUND, not that it is the ONLY match. The search box spans
           city, airport and code (DestinationRepository.searchAll), so a random 3-letter
           code is a legitimate substring of a seeded name — "AIR" matches every
           "... Airport", "ITY" matches "City". Demanding a length of 1 made this test
           depend on the draw never colliding: a ~1% flake that fired on 2026-09-18 and
           looked like a service bug. Exclusivity is a different test's job
           (aDuplicateAirportCodeIsRefusedEvenInAnotherCase); code uniqueness is the
           unique key's. */
        mockMvc.perform(get("/api/destinations").param("search", airportCode.toLowerCase()))
                .andExpect(jsonPath("$.destinations[?(@.code == '%s')]".formatted(airportCode))
                        .isNotEmpty());
    }

    /** The duplicate code, refused in either case, with the code the UI can branch on. */
    @Test
    void aDuplicateAirportCodeIsRefusedEvenInAnotherCase() throws Exception {
        Destination existing = seed();

        mockMvc.perform(post("/api/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"city":"%s Second","code":"%s","airport":"Another Airport","status":"Active"}
                                """.formatted(tag(), existing.getCode().toLowerCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DESTINATION_CODE_EXISTS"));
    }

    /**
     * The mock's city rule, now enforced by the API — {@code admin-destinations.js}
     * refuses a second "Pokhara" because the wizard's arrival list would show the
     * city twice, and there is no database constraint behind it.
     */
    @Test
    void aDuplicateCityIsRefused() throws Exception {
        Destination existing = seed();

        mockMvc.perform(post("/api/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"city":"%s","code":"%s","airport":"Same City Airport","status":"Active"}
                                """.formatted(existing.getCity().toUpperCase(), code())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DESTINATION_CITY_EXISTS"));
    }

    @Test
    void anUpdateReplacesTheFields() throws Exception {
        String tag = tag();
        Destination destination = seedNamed(tag + " Before");

        mockMvc.perform(put("/api/admin/destinations/" + destination.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"city":"%s After","code":"%s","airport":"Renamed Airport",
                                 "description":"Updated","status":"Inactive","imageUrl":"%s"}
                                """.formatted(tag, destination.getCode(), CLOUDINARY_URL)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value(tag + " After"))
                .andExpect(jsonPath("$.airport").value("Renamed Airport"))
                .andExpect(jsonPath("$.status").value("Inactive"))
                .andExpect(jsonPath("$.imageUrl").value(CLOUDINARY_URL));
    }

    /**
     * An edit that keeps the same URL keeps the stored public id with it, and one
     * that clears the URL drops both.
     *
     * <p>This is the half of the pairing that is easy to get wrong in a way only
     * the storage bill shows: clearing the URL but keeping the handle would make
     * the next cleanup call destroy an asset that is still in use, and replacing
     * the URL while keeping a stale handle would leave the new file uncollectable.
     */
    @Test
    void theImageUrlAndItsPublicIdMoveTogether() throws Exception {
        Destination destination = seed();
        destination.setImageUrl(CLOUDINARY_URL);
        destination.setImagePublicId("yatra/destinations/pokhara");
        destinations.save(destination);

        // Same fields, same URL, no public id supplied → the stored handle survives.
        mockMvc.perform(put("/api/admin/destinations/" + destination.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"city":"%s","code":"%s","airport":"%s","status":"Active","imageUrl":"%s"}
                                """.formatted(destination.getCity(), destination.getCode(),
                                destination.getAirport(), CLOUDINARY_URL)))
                .andExpect(status().isOk());

        assertThat(destinations.findById(destination.getId()).orElseThrow().getImagePublicId())
                .as("an edit that did not touch the image must not orphan the asset")
                .isEqualTo("yatra/destinations/pokhara");

        // An empty URL is how the form says "remove the image" — both fields go.
        mockMvc.perform(put("/api/admin/destinations/" + destination.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"city":"%s","code":"%s","airport":"%s","status":"Active","imageUrl":""}
                                """.formatted(destination.getCity(), destination.getCode(),
                                destination.getAirport())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imageUrl").value(""));

        Destination cleared = destinations.findById(destination.getId()).orElseThrow();
        assertThat(cleared.getImageUrl()).isNull();
        assertThat(cleared.getImagePublicId()).isNull();
    }

    /** An unknown status defaults to Active rather than storing a blank. */
    @Test
    void aBlankStatusDefaultsToActive() throws Exception {
        String tag = tag();

        mockMvc.perform(post("/api/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"city":"%s","code":"%s","airport":"Default Status Airport","status":""}
                                """.formatted(tag + " Default", code())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Active"));
    }

    @Test
    void anInvalidBodyIsRejectedBeforeTheServiceRuns() throws Exception {
        String token = adminToken();

        // A code that is not three letters, and a blank city.
        mockMvc.perform(post("/api/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"city":"  ","code":"POKHARA","airport":"X","status":"Active"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        // An unknown status.
        mockMvc.perform(post("/api/admin/destinations")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"city":"%s","code":"%s","airport":"Fine Airport","status":"Retired"}
                                """.formatted(tag() + " Bad Status", code())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anUnusedDestinationCanBeDeletedAndIsThenGone() throws Exception {
        Destination destination = seed();

        mockMvc.perform(delete("/api/admin/destinations/" + destination.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isNoContent());

        assertThat(destinations.findById(destination.getId())).isEmpty();
    }

    /* ------------------------------------------------------------------ *
     *  fixtures                                                          *
     * ------------------------------------------------------------------ */

    private String adminToken() {
        return jwtUtil.generate(1, "Dikshya Ghising", "admin@gmail.com", "ADMIN");
    }

    private Destination seed() {
        return seed(null);
    }

    private Destination seed(String imageUrl) {
        return destinations.save(row(tag() + " City", tag() + " Airport", imageUrl));
    }

    private Destination seedNamed(String city) {
        return destinations.save(row(city, city + " Airport", null));
    }

    private Destination row(String city, String airport, String imageUrl) {
        Destination destination = new Destination();
        destination.setCity(city);
        destination.setCode(code());
        destination.setAirport(airport);
        destination.setDescription("Test fixture");
        destination.setImageUrl(imageUrl);
        destination.setStatus("Active");
        return destination;
    }

    /** Isolates each test's rows from other tests and from the seeded airports. */
    private static String tag() {
        return "T" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * A three-letter airport code that is actually free.
     *
     * <p>Checked against the table rather than assumed: Phase 7 seeds eleven real
     * codes into this same schema, and a lucky random hit would turn a passing
     * assertion into a 409 from the unique key — a flake that would look like a
     * bug in the service.
     */
    private String code() {
        Random random = new Random();
        for (int attempt = 0; attempt < 100; attempt++) {
            String candidate = "" + (char) ('A' + random.nextInt(26))
                    + (char) ('A' + random.nextInt(26))
                    + (char) ('A' + random.nextInt(26));
            if (destinations.findByCodeIgnoreCase(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new IllegalStateException("No free three-letter airport code left");
    }

    private static int idOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return Integer.parseInt(body.replaceAll("(?s).*?\"id\"\\s*:\\s*(\\d+).*", "$1"));
    }
}
