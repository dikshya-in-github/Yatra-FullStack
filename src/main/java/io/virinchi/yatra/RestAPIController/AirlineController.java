package io.virinchi.yatra.RestAPIController;

import io.virinchi.yatra.Dto.AirlineListResponse;
import io.virinchi.yatra.Dto.AirlineRequest;
import io.virinchi.yatra.Dto.AirlineResponse;
import io.virinchi.yatra.Model.Airline;
import io.virinchi.yatra.Service.AirlineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.time.Duration;
import java.util.List;

/**
 * The airline module — the reference pattern every later admin module follows.
 *
 * <p><b>Two audiences on one resource.</b> The reads are public because the
 * storefront is: {@code homeLogged} search → {@code searchFlight.html} renders an
 * airline next to every flight, and it does so for signed-out visitors too. The
 * writes are admin-only and live under {@code /api/admin/airlines}. That split is
 * enforced in three places, deliberately: the route rule in
 * {@code SecurityConfig} (all of {@code /api/admin/**} needs ADMIN),
 * {@code @PreAuthorize("hasRole('ADMIN')")} on each write method, and
 * {@link io.virinchi.yatra.Security.Authorities} owning the role prefix. Hiding a
 * link in the admin page is not one of them.
 *
 * <p><b>The logo is not JSON.</b> {@code GET /api/airlines/{id}/logo} answers
 * bytes with the image's own {@code Content-Type}, a hash-based {@code ETag} and
 * a day of public caching, and honours {@code If-None-Match} with a {@code 304}.
 * List and detail bodies carry the URL to it instead of the image — see
 * {@link AirlineResponse}.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class AirlineController {

    private final AirlineService airlineService;

    /**
     * The airline list.
     *
     * <p><b>Unpaged by default, paged on request.</b> Called with no parameters
     * this returns every matching airline wrapped as {@code { "airlines": [...] }}
     * — exactly what {@code admin-airlines.js} expects, since it pages its table
     * client-side over the whole list. Passing {@code size} switches to a real
     * database page and adds {@code page}/{@code totalElements}/{@code totalPages}
     * to the body, which is what Phase 9 should use once the table pages
     * server-side. Defaulting to "everything" (rather than, say, 20) is a
     * deliberate choice: a silent truncation would look like missing data.
     *
     * @param search matches the name, case-insensitively
     * @param status {@code Active} / {@code Inactive}; blank means any
     * @param sort   one of {@code id|name|iata|status} — anything else falls back
     *               to {@code id}, because an open sort property would let a
     *               caller order by the logo blob
     */
    @GetMapping("/airlines")
    public AirlineListResponse list(@RequestParam(required = false) String search,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String sort,
                                    @RequestParam(required = false) Integer page,
                                    @RequestParam(required = false) Integer size) {

        if (size == null) {
            List<Airline> rows = airlineService.listAll(search, status, sort);
            return AirlineListResponse.of(rows);
        }

        return AirlineListResponse.of(airlineService.listPage(
                search, status, sort, page == null ? 0 : page, size));
    }

    /** One airline. */
    @GetMapping("/airlines/{id}")
    public AirlineResponse get(@PathVariable int id) {
        return AirlineResponse.of(airlineService.getAirline(id));
    }

    /**
     * The logo bytes.
     *
     * <p>{@code checkNotModified} runs before the body is built: it compares the
     * request's {@code If-None-Match} with our hash and, when the image has not
     * changed, answers {@code 304} so the browser reuses what it already has.
     * Without it an {@code ETag} would be decorative.
     */
    @GetMapping("/airlines/{id}/logo")
    public ResponseEntity<byte[]> logo(@PathVariable int id, WebRequest request) {
        AirlineService.Logo logo = airlineService.loadLogo(id);

        if (request.checkNotModified(logo.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).build();
        }

        return ResponseEntity.ok()
                .eTag(logo.etag())
                // Public: the storefront is anonymous, and the bytes only change
                // when an admin replaces the image.
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePublic())
                .contentType(MediaType.parseMediaType(logo.contentType()))
                .body(logo.bytes());
    }

    /* ------------------------------------------------------------------ *
     *  Admin writes                                                       *
     * ------------------------------------------------------------------ */

    /** Creates an airline. 409 {@code IATA_EXISTS} if the code is taken. */
    @PostMapping("/admin/airlines")
    @PreAuthorize("hasRole('ADMIN')")
    public AirlineResponse create(@Valid @RequestBody AirlineRequest request) {
        return AirlineResponse.of(airlineService.createAirline(request));
    }

    /** Replaces an airline's fields. */
    @PutMapping("/admin/airlines/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public AirlineResponse update(@PathVariable int id,
                                  @Valid @RequestBody AirlineRequest request) {
        return AirlineResponse.of(airlineService.updateAirline(id, request));
    }

    /**
     * Deletes an airline that no flight uses — 409 with "disable it instead" if
     * one does, which is the same advice the admin page's own confirm dialog gives
     * (see {@code Service/AirlineService}).
     */
    @DeleteMapping("/admin/airlines/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable int id) {
        airlineService.deleteAirline(id);
        return ResponseEntity.noContent().build();
    }
}
