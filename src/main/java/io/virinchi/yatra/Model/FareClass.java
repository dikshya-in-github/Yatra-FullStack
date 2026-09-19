package io.virinchi.yatra.Model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * The six fare classes a seat can be sold in, and the two facts that make them a
 * class rather than a label: <b>what the delta adds to the flight's base fare</b>,
 * and <b>whether the ticket is refundable</b>.
 *
 * <p><b>Why this exists now.</b> Until the storefront was cut over, the fare
 * classes lived only in {@code mock-data.js}'s {@code FARE_CLASSES} array, and the
 * backend knew them only as free text: {@code booking.fare_class} stored whatever
 * string a browser sent, and {@code BookingService.payable} priced <i>every</i>
 * class at the flight row's base fare. That was invisible while the whole wizard
 * was mock — the mock priced its own total — but the moment {@code searchFlight.js}
 * and {@code booking.js} moved to this API the two halves disagreed by up to
 * {@code 5,000 × passengers}: the page quoted A Class at base + 4,000 and the
 * server stored a charge of base. The page that quotes a price and the server that
 * charges one have to read the same table, so the table moved here.
 *
 * <p><b>An enum, not a table.</b> The deltas are policy, the same way the flight
 * table's {@code seat_capacity} is a column but {@code available = capacity −
 * booked} is a rule: nothing in this phase lets an admin edit a class, and a
 * {@code fare_class} table with six rows that only this enum can write would be a
 * fifth join on every booking read for no gain. If a later phase makes the deltas
 * editable, this is the type to replace with an entity — the {@code label} values
 * are already the strings {@code booking.fare_class} holds and the demo seed
 * writes ("E Class", "Y Class"), so such a migration has nothing to translate.
 *
 * <p><b>The labels and deltas are the mock's own</b> ({@code mock-data.js:232}),
 * kept verbatim so the storefront's six-pill row and the seeded bookings' class
 * names did not have to change when the data did. {@code E} is the zero-delta
 * class — the flight row's {@code fare} is an economy base fare and every other
 * class is that fare plus its delta, which is the relationship {@code Flight}'s
 * own comment records ("fare class ko delta yahi mathi thapinxa").
 *
 * <p><b>Money is {@code BigDecimal} throughout</b> (R5): the deltas are compared
 * and added as decimals, never doubles, and every price is scaled to 2 on the way
 * out — the same rule {@code BookingService.payable} and {@code PaymentService}
 * follow so the figure the gateway is asked for and the figure the page printed
 * are the same number.
 */
public enum FareClass {

    /** Economy — the base fare itself, and the class a blank request means. */
    E("E Class", "0", false),
    C("C Class", "1000", false),
    D("D Class", "2000", false),
    B("B Class", "3000", false),
    A("A Class", "4000", true),
    Y("Y Class", "5000", true);

    /** The class whose price is the flight row's own {@code fare}. */
    public static final FareClass DEFAULT = E;

    private final String label;
    private final BigDecimal delta;
    private final boolean refundable;

    FareClass(String label, String delta, boolean refundable) {
        this.label = label;
        this.delta = new BigDecimal(delta);
        this.refundable = refundable;
    }

    /** The page-facing name — what {@code booking.fare_class} stores. */
    public String getLabel() {
        return label;
    }

    /** The one-letter code the pill row and the report use. */
    public String getCode() {
        return name();
    }

    /** What this class adds to a flight's base fare. */
    public BigDecimal getDelta() {
        return delta;
    }

    public boolean isRefundable() {
        return refundable;
    }

    /**
     * What one seat costs in this class on that flight: the base fare plus this
     * class's delta, at currency scale.
     *
     * <p>{@code setScale(2, HALF_UP)} matches {@code BookingService.payable}, and it
     * is not a formality: {@code 8299.99 + 1000} is {@code 9299.99} either way, but
     * the multiply in {@code payable} is where an unscaled {@code BigDecimal} would
     * carry a third decimal into the gateway's {@code total_amount}.
     */
    public BigDecimal priceFor(BigDecimal baseFare) {
        return baseFare.add(delta).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Resolves a class from what a caller sent — the label ({@code "E Class"}), the
     * bare code ({@code "E"}), or the code with padding/case noise.
     *
     * <p>Both spellings are accepted on purpose: {@code booking.js} writes the
     * <i>label</i> into {@code yatra_selected_flight.flightClass} (that is what the
     * page's pill shows, and it is what ends up in the {@code booking} column),
     * while Postman and the walk files are easier to write with the code.
     *
     * <p>{@link Optional#empty()} for a value that matches nothing — never a silent
     * fallback to {@code E}, because a request naming a class this server does not
     * sell is a request to charge a price that does not exist.
     */
    public static Optional<FareClass> find(String value) {
        if (value == null) {
            return Optional.empty();
        }

        String wanted = value.trim();
        if (wanted.isEmpty()) {
            return Optional.empty();
        }

        for (FareClass fareClass : values()) {
            if (fareClass.label.equalsIgnoreCase(wanted) || fareClass.name().equalsIgnoreCase(wanted)) {
                return Optional.of(fareClass);
            }
        }
        return Optional.empty();
    }
}
