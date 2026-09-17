package io.virinchi.yatra.Dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.virinchi.yatra.Model.Airline;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * The body of {@code GET /api/airlines}: {@code { "airlines": [ ... ] }}.
 *
 * <p><b>The wrapper is the contract, not a bare array.</b> The mock returns
 * {@code { airlines: MockDB.getAirlines() }} and {@code admin-airlines.js} reads
 * {@code resp.airlines} — starting with {@code Array.isArray(resp.airlines)}. A
 * Spring {@code Page} serialised directly would answer {@code { content: [...],
 * pageable: {...} }}, so {@code resp.airlines} would be {@code undefined} and the
 * page would silently fall back to its mock seed. Hence this record.
 *
 * <p><b>Paging metadata appears only when paging was asked for.</b>
 * {@code @JsonInclude(NON_NULL)} means the unpaged response — the shape the pages
 * consume today — is byte-identical to the mock's, and the extra keys show up
 * only for a caller that passes {@code size}. Adding keys is safe; changing
 * {@code airlines} would not be.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AirlineListResponse(

        List<AirlineResponse> airlines,
        Integer page,
        Integer size,
        Long totalElements,
        Integer totalPages
) {

    /** The unpaged shape: every matching airline, in the service's deterministic order. */
    public static AirlineListResponse of(List<Airline> airlines) {
        return new AirlineListResponse(
                airlines.stream().map(AirlineResponse::of).toList(),
                null, null, null, null);
    }

    /** The paged shape: one page plus the counts a client needs to walk the rest. */
    public static AirlineListResponse of(Page<Airline> page) {
        return new AirlineListResponse(
                page.getContent().stream().map(AirlineResponse::of).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
