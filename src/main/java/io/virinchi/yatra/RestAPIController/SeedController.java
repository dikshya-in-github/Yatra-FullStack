package io.virinchi.yatra.RestAPIController;

import io.virinchi.yatra.Dto.SeedResponse;
import io.virinchi.yatra.Service.SeedService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The demo-data endpoints — {@code POST /api/admin/seed} and {@code POST /api/admin/reset}.
 *
 * <p><b>Why endpoints and not a {@code CommandLineRunner}.</b> The roadmap offers
 * either. A startup runner would execute inside <b>every</b> {@code @SpringBootTest}
 * boot — thirteen test classes against the live TiDB Cloud database — so a test run
 * would silently write demo rows (and a reset would delete them again) as a side
 * effect of starting a context. An admin-only endpoint keeps the write where a human
 * decided to make it, which is also why the demo can be re-run at any point instead
 * of only at boot.
 *
 * <p><b>Protected twice, like every other admin write</b> (rule 4): the
 * {@code /api/admin/**} route rule in {@code SecurityConfig} requires
 * {@code ROLE_ADMIN}, and each method carries {@code @PreAuthorize}. A seed is not
 * sensitive data, but it <i>is</i> a bulk write and a destructive reset — nothing a
 * signed-out visitor or a plain customer should ever reach.
 *
 * <p>Both calls return {@link SeedResponse}, a count per table plus anything a reset
 * deliberately kept, so the roadmap's Phase 7 checkpoint ("all admin list endpoints
 * return realistic non-empty data") can be confirmed from the response itself.
 */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class SeedController {

    private final SeedService seedService;

    /**
     * Creates the demo dataset.
     *
     * <p>409 {@code ALREADY_SEEDED} if it is already there — reset first. Nothing is
     * written on that path, so a double-click cannot double the data.
     */
    @PostMapping("/seed")
    @PreAuthorize("hasRole('ADMIN')")
    public SeedResponse seed() {
        return seedService.seed();
    }

    /**
     * Removes the demo dataset, leaving everything you created.
     *
     * <p>Safe to call when nothing is seeded: it reports zero counts rather than
     * failing, so "reset then seed" is always a valid two-step.
     */
    @PostMapping("/reset")
    @PreAuthorize("hasRole('ADMIN')")
    public SeedResponse reset() {
        return seedService.reset();
    }
}
