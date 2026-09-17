package io.virinchi.yatra.Dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.virinchi.yatra.Model.Flight;

import java.util.List;
import java.util.Map;

/**
 * The body of {@code GET /api/admin/flights}: {@code { "flights": [ ... ] }}.
 *
 * <p><b>The wrapper is the contract, not a bare array.</b> The mock answers
 * {@code { flights: MockDB.getFlights() }} and {@code admin-flights.js} reads
 * {@code resp.flights} — starting with {@code Array.isArray(resp.flights)} — then
 * falls back to its localStorage seed when that is empty. A bare array, or a
 * Spring {@code Page} serialised directly ({@code { content: [...] }}), leaves
 * {@code resp.flights} undefined and the page would silently render its fallback
 * rows as though they were real data. Same shape as the airline list, for the
 * same reason.
 *
 * <p><b>Paging metadata appears only when paging was asked for</b>
 * ({@code @JsonInclude(NON_NULL)}), so the unpaged response stays byte-compatible
 * with the mock. Adding keys is safe; changing {@code flights} would not.
 *
 * <p>{@code bookedSeats} is passed in as a map rather than counted per row: a
 * 50-row list would otherwise fire 50 extra {@code COUNT} queries.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FlightListResponse(

        List<FlightResponse> flights,
        Integer page,
        Integer size,
        Long totalElements,
        Integer totalPages
) {

    /** The unpaged shape: every matching flight, in the service's deterministic order. */
    public static FlightListResponse of(List<Flight> flights, Map<Integer, Long> bookedByFlight) {
        return new FlightListResponse(
                flights.stream()
                        .map(flight -> FlightResponse.of(
                                flight, bookedByFlight.getOrDefault(flight.getId(), 0L)))
                        .toList(),
                null, null, null, null);
    }

    /** The paged shape: one page plus the counts a client needs to walk the rest. */
    public static FlightListResponse of(org.springframework.data.domain.Page<Flight> page,
                                        Map<Integer, Long> bookedByFlight) {
        return new FlightListResponse(
                page.getContent().stream()
                        .map(flight -> FlightResponse.of(
                                flight, bookedByFlight.getOrDefault(flight.getId(), 0L)))
                        .toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
