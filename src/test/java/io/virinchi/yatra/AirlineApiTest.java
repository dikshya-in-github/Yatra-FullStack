package io.virinchi.yatra;

import io.virinchi.yatra.Model.Airline;
import io.virinchi.yatra.Repository.AirlineRepository;
import io.virinchi.yatra.Security.JwtUtil;
import io.virinchi.yatra.Service.Paging;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The airline module — the reference pattern for every later admin module.
 *
 * <p>DB-backed and {@code @Transactional} like {@code BookingDeletionTest}, so
 * every row is rolled back and the live database is never modified. Rows created
 * here carry a per-run tag and are always read back through {@code ?search=}, so
 * the assertions stay valid even once Phase 7 seeds real airlines.
 *
 * <p>Three of these tests are guards for specific things that were found by
 * reading the code rather than by running it:
 * <ul>
 *   <li>{@link #airlineReadsArePublicSoTheStorefrontLogoCannot401()} — the R8
 *       landmine: {@code /api/airlines/**} was not permitted, and because
 *       {@code searchFlight.js} renders the image with {@code onerror} plus a
 *       badge fallback, a 401 there is <b>silent</b>;</li>
 *   <li>{@link #theListNeverCarriesTheBase64Blob()} — R8's actual requirement;</li>
 *   <li>{@link #anEditThatEchoesTheServedLogoUrlDoesNotClobberTheStoredImage()} —
 *       the admin form stages the row's {@code logo} back into the form, so a
 *       naive "write whatever was posted" would replace the image with the
 *       string {@code /api/airlines/3/logo}.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AirlineApiTest {

    /** A real 1×1 PNG, used as the "uploaded" image. */
    private static final String PNG_BASE64 =
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==";

    @Autowired private MockMvc mockMvc;
    @Autowired private AirlineRepository airlines;
    @Autowired private JwtUtil jwtUtil;

    /* ------------------------------------------------------------------ *
     *  public reads                                                       *
     * ------------------------------------------------------------------ */

    /**
     * With no token at all — the storefront case. Before the GET permit existed
     * this returned 401, and the page would simply have shown no logos for guests.
     */
    @Test
    void airlineReadsArePublicSoTheStorefrontLogoCannot401() throws Exception {
        Airline airline = seed("Public", PNG_BASE64);

        mockMvc.perform(get("/api/airlines").param("search", airline.getName()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/airlines/" + airline.getId()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/airlines/" + airline.getId() + "/logo"))
                .andExpect(status().isOk());
    }

    /** The wrapper the mock returns and {@code admin-airlines.js} reads. */
    @Test
    void theListIsWrappedInAnAirlinesKeyAndUnpagedByDefault() throws Exception {
        Airline airline = seed("Wrapped", null);

        mockMvc.perform(get("/api/airlines").param("search", airline.getName()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.airlines").isArray())
                .andExpect(jsonPath("$.airlines[0].name").value(airline.getName()))
                .andExpect(jsonPath("$.airlines[0].iata").value(airline.getIata()))
                .andExpect(jsonPath("$.airlines[0].status").value("Active"))
                // Paging keys must be absent, not null — the unpaged body has to
                // stay byte-compatible with the mock's shape.
                .andExpect(jsonPath("$.page").doesNotExist())
                .andExpect(jsonPath("$.size").doesNotExist())
                .andExpect(jsonPath("$.totalElements").doesNotExist());
    }

    /** R8's whole point: the image must not be in the list payload. */
    @Test
    void theListNeverCarriesTheBase64Blob() throws Exception {
        Airline airline = seed("NoBlob", PNG_BASE64);

        MvcResult result = mockMvc.perform(get("/api/airlines").param("search", airline.getName()))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("the raw image must never appear in a list response")
                .doesNotContain("iVBORw0KGgo")
                .doesNotContain("data:image");
        assertThat(body)
                .as("the field is a URL the browser can fetch")
                .contains("/api/airlines/" + airline.getId() + "/logo");
    }

    /** An airline with no image reports the mock's empty string, not a broken URL. */
    @Test
    void anAirlineWithoutALogoReportsAnEmptyString() throws Exception {
        Airline airline = seed("NoImage", null);

        mockMvc.perform(get("/api/airlines").param("search", airline.getName()))
                .andExpect(jsonPath("$.airlines[0].logo").value(""));
    }

    @Test
    void oneUnknownAirlineIsA404WithTheProjectsErrorShape() throws Exception {
        mockMvc.perform(get("/api/airlines/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("AIRLINE_NOT_FOUND"))
                .andExpect(jsonPath("$.message").value("No Airline exists with id 999999."));
    }

    /* ------------------------------------------------------------------ *
     *  ordering + paging (R6)                                             *
     * ------------------------------------------------------------------ */

    /**
     * The default order is by id, and {@code sort=name} really changes it — two
     * airlines sharing a status must not come back in whatever order the engine
     * felt like, or a page can show one twice and the next none.
     */
    @Test
    void theListOrderIsExplicitAndTheSortParameterIsHonoured() throws Exception {
        String tag = tag();
        Airline zeta = seedNamed(tag + " Zeta", null);
        Airline alpha = seedNamed(tag + " Alpha", null);

        // Default: id ascending, i.e. insertion order — Zeta was created first.
        mockMvc.perform(get("/api/airlines").param("search", tag))
                .andExpect(jsonPath("$.airlines[0].id").value(zeta.getId()))
                .andExpect(jsonPath("$.airlines[1].id").value(alpha.getId()));

        // ?sort=name is honoured.
        mockMvc.perform(get("/api/airlines").param("search", tag).param("sort", "name"))
                .andExpect(jsonPath("$.airlines[0].id").value(alpha.getId()))
                .andExpect(jsonPath("$.airlines[1].id").value(zeta.getId()));

        // An unlisted property falls back to id rather than reaching the query.
        // `sort=logo` would otherwise ORDER BY a MEDIUMBLOB.
        mockMvc.perform(get("/api/airlines").param("search", tag).param("sort", "logo"))
                .andExpect(jsonPath("$.airlines[0].id").value(zeta.getId()));
    }

    /** Two pages of one row each must not overlap — the failure R6 describes. */
    @Test
    void pagingReturnsDisjointPagesWithCounts() throws Exception {
        String tag = tag();
        seedNamed(tag + " One", null);
        seedNamed(tag + " Two", null);

        MvcResult first = mockMvc.perform(get("/api/airlines")
                        .param("search", tag).param("page", "0").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.airlines.length()").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andReturn();

        MvcResult second = mockMvc.perform(get("/api/airlines")
                        .param("search", tag).param("page", "1").param("size", "1"))
                .andExpect(jsonPath("$.airlines.length()").value(1))
                .andReturn();

        assertThat(idOf(second)).as("the second page holds the other row")
                .isNotEqualTo(idOf(first));
    }

    /**
     * {@code size} is bounded at both ends, and this is the public list.
     *
     * <p>Paging is the one part of a list endpoint that is fed straight by the caller,
     * and until Phase 14 the only sanitising anywhere was {@code Math.max(size, 1)}: no
     * upper end, so {@code ?size=1000000} asked the server to materialise the whole table
     * into one page. On {@code GET /api/airlines} — public, because the storefront is —
     * that request needs no token and no account. Both ends are asserted here because
     * they are the same bug: an unvalidated number reaching {@code PageRequest}. See
     * {@link Paging}.
     */
    @Test
    void anOutOfRangePageSizeIsBoundedRatherThanServed() throws Exception {
        // Absurdly large: served, but capped at the ceiling.
        mockMvc.perform(get("/api/airlines").param("page", "0").param("size", "1000000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(Paging.MAX_SIZE))
                .andExpect(jsonPath("$.airlines.length()").value(lessThanOrEqualTo(Paging.MAX_SIZE)));

        // Zero and negative: one row on the first page. PageRequest.of refuses a size
        // below 1 outright, so without the clamp this is a 500 rather than a page.
        mockMvc.perform(get("/api/airlines").param("page", "-5").param("size", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1))
                .andExpect(jsonPath("$.airlines.length()").value(lessThanOrEqualTo(1)));
    }

    @Test
    void searchAndStatusFilterIndependently() throws Exception {
        String tag = tag();
        Airline active = seedNamed(tag + " Active One", null);
        Airline inactive = seedNamed(tag + " Retired One", null);
        inactive.setStatus("Inactive");
        airlines.save(inactive);

        mockMvc.perform(get("/api/airlines").param("search", tag))
                .andExpect(jsonPath("$.airlines.length()").value(2));

        mockMvc.perform(get("/api/airlines").param("search", tag).param("status", "Inactive"))
                .andExpect(jsonPath("$.airlines.length()").value(1))
                .andExpect(jsonPath("$.airlines[0].id").value(inactive.getId()));

        mockMvc.perform(get("/api/airlines").param("search", tag).param("status", "Active"))
                .andExpect(jsonPath("$.airlines.length()").value(1))
                .andExpect(jsonPath("$.airlines[0].id").value(active.getId()));
    }

    /* ------------------------------------------------------------------ *
     *  the logo bytes                                                     *
     * ------------------------------------------------------------------ */

    @Test
    void theLogoEndpointServesBytesWithTheDeclaredTypeAndAnEtag() throws Exception {
        Airline airline = seed("WithLogo", "data:image/png;base64," + PNG_BASE64);

        MvcResult result = mockMvc.perform(get("/api/airlines/" + airline.getId() + "/logo"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("image/png")))
                .andExpect(header().exists(HttpHeaders.ETAG))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age")))
                .andReturn();

        assertThat(result.getResponse().getContentAsByteArray())
                .as("the decoded image, not the Base64 text")
                .isEqualTo(Base64.getDecoder().decode(PNG_BASE64));
    }

    /** An ETag is only useful if it produces a 304 — otherwise it is decoration. */
    @Test
    void aMatchingEtagAnswers304InsteadOfResendingTheImage() throws Exception {
        Airline airline = seed("Cacheable", "data:image/png;base64," + PNG_BASE64);
        String path = "/api/airlines/" + airline.getId() + "/logo";

        String etag = mockMvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn().getResponse().getHeader(HttpHeaders.ETAG);

        assertThat(etag).isNotBlank();

        mockMvc.perform(get(path).header(HttpHeaders.IF_NONE_MATCH, etag))
                .andExpect(status().isNotModified());
    }

    /**
     * A bare Base64 string (no {@code data:} prefix) is still a valid stored
     * image — its type has to come from the content, not from a header.
     */
    @Test
    void aBareBase64StringIsTypeSniffed() throws Exception {
        Airline airline = seed("Sniffed", PNG_BASE64);

        mockMvc.perform(get("/api/airlines/" + airline.getId() + "/logo"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, containsString("image/png")));
    }

    @Test
    void anAirlineWithNoLogoHasNoImageToServe() throws Exception {
        Airline airline = seed("NoImageHere", null);

        mockMvc.perform(get("/api/airlines/" + airline.getId() + "/logo"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("AIRLINE_LOGO_NOT_FOUND"));
    }

    /* ------------------------------------------------------------------ *
     *  admin writes                                                       *
     * ------------------------------------------------------------------ */

    @Test
    void writesAreForbiddenWithoutAnAdminToken() throws Exception {
        String body = """
                {"name":"Guarded Air","iata":"%s","status":"Active"}
                """.formatted(code());

        mockMvc.perform(post("/api/admin/airlines")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("NOT_AUTHENTICATED"));

        String userToken = jwtUtil.generate(2, "A Customer", "customer@example.com", "USER");
        mockMvc.perform(post("/api/admin/airlines")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("FORBIDDEN"));
    }

    /** The roadmap checkpoint: create with a logo, and the column holds the image. */
    @Test
    void creatingAnAirlineStoresTheImageInTheDatabaseAndAnswersWithAUrl() throws Exception {
        String tag = tag();
        String iata = code();

        MvcResult result = mockMvc.perform(post("/api/admin/airlines")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s Created","iata":"%s","description":"Demo",
                                 "status":"Active","logo":"data:image/png;base64,%s"}
                                """.formatted(tag, iata, PNG_BASE64)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.iata").value(iata))
                .andExpect(jsonPath("$.status").value("Active"))
                .andReturn();

        int id = idOf(result);

        // The response points at the bytes...
        mockMvc.perform(get("/api/airlines").param("search", tag))
                .andExpect(jsonPath("$.airlines[0].logo")
                        .value("/api/airlines/" + id + "/logo"));

        // ...and the row really holds the image, not a path.
        Airline stored = airlines.findById(id).orElseThrow();
        assertThat(stored.getLogo())
                .as("the column holds the image data, not a filename")
                .startsWith("data:image/png;base64,");
    }

    @Test
    void aDuplicateIataCodeIsRefusedEvenInAnotherCase() throws Exception {
        String tag = tag();
        String iata = code();

        mockMvc.perform(post("/api/admin/airlines")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s First","iata":"%s","status":"Active"}
                                """.formatted(tag, iata)))
                .andExpect(status().isOk());

        // Codes are stored upper-cased, so the lower-case form is the same airline.
        mockMvc.perform(post("/api/admin/airlines")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s Second","iata":"%s","status":"Active"}
                                """.formatted(tag, iata.toLowerCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("IATA_EXISTS"));
    }

    @Test
    void anUpdateReplacesTheFields() throws Exception {
        String tag = tag();
        Airline airline = seedNamed(tag + " Before", null);

        mockMvc.perform(put("/api/admin/airlines/" + airline.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s After","iata":"%s","description":"Updated",
                                 "status":"Inactive"}
                                """.formatted(tag, airline.getIata())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(tag + " After"))
                .andExpect(jsonPath("$.status").value("Inactive"));
    }

    /**
     * The trap: {@code admin-airlines.js} stages the row's existing {@code logo}
     * — which in real mode is the URL this API served — back into the form, so an
     * edit that chose no new file posts that URL. Writing it verbatim would
     * replace the image with the string {@code /api/airlines/3/logo}.
     */
    @Test
    void anEditThatEchoesTheServedLogoUrlDoesNotClobberTheStoredImage() throws Exception {
        String tag = tag();
        Airline airline = seedNamed(tag + " Echo", "data:image/png;base64," + PNG_BASE64);

        mockMvc.perform(put("/api/admin/airlines/" + airline.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s Echo","iata":"%s","status":"Active",
                                 "logo":"/api/airlines/%d/logo"}
                                """.formatted(tag, airline.getIata(), airline.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logo").value("/api/airlines/" + airline.getId() + "/logo"));

        assertThat(airlines.findById(airline.getId()).orElseThrow().getLogo())
                .as("the image survived the URL being echoed back")
                .startsWith("data:image/png;base64,");
    }

    /** An explicit empty string is how the form says "remove the logo". */
    @Test
    void anEmptyLogoInAnUpdateClearsTheImage() throws Exception {
        String tag = tag();
        Airline airline = seedNamed(tag + " Clear", "data:image/png;base64," + PNG_BASE64);

        mockMvc.perform(put("/api/admin/airlines/" + airline.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s Clear","iata":"%s","status":"Active","logo":""}
                                """.formatted(tag, airline.getIata())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logo").value(""));

        assertThat(airlines.findById(airline.getId()).orElseThrow().getLogo()).isNull();
    }

    @Test
    void anInvalidBodyIsRejectedBeforeTheServiceRuns() throws Exception {
        String token = adminToken();

        mockMvc.perform(post("/api/admin/airlines")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"  ","iata":"toodlong","status":"Active"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_FAILED"));

        mockMvc.perform(post("/api/admin/airlines")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Fine Air","iata":"%s","status":"Retired"}
                                """.formatted(code())))
                .andExpect(status().isBadRequest());
    }

    /** An unknown status defaults to Active rather than storing a blank. */
    @Test
    void aBlankStatusDefaultsToActive() throws Exception {
        String tag = tag();

        mockMvc.perform(post("/api/admin/airlines")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s Default","iata":"%s","status":""}
                                """.formatted(tag, code())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("Active"));
    }

    @Test
    void anUnusedAirlineCanBeDeletedAndIsThenGone() throws Exception {
        Airline airline = seed("Deletable", null);

        mockMvc.perform(delete("/api/admin/airlines/" + airline.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isNoContent());

        assertThat(airlines.findById(airline.getId())).isEmpty();
    }

    /* ------------------------------------------------------------------ *
     *  fixtures                                                          *
     * ------------------------------------------------------------------ */

    private String adminToken() {
        return jwtUtil.generate(1, "Dikshya Ghising", "admin@gmail.com", "ADMIN");
    }

    private Airline seed(String label, String logo) {
        return seedNamed(tag() + " " + label, logo);
    }

    private Airline seedNamed(String name, String logo) {
        Airline airline = new Airline();
        airline.setName(name);
        airline.setIata(code());
        airline.setDescription("Test fixture");
        airline.setStatus("Active");
        airline.setLogo(logo);
        return airlines.save(airline);
    }

    /** Isolates each test's rows from other tests and from any future seed data. */
    private static String tag() {
        return "T" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** A two-character IATA-shaped code, unique enough for a rolled-back test. */
    private static String code() {
        String uuid = UUID.randomUUID().toString().replaceAll("[^a-z0-9]", "");
        return uuid.substring(0, 2).toUpperCase();
    }

    private static int idOf(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return Integer.parseInt(body.replaceAll("(?s).*?\"id\"\\s*:\\s*(\\d+).*", "$1"));
    }
}
