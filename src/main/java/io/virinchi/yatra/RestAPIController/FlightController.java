package io.virinchi.yatra.RestAPIController;

import io.virinchi.yatra.Dto.FlightListResponse;
import io.virinchi.yatra.Dto.FlightRequest;
import io.virinchi.yatra.Dto.FlightResponse;
import io.virinchi.yatra.Dto.SeatMapResponse;
import io.virinchi.yatra.Dto.StorefrontSearchResponse;
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
 * <p><b>Two public reads, both deliberate.</b> The seat map ({@code GET
 * /api/flights/{id}/seats}, Phase 6) exists because a booking page has to show the
 * cabin to signed-out visitors, and the search ({@code GET /api/flights/search},
 * fix-plan §10) because the storefront's first page is public by definition. Both
 * are the consequence R12 flagged when the {@code /api/flights/**} GET permit went
 * in early: the permit is a prefix rule, so these paths inherit it. Keeping them
 * public is the choice — a 401 here fails exactly the way the airline logos did (the
 * page would draw an empty cabin or an empty result list rather than an error), and
 * neither exposes anything a browsing customer cannot already see: the map is seat
 * statuses and the search is flights, carriers, times and prices.
 *
 * <p><b>The storefront search was built here, and it is why this class's own doc
 * used to say it could not be.</b> The note that stood in this spot said the route
 * was committed in {@code assets/js/api.js} but could not be served because its
 * response carries fare classes and {@code fareOptions} — data "the fare-class
 * phase" owned. That was true of the response <i>shape</i> and wrong about the
 * blocker: what stopped the cut-over was that the mock <b>invented its flight
 * numbers from the date</b>, so the flight a customer picked was not a row and
 * {@code POST /api/bookings}, which resolves the flight by number, would have
 * 404'd. The deltas themselves were never a phase-sized problem — they are six
 * constants, and they now live in {@link io.virinchi.yatra.Model.FareClass}, which
 * both this response and {@code BookingService}'s pricing read. The permit being in
 * place a phase early is the part that held up: this endpoint needed no
 * {@code SecurityConfig} change (risk R8, paid off).
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
     * The storefront's flight search — the page-level read behind
     * {@code searchFlight.html} (fix-plan §10).
     *
     * <p><b>Public, and on the path the page has always called.</b>
     * {@code searchFlight.js} has requested {@code /api/flights/search} since item 16
     * and rendered the answer; that call used to resolve inside {@code mock-data.js}.
     * Pointing the page at this API is therefore an allow-list entry, not a rewrite —
     * the response reproduces the mock's keys (see
     * {@link io.virinchi.yatra.Dto.StorefrontSearchResponse}) over real rows.
     *
     * @param origin      departure airport code; unknown or blank returns no flights
     * @param destination arrival airport code; the same code as {@code origin} also
     *                    returns no flights (a route to itself is not a route)
     * @param date        an exact travel date; omitted means today, because a
     *                    customer's search always has a day and a null date would
     *                    have to mean "any", which is not a question this page asks
     * @param passengers  echoed back to size the page's own header; it filters nothing
     */
    @GetMapping("/flights/search")
    public StorefrontSearchResponse search(@RequestParam(required = false) String origin,
                                           @RequestParam(required = false) String destination,
                                           @RequestParam(required = false)
                                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                           @RequestParam(required = false) Integer passengers) {
        return flightService.searchStorefront(origin, destination, date, passengers);
    }

    /**
     * The live seat map — the module's other public, unauthenticated read.
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
