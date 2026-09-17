package io.virinchi.yatra.Dto;

import java.util.List;
import java.util.Map;

/**
 * What {@code POST /api/admin/seed} and {@code POST /api/admin/reset} report back.
 *
 * <p><b>Why a report and not just 200.</b> Both operations are bulk and
 * destructive-adjacent, and the roadmap's Phase 7 checkpoint is "all admin list
 * endpoints return realistic non-empty data" — a body that says how many rows each
 * step actually wrote (or removed) is what makes that checkable from Postman in one
 * glance instead of by opening five tables.
 *
 * <p>{@code counts} is keyed by table name in insertion order (airlines,
 * destinations, users, flights, seats, bookings, passengers, payments, tickets), so
 * the same keys come back for a seed and for a reset and the two can be compared
 * directly. A {@code LinkedHashMap} on the service side is what keeps that order
 * stable in the JSON.
 *
 * <p><b>A seed's counts describe the dataset in place, not the rows it had to
 * insert.</b> Contributing rows are reused rather than duplicated — an airport whose
 * code already exists, a carrier whose IATA code is taken, an account that already
 * exists — so "destinations: 11" means the eleven airports the seeder needs are
 * available, which is exactly what the checkpoint asks. A reset's counts are literal
 * deletions.
 *
 * <p>{@code kept} names rows a reset deliberately did <b>not</b> remove, with the
 * reason. It exists because "reset" must never quietly mean "delete something you
 * made": a seeded airline that your own hand-created flight still uses is reported
 * here and left alone, and {@code destinations} are reference data that no reset
 * touches.
 */
public record SeedResponse(

        String action,
        String message,
        Map<String, Integer> counts,
        List<String> kept
) {
}
