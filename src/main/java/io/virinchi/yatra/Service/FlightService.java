package io.virinchi.yatra.Service;

import io.virinchi.yatra.Dto.FlightRequest;
import io.virinchi.yatra.Dto.StorefrontSearchResponse;
import io.virinchi.yatra.Exception.ConflictException;
import io.virinchi.yatra.Exception.DuplicateResourceException;
import io.virinchi.yatra.Exception.ResourceNotFoundException;
import io.virinchi.yatra.Exception.ValidationException;
import io.virinchi.yatra.Model.Airline;
import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Model.Flight;
import io.virinchi.yatra.Model.Seat;
import io.virinchi.yatra.Repository.AirlineRepository;
import io.virinchi.yatra.Repository.BookingRepository;
import io.virinchi.yatra.Repository.DestinationRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import io.virinchi.yatra.Repository.SeatRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Flight lifecycle — the admin module's reads and writes, the seat map, and the
 * deletes.
 *
 * <h2>The seat map is generated, never entered</h2>
 * <p>Creating a flight of capacity 70 writes <b>70 {@code Seat} rows</b>, all
 * {@code AVAILABLE}, numbered {@code 1A…12D}. The capacity figure is the only
 * thing an admin types; the rows behind it are derived. That is what makes
 * {@code available = seatCapacity − COUNT(BOOKED seats)} a computable rule rather
 * than a number somebody has to remember to update — nothing in this class ever
 * writes a "booked" or "available" total.
 *
 * <h2>Deletes</h2>
 * <p>A flight is the parent of two things: its {@code Seat} rows and any
 * {@code Booking} that references it. They need opposite treatment, and the
 * difference is a business rule, not a technical one:
 *
 * <ul>
 *   <li><b>Seats</b> only mean something inside their flight, so they go with
 *       it. {@code Flight.seats} is {@code cascade = ALL, orphanRemoval = true},
 *       but the cascade alone is <b>not</b> enough: if the seats are already
 *       managed (the seat map is part of the admin screen), Hibernate's pre-flush
 *       transient-reference check throws {@code TransientPropertyValueException}
 *       before the cascade ever runs. Deleting them explicitly first is the
 *       ordering the probe proved works.</li>
 *   <li><b>Bookings</b> are customer contracts. Deleting a sold flight would
 *       orphan them, so this refuses with 409 and points at the status toggle the
 *       admin page already has. The FK has no cascade either, so the database
 *       backs the same rule for any code path that skips this service.</li>
 * </ul>
 *
 * <h2>Paging and ordering</h2>
 * <p>Every read passes an <b>explicit</b> sort, and the caller cannot choose an
 * arbitrary one — {@link #sortFor(String)} whitelists seven flight properties.
 * Paging without {@code ORDER BY} lets a row appear on two pages or vanish
 * between them (InnoDB/TiDB promise no order), which is visible at the page size
 * the admin table uses.
 */
@Service
public class FlightService {

    /** The two seat states. Phase 6 flips these; nothing else writes them. */
    private static final String AVAILABLE = "AVAILABLE";
    private static final String BOOKED = "BOOKED";

    /**
     * Cabin letters for auto-generated seat numbers, six per row. Index 0 is
     * {@code 1A}, so capacity 70 ends at {@code 12D} — eleven full rows plus four
     * seats — and growing to 72 finishes that row ({@code 12E}, {@code 12F}) before
     * 74 starts {@code 13A}.
     */
    private static final String SEAT_LETTERS = "ABCDEF";

    /**
     * The sort properties a caller may ask for, keyed by the name the <b>page</b>
     * uses (its record's keys) — anything not listed falls back to {@code id}, so
     * no request can order by a column that does not exist.
     */
    private static final Map<String, String> SORTABLE = Map.of(
            "id", "id",
            "no", "flightNo",
            "date", "flightDate",
            "dep", "departTime",
            "arr", "arriveTime",
            "fare", "fare",
            "seats", "seatCapacity",
            "status", "status");

    private static final String DEFAULT_SORT = "id";

    private final FlightRepository flights;
    private final BookingRepository bookings;
    private final SeatRepository seats;
    private final AirlineRepository airlines;
    private final DestinationRepository destinations;

    public FlightService(FlightRepository flights, BookingRepository bookings, SeatRepository seats,
                         AirlineRepository airlines, DestinationRepository destinations) {
        this.flights = flights;
        this.bookings = bookings;
        this.seats = seats;
        this.airlines = airlines;
        this.destinations = destinations;
    }

    /* ------------------------------------------------------------------ *
     *  Reads                                                             *
     * ------------------------------------------------------------------ */

    /**
     * Every matching flight, sorted — the shape the pages consume today, where
     * {@code admin-flights.js} filters and pages its table client-side over the
     * full list.
     *
     * @param search matches the flight number, either airport code, or the
     *               airline's name, case-insensitively
     * @param date   an exact travel date; {@code null} means "any date", which
     *               also matches flights that have no date (see the note on
     *               {@code FlightRequest#date})
     */
    @Transactional(readOnly = true)
    public List<Flight> listAll(String search, Integer airlineId, String from, String to,
                                LocalDate date, String status, String sort) {
        return flights.searchAll(like(search), airlineId, code(from), code(to), date,
                blankToNull(status), sortFor(sort));
    }

    /** One page of matching flights, plus the counts needed to walk the rest. */
    @Transactional(readOnly = true)
    public Page<Flight> listPage(String search, Integer airlineId, String from, String to,
                                 LocalDate date, String status, String sort,
                                 int page, int size) {
        PageRequest request = Paging.request(page, size, sortFor(sort));

        return flights.searchPage(like(search), airlineId, code(from), code(to), date,
                blankToNull(status), request);
    }

    /**
     * @throws ResourceNotFoundException 404 — no such flight
     */
    @Transactional(readOnly = true)
    public Flight getFlight(int flightId) {
        return flights.findById(flightId)
                .orElseThrow(() -> ResourceNotFoundException.of("Flight", flightId));
    }

    /**
     * The booked-seat count for one flight — computed from the {@code seat} table
     * every time, never read from a stored total.
     */
    @Transactional(readOnly = true)
    public long bookedSeats(int flightId) {
        return seats.countByFlightIdAndStatus(flightId, BOOKED);
    }

    /**
     * Booked-seat counts for a whole page in one query.
     *
     * <p>Guarded for the empty list because {@code in ()} is not valid SQL — and
     * an empty page is the normal result of a filter that matches nothing.
     */
    @Transactional(readOnly = true)
    public Map<Integer, Long> bookedSeatsFor(List<Flight> rows) {
        if (rows.isEmpty()) {
            return Map.of();
        }

        List<Integer> ids = rows.stream().map(Flight::getId).toList();
        Map<Integer, Long> counts = new HashMap<>();
        for (SeatRepository.BookedCount count : seats.countByFlightIdInAndStatus(ids, BOOKED)) {
            counts.put(count.getFlightId(), count.getTotal());
        }
        return counts;
    }

    /* ------------------------------------------------------------------ *
     *  The storefront's search (fix-plan §10)                             *
     * ------------------------------------------------------------------ */

    /**
     * The storefront's search — {@code GET /api/flights/search} (fix-plan §10).
     *
     * <p><b>The route the wizard's first page calls, built over real rows.</b> Until
     * this existed the search answered from {@code mock-data.js}, which generated its
     * flights per date and <i>invented</i> their numbers
     * ({@code al.iata + " " + (951 + i * 7 + dow)}). That is why this was the
     * cut-over's blocker rather than one more page to wire: the customer selected a
     * flight that was not a row, and {@code POST /api/bookings} resolves the flight by
     * its number — it would have 404'd on a perfectly valid-looking search result.
     *
     * <p><b>Nothing here is derived from the date.</b> The flights are the
     * {@code flight} rows for that route on that date, active ones only, ordered by
     * departure in SQL. {@code passengers} is echoed back because the page sizes its
     * own header and confirmation from it; it does not filter anything, because a
     * flight is not sold per-party — the seats are claimed one at a time at booking
     * time, and availability is reported per flight
     * ({@code StorefrontSearchResponse.seatsAvailable}).
     *
     * <p><b>An empty answer is a normal answer, not an error.</b> A route with no
     * flight that day, a code that is not a destination, or the same code on both
     * sides all return the response with an empty {@code flights} list, exactly as the
     * mock did — the page's empty state is built for it, and 404-ing a search the
     * customer can fix by changing the date would turn a legible empty page into an
     * error dialog. The endpoints still come back (with the code as the fallback name)
     * so the heading can name the route that had nothing.
     */
    @Transactional(readOnly = true)
    public StorefrontSearchResponse searchStorefront(String origin, String destination,
                                                     LocalDate date, Integer passengers) {
        String from = code(origin);
        String to = code(destination);
        LocalDate on = date == null ? LocalDate.now() : date;
        int pax = passengers == null || passengers < 1 ? 1 : passengers;

        Destination originRow = from == null ? null : destinations.findByCode(from).orElse(null);
        Destination destinationRow = to == null ? null : destinations.findByCode(to).orElse(null);

        List<Flight> rows = (from == null || to == null || from.equals(to))
                ? List.of()
                : flights.searchStorefront(from, to, on);

        return StorefrontSearchResponse.of(from, originRow, to, destinationRow, on, pax, rows,
                bookedSeatsFor(rows));
    }

    /**
     * The live seat map for one flight, in seat-number order —
     * {@code GET /api/flights/{id}/seats}.
     *
     * <p>Ordered explicitly because a {@code SELECT} promises no order and the
     * caller draws a grid: id order <i>is</i> seat-number order, because every row
     * this class writes is appended in index order ({@code 1A, 1B … 12D}), and a
     * capacity shrink removes the highest ids and a re-grow appends higher ones, so
     * the sequence survives both.
     *
     * <p>Statuses are read, never derived — the booking path is the only thing that
     * flips them ({@code BookingService}), and it does so through the same
     * {@code seat} table this returns.
     */
    @Transactional(readOnly = true)
    public List<Seat> seatMap(int flightId) {
        return seats.findByFlightId(flightId).stream()
                .sorted(Comparator.comparingInt(Seat::getId))
                .toList();
    }

    /* ------------------------------------------------------------------ *
     *  Writes                                                            *
     * ------------------------------------------------------------------ */

    /**
     * Creates a flight and its seat map.
     *
     * @throws DuplicateResourceException 409 {@code FLIGHT_NO_EXISTS} — the
     *                                   normalised number is taken
     * @throws ValidationException        400 — same origin and destination,
     *                                   arrival not after departure, or an airport
     *                                   code that is not in the destination table
     * @throws ResourceNotFoundException  404 — no such airline
     */
    @Transactional
    public Flight createFlight(FlightRequest request) {
        String flightNo = normalizeFlightNo(request.no());

        if (flights.findByFlightNo(flightNo).isPresent()) {
            throw DuplicateResourceException.flightNoExists(flightNo);
        }

        Flight flight = new Flight();
        apply(flight, request, flightNo);

        //save() assigns the identity id immediately, which the seat rows need.
        Flight saved = flights.save(flight);
        generateSeats(saved, request.seats());

        return saved;
    }

    /**
     * Replaces a flight's fields, keeping its seat map in step with the capacity
     * the admin set.
     *
     * @throws ResourceNotFoundException  404 — no such flight or airline
     * @throws DuplicateResourceException 409 {@code FLIGHT_NO_EXISTS}
     * @throws ConflictException          409 {@code CAPACITY_BELOW_BOOKED_SEATS}
     */
    @Transactional
    public Flight updateFlight(int flightId, FlightRequest request) {
        Flight flight = getFlight(flightId);
        String flightNo = normalizeFlightNo(request.no());

        flights.findByFlightNo(flightNo)
                .filter(existing -> existing.getId() != flightId)
                .ifPresent(existing -> {
                    throw DuplicateResourceException.flightNoExists(flightNo);
                });

        apply(flight, request, flightNo);
        reconcileSeats(flight, request.seats());

        return flights.save(flight);
    }

    /**
     * Deletes an unsold flight and its seat map.
     *
     * @throws ResourceNotFoundException 404 — no such flight
     * @throws ConflictException         409 — the flight has bookings; set it
     *                                   Inactive instead of deleting it
     */
    @Transactional
    public void deleteFlight(int flightId) {
        Flight flight = getFlight(flightId);

        //Check pahile: booking hunxa vane DB le pani delete garna dindaina, tara
        //tyo raw FK error 500 banxa — admin lai 409 + "Inactive garne" path dinu parxa.
        long bookingCount = bookings.countByFlightId(flightId);
        if (bookingCount > 0) {
            throw ConflictException.flightHasBookings(flight.getFlightNo(), bookingCount);
        }

        //Delete order: seats pahile, flight paxi.
        seats.deleteAll(seats.findByFlightId(flightId));
        seats.flush();
        flights.delete(flight);
        flights.flush();
    }

    /* ------------------------------------------------------------------ *
     *  The seat map                                                      *
     * ------------------------------------------------------------------ */

    /** Writes a fresh seat map of {@code capacity} rows, all {@code AVAILABLE}. */
    private void generateSeats(Flight flight, int capacity) {
        List<Seat> map = new ArrayList<>(capacity);
        for (int index = 0; index < capacity; index++) {
            map.add(newSeat(flight, index));
        }
        seats.saveAll(map);
        seats.flush();
    }

    /**
     * Brings the seat map to {@code target} rows after a capacity change.
     *
     * <p>Three cases, in the order they must be checked:
     * <ol>
     *   <li><b>Shrinking below the sold count is refused</b> (409). The capacity
     *       is the number the available-seat sum starts from, so 50 seats on a
     *       flight with 60 bookings would report −10 free.</li>
     *   <li><b>Growing</b> appends seats, continuing the same numbering from the
     *       current row count — {@code 1A…12D} extends to {@code 12E}, {@code 12F}
     *       and only then to a 13th row.</li>
     *   <li><b>Shrinking</b> removes the highest-numbered <i>available</i> seats
     *       only, never a sold one. Case 1 guarantees enough available rows exist,
     *       which is why the booked count is read before anything is deleted.</li>
     * </ol>
     */
    private void reconcileSeats(Flight flight, int target) {
        List<Seat> existing = seats.findByFlightId(flight.getId());
        int current = existing.size();

        if (current == target) {
            return;
        }

        long booked = seats.countByFlightIdAndStatus(flight.getId(), BOOKED);
        if (target < booked) {
            throw ConflictException.capacityBelowBookedSeats(flight.getFlightNo(), booked, target);
        }

        if (target > current) {
            List<Seat> added = new ArrayList<>(target - current);
            for (int index = current; index < target; index++) {
                added.add(newSeat(flight, index));
            }
            seats.saveAll(added);
        } else {
            //Highest id = most recently appended = highest seat number, because
            //every seat this class ever writes is appended in index order.
            List<Seat> surplus = existing.stream()
                    .filter(seat -> !BOOKED.equals(seat.getStatus()))
                    .sorted(Comparator.comparingInt(Seat::getId).reversed())
                    .limit(current - target)
                    .toList();
            seats.deleteAll(surplus);
        }

        seats.flush();
    }

    private Seat newSeat(Flight flight, int index) {
        Seat seat = new Seat();
        seat.setFlight(flight);
        seat.setSeatNumber(seatNumber(index));
        seat.setStatus(AVAILABLE);
        return seat;
    }

    /** Rows of six: index 0 → {@code "1A"}, index 69 → {@code "12D"}. */
    static String seatNumber(int index) {
        int row = index / SEAT_LETTERS.length() + 1;
        char letter = SEAT_LETTERS.charAt(index % SEAT_LETTERS.length());
        return row + String.valueOf(letter);
    }

    /* ------------------------------------------------------------------ *
     *  Helpers                                                           *
     * ------------------------------------------------------------------ */

    /**
     * Applies a request onto an entity.
     *
     * <p>The business rules live here rather than in the DTO because they compare
     * two fields: "arrival after departure" and "origin is not the destination"
     * are things Bean Validation cannot state on one field. Both mirror the checks
     * {@code admin-flights.js} already performs client-side, with the same wording,
     * so the API and the page tell the user the same thing — and the page's
     * client-side guard is no longer the only thing enforcing them.
     */
    private void apply(Flight flight, FlightRequest request, String flightNo) {
        if (!request.arr().isAfter(request.dep())) {
            throw new ValidationException(
                    "Arrival must be after departure (same-day domestic flights).");
        }

        String from = code(request.from());
        String to = code(request.to());
        if (from.equals(to)) {
            throw new ValidationException("Origin and destination cannot be the same.");
        }

        Airline airline = airlines.findById(request.airlineId())
                .orElseThrow(() -> ResourceNotFoundException.of("Airline", request.airlineId()));
        Destination origin = resolveAirport(from);
        Destination destination = resolveAirport(to);

        flight.setFlightNo(flightNo);
        flight.setAirline(airline);
        flight.setOrigin(origin);
        flight.setDestination(destination);
        //Deliberately overwritten with whatever the request carried — including
        //null. The admin form has no date field yet, so this is normally null, and
        //defaulting it to "today" would make a date-filtered search match a day the
        //admin never scheduled.
        flight.setFlightDate(request.date());
        flight.setDepartTime(request.dep());
        flight.setArriveTime(request.arr());
        flight.setAircraft(blankToNull(request.aircraft()));
        flight.setFare(request.fare());
        flight.setSeatCapacity(request.seats());
        flight.setStatus(statusOrDefault(request.status()));
    }

    /**
     * Resolves an airport code to a destination row.
     *
     * <p>Case-insensitively, because {@code destination.code} is
     * {@code utf8mb4_bin} — the lookup, not the collation, is what makes
     * {@code "ktm"} work. A missing airport is a 400 with its own code rather than
     * a raw FK failure at flush time, which would surface as a 500 naming a
     * constraint instead of the airport the admin mistyped.
     */
    private Destination resolveAirport(String code) {
        return destinations.findByCodeIgnoreCase(code)
                .orElseThrow(() -> new ValidationException(
                        "UNKNOWN_AIRPORT", "No airport exists with code " + code + "."));
    }

    /**
     * Canonical form of a flight number, matching what the page sends: uppercase,
     * spaces collapsed to one, trimmed.
     *
     * <p>This is also what makes the uniqueness check real. {@code flight_no} is
     * {@code utf8mb4_bin}, so {@code "u4 951"} and {@code "U4 951"} are two
     * different rows to the database — normalising first is the only thing
     * stopping the same flight existing twice under different casing.
     */
    private static String normalizeFlightNo(String flightNo) {
        return String.valueOf(flightNo == null ? "" : flightNo)
                .trim()
                .replaceAll("\\s+", " ")
                .toUpperCase(Locale.ROOT);
    }

    /** Uppercase airport code, or {@code null} for "no filter". */
    private static String code(String value) {
        String trimmed = String.valueOf(value == null ? "" : value).trim();
        return trimmed.isEmpty() ? null : trimmed.toUpperCase(Locale.ROOT);
    }

    /**
     * A {@code LIKE} pattern for the free-text search, or {@code null} for "no
     * filter".
     *
     * <p>Null — not {@code "%%"} — because the query guards this clause with
     * {@code :search is null}. An empty term folded into {@code "%%"} would sit in
     * an {@code OR} and match every row, quietly turning the airline, route, date
     * and status filters off; that is the bug this shape exists to prevent.
     */
    private static String like(String search) {
        String term = String.valueOf(search == null ? "" : search).trim().toLowerCase(Locale.ROOT);
        return term.isEmpty() ? null : "%" + term + "%";
    }

    private static String statusOrDefault(String status) {
        return status == null || status.isBlank() ? "Active" : status.trim();
    }

    private static String blankToNull(String value) {
        String trimmed = String.valueOf(value == null ? "" : value).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Resolves a requested sort to a whitelisted property, always finishing with
     * {@code id} so rows that share a status or a date still page in a stable
     * order.
     */
    private static Sort sortFor(String requested) {
        String key = String.valueOf(requested == null ? "" : requested).trim().toLowerCase(Locale.ROOT);
        String property = SORTABLE.getOrDefault(key, DEFAULT_SORT);

        return property.equals(DEFAULT_SORT)
                ? Sort.by(Sort.Order.asc(property))
                : Sort.by(Sort.Order.asc(property), Sort.Order.asc(DEFAULT_SORT));
    }
}
