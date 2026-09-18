package io.virinchi.yatra.Controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.server.ResponseStatusException;

/**
 * Serves the 34 storefront and admin pages — the view half of the application, which
 * until now had no controller at all.
 *
 * <h2>Why this class had to exist</h2>
 * <p>The pages live in {@code src/main/resources/templates/} (34 files) and
 * {@code spring-boot-starter-thymeleaf} is on the classpath, but
 * <b>nothing mapped a URL to a view</b>: there is no {@code static/*.html} and no
 * {@code @Controller} returning a view name. Measured on a booted app before this
 * class was added: {@code GET /} → <b>404</b> and {@code GET /home.html} → <b>404</b>,
 * so the application could not serve its own site. {@code SecurityConfig} was
 * already permitting {@code /}, {@code /*.html} and {@code /assets/**} (R10) — it was
 * permitting files nobody served.
 *
 * <h2>Why one mapping instead of thirty-four methods</h2>
 * <p>A page's URL <i>is</i> its template name, so thirty-four hand-written methods
 * would be thirty-four chances to typo a view name and one more thing to remember when
 * a page is added. {@code /{page}.html} is the whole rule, and
 * {@code ClassPathResource.exists()} turns an unknown page into the project's own 404
 * instead of a Thymeleaf {@code TemplateInputException} surfacing as a 500.
 *
 * <p><b>{@code {page}} cannot contain a slash</b> (Spring matches a path variable
 * within one segment), so the name cannot walk out of {@code templates/} — the
 * existence check is about a 404 instead of a stack trace, not about traversal.
 *
 * <p><b>Why an unknown page is a {@link ResponseStatusException} and not an
 * {@code ApiException}.</b> {@code GlobalExceptionHandler} is deliberately scoped with
 * {@code @RestControllerAdvice(annotations = RestController.class)}: its own class doc
 * says a browser hitting a broken page <i>must</i> get HTML, not the API's
 * {@code { error, message }} body, so page-side errors keep Spring's normal handling.
 * Throwing {@code ResourceNotFoundException} here would therefore have escaped the
 * advice entirely and surfaced as a 500 — measured, which is how this line was
 * chosen. {@code ResponseStatusException} is resolved by the dispatcher itself, so it
 * answers a plain 404 whatever the {@code Accept} header.
 *
 * <h2>Why the pages render untouched</h2>
 * <p>They are plain HTML: no {@code th:} attributes, no {@code [[...]]} inline
 * expressions, and not a single template variable. Every {@code ${...}} in them is a
 * JavaScript template literal inside a {@code <script>} block, which Thymeleaf does not
 * interpret — verified before this class was written, because a single stray
 * {@code [[} would have made Thymeleaf evaluate page JavaScript. Data reaches the pages
 * through {@code assets/js/*} calling the REST API, not through the model, which is the
 * design {@code mock-data.js} + {@code api.js} were built for (§35: pages never branch
 * on mock/real).
 */
@Controller
public class PageController {

    /** Matches Boot's {@code spring.thymeleaf.prefix} default; used only for the 404 check. */
    private static final String TEMPLATE_ROOT = "templates/";

    private static final String SUFFIX = ".html";

    /**
     * The site's entry point.
     *
     * <p>There is no {@code index.html} (the roadmap names this as a known landmine:
     * "{@code http://localhost:8080/} 404s unless a tiny redirect page is added —
     * {@code home.html} is the entry"). A redirect rather than a forward, so the
     * browser's address bar and every relative link on the page agree on the URL.
     */
    @GetMapping("/")
    public String entry() {
        return "redirect:/home.html";
    }

    /**
     * Any page: {@code /booking.html} renders {@code templates/booking.html}.
     *
     * <p>Returns the bare name — Thymeleaf's resolver supplies the
     * {@code classpath:/templates/} prefix and the {@code .html} suffix, so returning
     * {@code "booking.html"} would look for {@code templates/booking.html.html}.
     *
     * @throws ResponseStatusException 404 for a name with no template, so a typo reads
     *         as a missing page rather than a server error (see the class note on why
     *         this is not the API's error shape)
     */
    @GetMapping("/{page}.html")
    public String page(@PathVariable String page) {
        if (!new ClassPathResource(TEMPLATE_ROOT + page + SUFFIX).exists()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No page named \"" + page + SUFFIX + "\" exists.");
        }

        return page;
    }
}
