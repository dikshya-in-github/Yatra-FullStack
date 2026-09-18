package io.virinchi.yatra.Dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * The body of {@code GET /api/admin/dashboard} — Roadmap Phase 13:
 * {@code { "bookings": [ ... ], "stats": { ... } }}.
 *
 * <h2>Two keys, because the page renders two things</h2>
 * <p>{@code admin-dashboard.js} draws a row of seven stat cards and a recent-bookings
 * table, and {@code bookings} plus {@code stats} are exactly those two. The key names
 * are the page's, not the entity's — the page reads {@code resp.bookings} and
 * {@code resp.stats}, and the mock route in {@code api.js} answers the same pair — so
 * this is risk R16 applied honestly rather than defensively: the shape carries what
 * the page uses and nothing else.
 *
 * <p><b>This record used to also carry {@code airlines}, {@code flights} and
 * {@code users}.</b> Those three existed only because the page derived the cards from
 * them — it counted {@code users.length} and so on. Once the aggregates moved here
 * (this phase's build item) nothing read them: three whole-table reads per dashboard
 * load, on a page that shows a number from each. They were removed with the page and
 * the mock in the same change so the two sides could not drift, which is also why the
 * trim was done <i>after</i> the aggregates landed rather than before it — the
 * intermediate state where the arrays were gone but the cards still needed them would
 * have been a page rendering seed sizes with no error anywhere.
 *
 * <h2>Every number is the database's own answer</h2>
 * <p>{@link Stats} is built by {@code Service/DashboardService} from
 * {@code count}/{@code sum} queries — not from the {@code bookings} list beside it —
 * so a dashboard read no longer loads the tables it only counts. The three
 * definitions that have a choice in them are pinned here rather than left to whoever
 * writes the next query:
 *
 * <ul>
 *   <li><b>Revenue</b> is the sum of each booking's amount over the <b>non-cancelled</b>
 *       bookings, not the sum of {@code SUCCESS} payment rows. The page's own title says
 *       "Sum of confirmed (non-cancelled) bookings", and a money-first definition would
 *       silently renumber the card the first time a payment and its booking disagreed.
 *       Changing it is a decision.</li>
 *   <li><b>Today's Bookings</b> counts bookings <i>created</i> today — a sales number,
 *       sitting next to Pending Payments — not flights departing today.</li>
 *   <li><b>Pending Payments</b> counts bookings whose {@code paymentStatus} is
 *       {@code Pending}: the display vocabulary {@link AdminBookingResponse} already
 *       emits for the table's badges, so the card and the rows cannot disagree about the
 *       same booking. The payment table's own {@code PENDING} is deliberately <i>not</i>
 *       what this counts.</li>
 * </ul>
 */
public record AdminDashboardResponse(

        /** The recent-bookings table's rows, in the admin booking shape. */
        List<AdminBookingResponse> bookings,

        /** The seven Master Plan §2.5 cards, computed in the database. */
        Stats stats
) {

    /**
     * The seven cards, in the order {@code admin-dashboard.html} shows them.
     *
     * <p>A nested record rather than seven flat keys so a reader (and the page) can ask
     * "did the server compute these?" with one check instead of seven.
     */
    public record Stats(
            long totalUsers,
            long totalFlights,
            long totalAirlines,
            long totalBookings,
            long todaysBookings,
            BigDecimal revenue,
            long pendingPayments
    ) {
    }
}
