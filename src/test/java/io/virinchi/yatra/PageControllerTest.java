package io.virinchi.yatra;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The site's own pages — {@code Controller/PageController}.
 *
 * <p><b>This suite exists because the pages were not served at all.</b> Measured on a
 * booted application before the controller was added: {@code GET /} → 404 and
 * {@code GET /home.html} → 404, with 34 files sitting in {@code templates/},
 * {@code spring-boot-starter-thymeleaf} on the classpath, and no view controller to
 * render any of them. Nothing in the existing suite could have caught that — every
 * other test drives {@code /api/**}, and {@code SecurityConfig} was happily permitting
 * {@code /*.html} for files nobody served.
 *
 * <h2>Why it sweeps instead of sampling</h2>
 * <p>The mapping is one rule ({@code /{page}.html} → {@code templates/{page}.html}), so
 * the interesting question is not "does home.html work" but "<i>does every page</i>
 * work". The list of pages is read from the classpath rather than written down here, so
 * a page added tomorrow is covered without editing this file — and a page that stops
 * resolving fails the build instead of 404ing in front of the examiner.
 *
 * <h2>What it deliberately does not assert</h2>
 * <p>Nothing about the page <i>contents</i> beyond "this is HTML". The pages render
 * their data from {@code assets/js/*} calling the API (that is the {@code api.js}
 * design), so their markup is static and asserting on it would pin the design system
 * rather than the routing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PageControllerTest {

    @Autowired private MockMvc mockMvc;

    /**
     * The entry point. There is no {@code index.html}, so {@code /} has to send the
     * browser somewhere — the roadmap names this as a known landmine.
     */
    @Test
    void theRootRedirectsToTheEntryPage() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "/home.html"));
    }

    /**
     * Every template on the classpath is reachable by its own name — the whole point of
     * the controller.
     */
    @Test
    void everyTemplateIsServed() throws Exception {
        List<String> pages = templateNames();
        assertThat(pages)
                .as("the storefront and admin pages must still be on the classpath")
                .hasSizeGreaterThanOrEqualTo(30)
                .contains("home", "admin-login", "admin-tickets", "searchFlight", "login");

        List<String> broken = new ArrayList<>();
        for (String page : pages) {
            int status = mockMvc.perform(get("/" + page + ".html"))
                    .andReturn().getResponse().getStatus();
            if (status != 200) {
                broken.add(page + ".html → " + status);
            }
        }

        assertThat(broken)
                .as("every page in templates/ must answer 200 — this is the site the app serves")
                .isEmpty();
    }

    /** One page in full, so the sweep above is not the only thing standing behind it. */
    @Test
    void aPageIsServedAsHtml() throws Exception {
        mockMvc.perform(get("/home.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<html")));
    }

    /**
     * An unknown page is a 404, not a Thymeleaf stack trace. Without the existence check
     * the resolver throws inside the view layer, which surfaces as a 500 — a typo in a
     * URL would look like a broken server.
     *
     * <p><b>It asserts a status, not the API's error body, and that is the point.</b>
     * {@code GlobalExceptionHandler} is scoped to {@code @RestController} on purpose, so
     * a browser hitting a bad page must not receive {@code { "error": "..." }}. The first
     * version of the controller threw {@code ResourceNotFoundException} here and the
     * advice never saw it — an unhandled 500 — which is what a booted app showed. See
     * {@code PageController}'s class note.
     */
    @Test
    void anUnknownPageIsA404() throws Exception {
        mockMvc.perform(get("/no-such-page.html").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());

        // A JSON body here would mean the REST advice had leaked onto the page layer.
        mockMvc.perform(get("/no-such-page.html").accept(MediaType.APPLICATION_JSON))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("PAGE_NOT_FOUND"))));
    }

    /**
     * The mapping must not swallow the API. {@code /{page}.html} cannot match
     * {@code /api/...} (there is no {@code .html} segment), and this is the assertion
     * that keeps it that way — a loosened pattern here would be invisible until a page
     * started receiving HTML where it expected JSON.
     */
    @Test
    void theApiRoutesAreUntouched() throws Exception {
        mockMvc.perform(get("/api/airlines"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.airlines").isArray());
    }

    /**
     * The pages link {@code assets/css/...} and {@code assets/js/...} relatively, so
     * serving the HTML is only half of a working site. This is the other half, on the
     * static-resource handler rather than on the controller.
     */
    @Test
    void theAssetsThePagesLinkAreServed() throws Exception {
        for (String asset : List.of("js/config.js", "js/api.js", "js/auth.js", "css/homeLogged.css")) {
            mockMvc.perform(get("/assets/" + asset))
                    .andExpect(status().isOk());
        }
    }

    /* ------------------------------------------------------------------ *
     *  helpers                                                            *
     * ------------------------------------------------------------------ */

    /** Every {@code templates/*.html} on the classpath, without its extension. */
    private static List<String> templateNames() throws IOException {
        Resource[] templates = new PathMatchingResourcePatternResolver()
                .getResources("classpath:templates/*.html");

        List<String> names = new ArrayList<>();
        for (Resource template : templates) {
            names.add(String.valueOf(template.getFilename()).replaceFirst("\\.html$", ""));
        }
        return names;
    }
}
