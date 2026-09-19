package io.virinchi.yatra.Dto;

import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Model.FareClass;
import io.virinchi.yatra.Model.Flight;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * The storefront's flight search — {@code GET /api/flights/search?origin=&
 * destination=&date=&passengers=}.
 *
 * <p><b>The shape is the mock's, taken from the page rather than invented.</b>
 * {@code searchFlight.js} has rendered this response since item 16; its card, its
 * date strip, its policy modal and its Confirm Flight modal all read these keys
 * ({@code origin.label}, {@code farePolicies.nonRefundable}, {@code flights[].fareOptions}
 * …). Serving a different shape under the same path would mean rewriting a page
 * that is already correct, so every key here is one the page already reads, spelled
 * the way it spells it. What is <i>not</i> copied from the mock is the data: the
 * flights are {@code flight} rows, the airline is the joined {@code airline}, the
 * price is the row's own fare, and the fare-class deltas come from
 * {@link FareClass}.
 *
 * <p><b>What the mock invented and this does not.</b>
 * <ul>
 *   <li><b>Flight numbers.</b> The mock derived them from the date
 *       ({@code al.iata + " " + (951 + i * 7 + dow)}) — which is the defect that
 *       blocked this cut-over, because the flight a customer picked was not a row
 *       and {@code POST /api/bookings} resolves the flight <i>by number</i>. Every
 *       {@code flightNo} here names a real {@code flight} row, and {@code id} rides
 *       along for callers that want the seat map.</li>
 *   <li><b>{@code comparePrice}.</b> The mock's strike-through price was
 *       {@code base + 177} — a number with nothing behind it. There is no "was"
 *       price in the data, so this response has no such field and the card shows no
 *       strike-through. {@code isLowest} stays, and it is real: the row with the
 *       cheapest fare for that route and date.</li>
 *   <li><b>{@code baggage} / {@code handCarry}.</b> Also invented ("15kg"/"5kg"),
 *       and the card's markup prints those figures itself — so they would have been
 *       a second source of truth that nothing reads.</li>
 * </ul>
 *
 * <p><b>{@code seatsAvailable} is exposed and not filtered on.</b> It is the
 * teacher-flagged rule computed live from the {@code seat} table
 * ({@code capacity − COUNT(BOOKED)}), in one query for the whole page of rows. A
 * sold-out flight is <i>still listed</i>: hiding it would be a search rule the card
 * has no way to explain, and the booking itself refuses the seat with its own 409,
 * which is the honest place for that answer. A future pass that wants "no seats"
 * rendered on the card has the figure already.
 */
