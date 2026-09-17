package io.virinchi.yatra.RestAPIController;

import io.virinchi.yatra.Dto.FlightListResponse;
import io.virinchi.yatra.Dto.FlightRequest;
import io.virinchi.yatra.Dto.FlightResponse;
import io.virinchi.yatra.Dto.SeatMapResponse;
import io.virinchi.yatra.Model.Flight;
import io.virinchi.yatra.Service.FlightService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDate;
import java.util.List;

/**
 * The flight module — the second CRUD module, following the airline pattern.
 *
 * <p><b>Almost everything here is admin-only, unlike the airline module.</b> An
 * airline has public reads because the storefront renders one next to every flight
 * card; a flight's own list is the admin table, so it lives under
 * {@code /api/admin/**} and is protected twice over: the route rule in
 * {@code SecurityConfig} and {@code @PreAuthorize("hasRole('ADMIN')")} on each
 * method. Hiding a link in the admin page is not one of the layers.
 *
 * <p><b>The one public read is the seat map</b> ({@code GET
 * /api/flights/{id}/seats}, added in Phase 6). A booking page has to show the cabin
 * to signed-out visitors, and it is the deliberate consequence R12 flagged when the
 * {@code /api/flights/**} GET permit went in early: the permit is a prefix rule, so
 * this path inherits it. Keeping it public is the choice — a 401 here fails exactly
 * the way the airline logos did (the page would draw an empty cabin rather than an
 * error), and the map exposes nothing a browsing customer cannot already see.
 *
 * <p><b>What this module deliberately does not serve yet.</b> The storefront route
 * {@code GET /api/flights/search?origin=&destination=&date=&passengers=} is
 * committed in {@code assets/js/api.js}, but its response carries fare classes,
 * {@code fareOptions} and {@code FARE_POLICIES} — data the fare-class phase owns
 * and this phase does not build. Serving a narrower shape under that path would
 * answer a page with a body it cannot render, so {@code searchFlight.html} stays
 * on the mock until then. The GET permit for {@code /api/flights/**} is already in
 * {@code SecurityConfig} so the endpoint cannot fall into the silent-401 trap that
 * cost the airline logos a phase (risk R8).
 *
 * <p><b>Unpaged by default, paged on request</b>, exactly as the airline list: the
 * admin table filters and pages client-side over the full list today, and a hidden
 * row limit would look like missing data.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FlightController {

    private final FlightService flightService;

    /**
     * The admin flight list.
     *
     * @param search    matches the flight number, either airport code, or the
     *                  airline's name
     * @param airlineId one airline's flights
     * @param from      origin airport code
     * @param to        destination airport code
     * @param date      an exact travel date; omitted means any date, which also
     *                  includes flights that have no date yet
     * @param status    {@code Active} / {@code Inactive}; blank means any
     * @param sort      one of
     *                  {@code id|no|date|dep|arr|fare|seats|status} — anything
     *                  else falls back to {@code id}
     */
    @GetMapping("/admin/flights")
    @PreAuthorize("hasRole('ADMIN')")
    public FlightListResponse list(@RequestParam(required = false) String search,
                                   @RequestParam(required = false) Integer airlineId,
                                   @RequestParam(required = false) String from,
                                   @RequestParam(required = false) String to,
                                   @RequestParam(required = false)
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                   @RequestParam(required = false) String status,
                                   @RequestParam(required = false) String sort,
                                   @RequestParam(required = false) Integer page,
                                   @RequestParam(required = false) Integer size) {

        if (size == null) {
            List<Flight> rows = flightService.listAll(search, airlineId, from, to, date, status, sort);
            return FlightListResponse.of(rows, flightService.bookedSeatsFor(rows));
        }

        Page<Flight> rows = flightService.listPage(search, airlineId, from, to, date, status, sort,
                page == null ? 0 : page, size);
        return FlightListResponse.of(rows, flightService.bookedSeatsFor(rows.getContent()));
    }

    /** One flight, with its live booked-seat count. */
    @GetMapping("/admin/flights/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public FlightResponse get(@PathVariable int id) {
        return FlightResponse.of(flightService.getFlight(id), flightService.bookedSeats(id));
    }

    /**
     * Creates a flight and auto-generates its seat map — capacity 70 writes 70
     * {@code AVAILABLE} rows ({@code 1A…12D}).
     *
     * <p>The response reports {@code bookedSeats: 0} without querying, because a
     * flight that was just created cannot have one.
     */
    @PostMapping("/admin/flights")
    @PreAuthorize("hasRole('ADMIN')")
    public FlightResponse create(@Valid @RequestBody FlightRequest request) {
        return FlightResponse.of(flightService.createFlight(request), 0L);
    }

    /**
     * Replaces a flight's fields, resizing its seat map if the capacity changed.
     * Shrinking below the seats already sold is a 409.
     */
    @PutMapping("/admin/flights/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public FlightResponse update(@PathVariable int id,
                                 @Valid @RequestBody FlightRequest request) {
        return FlightResponse.of(flightService.updateFlight(id, request),
                flightService.bookedSeats(id));
    }

    /**
     * The live seat map — the module's only public, unauthenticated read.
     *
     * <p>{@code available} is computed from the seat rows on every call; nothing
     * about it is stored, which is the teacher-flagged availability rule expressed as
     * an endpoint.
     */
    @GetMapping("/flights/{id}/seats")
    public SeatMapResponse seatMap(@PathVariable int id) {
        Flight flight = flightService.getFlight(id);
        return SeatMapResponse.of(flight, flightService.seatMap(id));
    }

    /**
     * Deletes an unsold flight and its seat map — 409 with "set it Inactive
     * instead" if it has bookings.
     */
    @DeleteMapping("/admin/flights/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable int id) {
        flightService.deleteFlight(id);
        return ResponseEntity.noContent().build();
    }
}
