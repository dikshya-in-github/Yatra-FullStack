package io.virinchi.yatra.RestAPIController;

import io.virinchi.yatra.Dto.AdminDashboardResponse;
import io.virinchi.yatra.Service.DashboardService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The admin dashboard — Roadmap Phase 13. One route, one read.
 *
 * <p><b>Admin-only twice over</b>, like every other admin route: the
 * {@code /api/admin/**} rule in {@code SecurityConfig} refuses first and
 * {@code @PreAuthorize("hasRole('ADMIN')")} refuses again inside the method.
 * {@code AdminRouteAuthorizationTest} discovers the mapping and asserts both layers,
 * so a route added here cannot quietly skip either — and it is also in that test's
 * named-route floor, so a change that dropped it out of discovery fails the build
 * rather than passing vacuously.
 *
 * <p><b>A controller of its own rather than a method somewhere else.</b> The
 * dashboard belongs to no single module — it reads all four — so hanging it off
 * {@code AdminBookingController} or {@code AdminUserController} would put a
 * cross-domain route behind one module's name and imply a scope it does not have.
 * {@code AdminPaymentController} and {@code AdminTicketController} are the existing
 * precedent for "its own page, its own controller".
 *
 * <p><b>No parameters, on purpose.</b> The cards are totals for the whole system
 * (Master Plan §2.5), and the page applies its own search and filters to the tables
 * it owns — a dashboard that could be filtered would need a page-side control to
 * filter it, which is Phase 14's list, not this card row's.
 */
@RestController
@RequestMapping("/api/admin/dashboard")
@RequiredArgsConstructor
public class AdminDashboardController {

    private final DashboardService dashboardService;

    /**
     * The seven stat cards and the recent-bookings table, in one body.
     *
     * <p>Answers the four collections the page has always read, plus {@code stats} —
     * see {@link AdminDashboardResponse} for why the aggregates were <i>added</i>
     * rather than replacing the lists (risk R16: a renamed key would empty the table
     * and reset the cards to their seed sizes with no error on the page).
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public AdminDashboardResponse dashboard() {
        return dashboardService.load();
    }
}
