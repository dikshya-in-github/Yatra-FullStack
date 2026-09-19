package io.virinchi.yatra.Dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.util.List;

/**
 * The body of {@code POST /api/bookings} — exactly what {@code booking.js} posts
 * at wizard step 2, and the source of the seat hold the rest of the flow depends
 * on.
 *
 * <p><b>Shape taken from the page, not invented.</b> {@code booking.js} posts
 * {@code { contact, passengers, flight, amount }}, where {@code flight} is the
 * record it read out of {@code sessionStorage.yatra_selected_flight}. Those keys
 * are kept verbatim (and the nested field names with them — the page's passenger
 * object has {@code type}, the entity's column is {@code passenger_type}, so the
 * mapping happens once in {@link io.virinchi.yatra.Service.BookingService}
 * instead of forcing a page change). {@code ignoreUnknown} keeps a future form
 * field from breaking the endpoint.
 *
 * <p><b>Three things the request carries that the backend deliberately does not
 * trust:</b>
 * <ol>
 *   <li><b>{@code amount}.</b> Accepted and ignored. The payable total is computed
 *       server-side from the <i>flight row's</i> fare <b>plus the selected fare
 *       class's delta</b> × passenger count, because a number that arrives in a JSON
 *       body is a number a browser can edit. The response returns the figure that was
 *       actually stored, so the caller can see the difference rather than guess.
 *       (This note used to say every class priced at the base fare, "the fare-class
 *       phase's" — true until §10 wired the storefront, at which point the page quoted
 *       one price and the server charged another. The deltas live in
 *       {@link io.virinchi.yatra.Model.FareClass} now, and both the search response
 *       and {@code BookingService} read them from there.)</li>
 *   <li><b>{@code flight.pricePerPassenger} / {@code totalPrice}.</b> The same
 *       rule — informational, ignored for pricing.</li>
 *   <li><b>{@code flight.refundable}.</b> Also not read: refundability is the fare
 *       class's own rule ({@code FareClass.isRefundable()}) and is stored from
 *       there, because the flag arrives in the body from the pill the page drew. A
 *       request claiming a non-refundable class was refundable would otherwise be
 *       recorded as one — and on a page whose whole point is quoting the terms, the
 *       stored terms and the quoted terms have to be the same ones.</li>
 *   <li><b>{@code flight.flightClass}.</b> <i>Is</i> read, and is the one part of
 *       the selected-flight block that decides money: a blank class means the base
 *       economy fare, a name this server does not sell is a 400, and a known one
 *       prices the booking. See {@code BookingService.fareClass}.</li>
 *   <li><b>{@code flight.from} / {@code to}.</b> Not ignored: they are
 *       cross-checked against the flight row as a cheap staleness guard. A
 *       {@code yatra_selected_flight} left in {@code sessionStorage} from an
 *       earlier search would otherwise book a real flight while the page shows a
 *       different route.</li>
 * </ol>
 *
 * <p><b>Which flight.</b> {@code flight.flightNo} is what the page sends and it is
 * {@code UNIQUE} on the {@code flight} table, so it resolves the row on its own —
 * the page never carries an id for the selected flight.
 *
 * <p><b>{@code passengers[].seatNumber} is optional, and that is the whole
 * Postman checkpoint.</b> The wizard has no seat picker yet (the seat map is
 * Phase 6's endpoint, wired into a page in Phase 9), so the page sends no seat
 * numbers and the service assigns the lowest-numbered free seats. A caller that
 * <i>does</i> name a seat — Postman, or the future picker — gets exactly that seat
 * or a 409, never a silent substitute.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingRequest(

        @Valid
        @NotNull(message = "Contact details are required.")
        Contact contact,

        @Valid
        @NotEmpty(message = "At least one passenger is required.")
        List<Passenger> passengers,

        @Valid
        @NotNull(message = "The selected flight is required.")
        SelectedFlight flight,

        /* Accepted, never used to price the booking — see the class comment. */
        BigDecimal amount
) {

    /**
     * The contact block from the wizard's step-2 form.
     *
     * <p>{@code invoiceParty} / {@code panNo} / {@code isPassenger} are absorbed
     * and not stored: the {@code booking} table has no column for them, and adding
     * one to satisfy a payload is a schema change looking for a requirement.
     */
    public record Contact(
            String title,
            @NotBlank(message = "Contact first name is required.")
            String firstName,
            String middleName,
            @NotBlank(message = "Contact last name is required.")
            String lastName,
            @NotBlank(message = "Contact email is required.")
            @Email(message = "Enter a valid email address.")
            String email,
            @NotBlank(message = "Contact mobile number is required.")
            @Pattern(regexp = "[0-9+()\\s-]{7,20}", message = "Enter a valid mobile number.")
            String phone,
            String invoiceParty,
            String panNo,
            Boolean isPassenger
    ) {
    }

    /**
     * One passenger row.
     *
     * <p>{@code type} is the page's key for {@code ADT}/{@code CHD}; blank is
     * allowed and defaults to {@code ADT} in the service, so an older caller that
     * omits it is not rejected over something the entity can infer.
     */
    public record Passenger(
            String title,
            @NotBlank(message = "Every passenger needs a first name.")
            String firstName,
            String middleName,
            @NotBlank(message = "Every passenger needs a last name.")
            String lastName,
            String nationality,
            @Pattern(regexp = "(?i)ADT|CHD|", message = "Passenger type must be ADT or CHD.")
            String type,
            /* The wizard does not send this yet; Postman and the Phase 9 seat
               picker do. Null/blank means "assign me one". */
            @Pattern(regexp = "(?i)\\s*\\d{1,3}[A-F]\\s*", message = "Seat must look like \"12C\".")
            String seatNumber
    ) {
    }

    /**
     * The wizard's selected-flight record.
     *
     * <p>Only {@code flightNo} is load-bearing. The rest is documented here
     * because it is what the page actually sends, and the pricing fields are
     * deliberately ignored ({@link BookingRequest} explains why) while
     * {@code from}/{@code to} are used as a staleness check.
     *
     * <p><b>{@code airline} is a String, and the page has to send one.</b>
     * {@code searchFlight.js} carries the carrier as the object its cards render
     * ({@code {name, code, logo}}) because {@code eticket.js} draws the logo from it,
     * so {@code booking.js} posts <i>the name</i> out of that object instead of the
     * object itself. A mismatched type here does not degrade gracefully: Jackson
     * cannot bind an object to this field and answers 400 "Request body is missing or
     * is not valid JSON", which is what the first real POST to this endpoint did —
     * the mock never bound the payload to a DTO at all, so nothing caught it while
     * the wizard was mock. The value is informational: the booking's carrier is the
     * flight row's own {@code airline}.
     *
     * <p><b>{@code fromCity} / {@code toCity} are sent and not read.</b> §10 added
     * them so {@code booking.html} and {@code payment.html} can print the cities the
     * search response named rather than looking the codes up in the mock's airport
     * map. They ride in the selected-flight record because that is the one handoff
     * the wizard already has; the server's own route check uses {@code from}/{@code to}.
     * They are accepted because unknown keys are ignored ({@code ignoreUnknown} above).
     *
     * <p><b>{@code date} is a plain {@code String}, not a {@code LocalDate}, on
     * purpose.</b> {@code searchFlight.js} commits it as ISO
     * ({@code iso(selected)}), so a {@code LocalDate} would parse today — but the
     * backend does not read it (the flight row is resolved by number and its own
     * date is what counts), and binding an unused field to a date type means a
     * single display-formatted value, or an {@code ""} from a record written by an
     * older page, could 400 a booking the customer has already filled in. Accepted,
     * never parsed, never used to price or resolve anything.
     */
    public record SelectedFlight(
            @NotBlank(message = "The flight number is required.")
            String flightNo,
            String from,
            String to,
            String date,
            String depart,
            String arrive,
            /* The carrier's name — see the record's note on why not the object. */
            String airline,
            String flightClass,
            Boolean refundable,
            BigDecimal pricePerPassenger,
            Integer passengerCount,
            BigDecimal totalPrice
    ) {
    }
}
