package io.virinchi.yatra.Service;

import io.virinchi.yatra.Dto.AdminDashboardResponse;
import io.virinchi.yatra.Dto.AdminDashboardResponse.Stats;
import io.virinchi.yatra.Repository.AirlineRepository;
import io.virinchi.yatra.Repository.BookingRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import io.virinchi.yatra.Repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * The admin dashboard's read — Roadmap Phase 13.
 *
 * <h2>Why this is its own service and not a method on four of them</h2>
 * <p>The one thing it does is cross-domain by nature: the seven cards count users,
 * airlines, flights and bookings, and the recent-bookings table is the booking record.
 * Spreading it over the four owning services would mean four places calling each other
 * and one controller composing the answer, and the composition <i>is</i> the feature.
 * Every other module keeps its own service unchanged; this one only reads from them.
 *
 * <h2>Two kinds of read, deliberately</h2>
 * <ul>
 *   <li><b>The rows</b> come from {@link BookingService}, because that is what owns the
 *       admin booking shape and the batched passenger query that makes a list of them
 *       cheap. Reaching past it into {@code BookingRepository} would duplicate the
 *       mapper here — the third copy of the admin booking row, which is exactly how two
 *       pages start showing different numbers for the same booking.</li>
 *   <li><b>The seven numbers</b> come from {@code count}/{@code sum} queries on the four
 *       repositories. They deliberately do <i>not</i> come from the rows: a dashboard
 *       that counts a table by loading it is doing the database's job in Java, and the
 *       totals would then be a second interpretation of the same data. The one
 *       exception is {@code totalBookings}, which is also the list's size — both are
 *       true and the queries say so directly.</li>
 * </ul>
 *
 * <p><b>Read-only, and one transaction.</b> Every read must happen inside <i>one</i>
 * transaction or the cards could straddle a write and describe a database that never
 * existed at any single moment — {@code @Transactional(readOnly = true)} here is what
 * makes the numbers consistent with each other and with the rows beside them.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    /** The booking text the Pending Payments card counts — see the DTO's class note. */
    private static final String PAYMENT_PENDING = "Pending";

    private final BookingService bookingService;

    private final BookingRepository bookingRepository;
    private final UserRepository userRepository;
    private final AirlineRepository airlineRepository;
    private final FlightRepository flightRepository;

    /**
     * Everything {@code admin-dashboard.html} renders, in one read.
     *
     * <p><b>Every filter is deliberately absent.</b> These are totals, not a filtered
     * view: the unfiltered list call is how the booking module already expresses "all of
     * them, in its deterministic order" (R6 — passing no sort still resolves to a
     * whitelisted default rather than an unordered read), and the four totals are the
     * whole tables by definition.
     *
     * <p>The bookings are the full records, with their passengers and their flight,
     * because the page's recent-bookings table sorts and pages exactly these rows. The
     * day range is computed once, half-open {@code [midnight, next midnight)} — see
     * {@code BookingRepository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan}.
     */
    @Transactional(readOnly = true)
    public AdminDashboardResponse load() {
        LocalDateTime startOfToday = LocalDate.now().atStartOfDay();
        LocalDateTime startOfTomorrow = startOfToday.plusDays(1);

        var bookings = bookingService.listBookings(null, null, null, null, null, null, null);

        Stats stats = new Stats(
                userRepository.count(),
                flightRepository.count(),
                airlineRepository.count(),
                bookingRepository.count(),
                bookingRepository.countByCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                        startOfToday, startOfTomorrow),
                revenue(),
                bookingRepository.countByPaymentStatusIgnoreCase(PAYMENT_PENDING));

        return new AdminDashboardResponse(bookings, stats);
    }

    /**
     * The Revenue card, with the empty-table case answered as {@code 0}.
     *
     * <p>{@code sum} over no rows is {@code null} in SQL, and a {@code null} here would
     * serialise as {@code "revenue": null} and print {@code NPR 0} on the page by luck
     * rather than by contract. Zero is the honest number for "nothing sold yet", so the
     * null is resolved once, here.
     */
    private BigDecimal revenue() {
        return Optional.ofNullable(bookingRepository.sumNonCancelledRevenue())
                .orElse(BigDecimal.ZERO);
    }
}
