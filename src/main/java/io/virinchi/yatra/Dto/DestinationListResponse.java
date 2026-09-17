package io.virinchi.yatra.Dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.virinchi.yatra.Model.Destination;
import org.springframework.data.domain.Page;

import java.util.List;

/**
 * The body of {@code GET /api/destinations}: {@code { "destinations": [ ... ] }}.
 *
 * <p><b>The wrapper is the contract.</b> The mock returns
 * {@code { destinations: MockDB.getDestinations() }} and both
 * {@code admin-destinations.js} and {@code admin-flights.js} read
 * {@code resp.destinations} after an {@code Array.isArray} check — a Spring
 * {@code Page} serialised directly would answer {@code { content: [...] }}, the
 * check would fail, and the admin page would silently fall back to its
 * localStorage seed without saying so.
 *
 * <p><b>Paging metadata appears only when paging was asked for.</b>
 * {@code @JsonInclude(NON_NULL)} keeps the unpaged body — the shape the pages
 * consume today, since both filter and page client-side — byte-compatible with
 * the mock. The extra keys arrive only for a caller that passes {@code size}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DestinationListResponse(

        List<DestinationResponse> destinations,
        Integer page,
        Integer size,
        Long totalElements,
        Integer totalPages
) {

    /** The unpaged shape: every matching destination, in the service's deterministic order. */
    public static DestinationListResponse of(List<Destination> destinations) {
        return new DestinationListResponse(
                destinations.stream().map(DestinationResponse::of).toList(),
                null, null, null, null);
    }

    /** The paged shape: one page plus the counts a client needs to walk the rest. */
    public static DestinationListResponse of(Page<Destination> page) {
        return new DestinationListResponse(
                page.getContent().stream().map(DestinationResponse::of).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