public record StorefrontSearchResponse(

        Endpoint origin,
        Endpoint destination,
        String date,
        int passengers,
        FarePolicies farePolicies,
        List<FlightCard> flights
) {

    /** {@code HH:mm} — the format the page's own {@code to12()} splits. */
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /**
     * One end of the route.
     *
     * <p>{@code label} is the all-caps city the mock's own {@code AIRPORT_LABELS}
     * used, because the page prints it in the results heading
     * ("Select your preferred flight from KATHMANDU to POKHARA") — a presentation
     * field next to the raw {@code city} and {@code airport}, not a replacement for
     * them. An unknown code falls back to the code itself rather than to an empty
     * string, so a route that no longer exists still titles honestly.
     */
    public record Endpoint(String code, String city, String airport, String label) {

        public static Endpoint of(String requestedCode, Destination row) {
            String code = requestedCode == null ? "" : requestedCode.toUpperCase();
            if (row == null) {
                return new Endpoint(code, code, "", code);
            }

            String city = row.getCity() == null ? code : row.getCity();
            return new Endpoint(
                    row.getCode() == null ? code : row.getCode(),
                    city,
                    row.getAirport() == null ? "" : row.getAirport(),
                    city.toUpperCase(java.util.Locale.ROOT));
        }
    }

    /**
     * One flight card.
     *
     * <p>{@code airline} carries only what the card draws — name, IATA code and the
     * logo path. The logo is the same {@code /api/airlines/{id}/logo} route the
     * storefront already uses, so a carrier without an uploaded file renders the
     * code chip the card falls back to (the {@code onerror} the markup has always
     * had).
     *
     * <p>{@code baseFare} is the flight row's own {@code fare} and
     * {@code fareOptions[0].price} is that same figure priced as {@link FareClass#E} —
     * the zero-delta class. They agree by construction, which is what makes the
     * card's headline price and the pill the page pre-selects the same number.
     */
    public record FlightCard(
            int id,
            String flightNo,
            AirlineView airline,
            String from,
            String to,
            String date,
            String depart,
            String arrive,
            long durationMinutes,
            BigDecimal baseFare,
            List<FareOption> fareOptions,
            long seatsAvailable,
            boolean isLowest
    ) {
    }

    /** The three fields the card's airline block reads. */
    public record AirlineView(String name, String code, String logo) {
    }

    /**
     * One sellable class on one flight.
     *
     * <p>{@code delta} is reported alongside {@code price} so a page can show "base +
     * 4,000" without re-deriving the policy, and {@code code} is the one-letter class
     * the report tables use. {@code refundable} is the class's own rule — which is
     * also the rule {@code BookingService} now stores on the booking instead of
     * trusting the flag the browser sent with it.
     */
    public record FareOption(String code, String label, BigDecimal delta, BigDecimal price,
                             boolean refundable) {
    }

    /**
     * The cancellation policy text the card's modal prints.
     *
     * <p><b>Copy, served because the page reads it from here.</b> The mock answered
     * these two lists and {@code searchFlight.js}'s {@code openPolicy()} picks one by
     * the selected class's {@code refundable} flag; a real response that omitted them
     * would open an empty modal. The wording is the spec's own cancellation terms and
     * is the same on every flight — it is policy, not data about a row — so it lives
     * here as constants rather than in a table nothing would ever vary. A fare-rules
     * phase that makes these per-class terms belongs in this record's place.
     */
    public record FarePolicies(List<String> nonRefundable, List<String> refundable) {

        public static FarePolicies standard() {
            return new FarePolicies(
                    List.of("Cancellation is not available for this fare.",
                            "Only PSC (Airport Tax) will be refunded.",
                            "Date changes are not permitted on this fare."),
                    List.of("Cancellation allowed up to 2 hours before departure — only a 10% fee "
                                    + "(33.33% within 11 hours).",
                            "Free date changes up to 2 hours before departure (fare difference may apply).",
                            "Unutilized PSC (Airport Tax) is always refundable on request."));
        }
    }

    /**
     * Builds the response from rows the caller has already loaded.
     *
     * <p>{@code bookedByFlightId} is the one-query booked-seat count
     * ({@code FlightService.bookedSeatsFor}), so a search of six flights costs the
     * same number of statements as one — the reason the count is passed in rather
     * than asked per card.
     */
    public static StorefrontSearchResponse of(String originCode, Destination origin,
                                              String destinationCode, Destination destination,
                                              LocalDate date, int passengers,
                                              List<Flight> rows, Map<Integer, Long> bookedByFlightId) {

        BigDecimal cheapest = rows.stream()
                .map(Flight::getFare)
                .filter(fare -> fare != null)
                .min(BigDecimal::compareTo)
                .orElse(null);

        return new StorefrontSearchResponse(
                Endpoint.of(originCode, origin),
                Endpoint.of(destinationCode, destination),
                date == null ? "" : date.toString(),
                passengers,
                FarePolicies.standard(),
                rows.stream().map(flight -> card(flight, bookedByFlightId, cheapest)).toList());
    }

    private static FlightCard card(Flight flight, Map<Integer, Long> bookedByFlightId,
                                   BigDecimal cheapest) {
        BigDecimal base = flight.getFare() == null ? BigDecimal.ZERO : flight.getFare();
        long booked = bookedByFlightId.getOrDefault(flight.getId(), 0L);

        return new FlightCard(
                flight.getId(),
                flight.getFlightNo() == null ? "" : flight.getFlightNo(),
                airline(flight),
                flight.getOrigin() == null || flight.getOrigin().getCode() == null
                        ? "" : flight.getOrigin().getCode(),
                flight.getDestination() == null || flight.getDestination().getCode() == null
                        ? "" : flight.getDestination().getCode(),
                flight.getFlightDate() == null ? "" : flight.getFlightDate().toString(),
                flight.getDepartTime() == null ? "" : TIME.format(flight.getDepartTime()),
                flight.getArriveTime() == null ? "" : TIME.format(flight.getArriveTime()),
                durationMinutes(flight),
                base,
                fareOptions(base),
                Math.max(0, flight.getSeatCapacity() - booked),
                cheapest != null && cheapest.compareTo(base) == 0);
    }

    private static AirlineView airline(Flight flight) {
        if (flight.getAirline() == null) {
            return new AirlineView("", "", "");
        }
        String code = flight.getAirline().getIata() == null ? "" : flight.getAirline().getIata();
        return new AirlineView(
                flight.getAirline().getName() == null ? "" : flight.getAirline().getName(),
                code,
                /* The route the airline module already serves — `logo` itself is a
                   stored path that a carrier without a file leaves null, and an empty
                   src is what the card's onerror fallback is for. */
                flight.getAirline().getLogo() == null ? "" : flight.getAirline().getLogo());
    }

    /**
     * The flight's duration in minutes, from its own times.
     *
     * <p>The mock computed it from the schedule strings, and the card prints
     * {@code "Flight Duration: N min"} from the two times again. Both are the same
     * arithmetic on the same columns, so this figure is reported for callers that
     * want it (the walk files, Postman) rather than to change what the card shows.
     */
    private static long durationMinutes(Flight flight) {
        if (flight.getDepartTime() == null || flight.getArriveTime() == null) {
            return 0;
        }
        return java.time.Duration.between(flight.getDepartTime(), flight.getArriveTime()).toMinutes();
    }

    /**
     * Every class this server sells, priced on that flight — in the enum's own
     * order, which is the order of the pill row ({@code E C D B A Y}) and the order
     * of the mock's array.
     */
    private static List<FareOption> fareOptions(BigDecimal baseFare) {
        return java.util.Arrays.stream(FareClass.values())
                .map(fareClass -> new FareOption(
                        fareClass.getCode(),
                        fareClass.getLabel(),
                        fareClass.getDelta(),
                        fareClass.priceFor(baseFare),
                        fareClass.isRefundable()))
                .toList();
    }
}
