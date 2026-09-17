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
 *       server-side from the <i>flight row's</i> fare × passenger count, because a
 *       number that arrives in a JSON body is a number a browser can edit. The
 *       response returns the figure that was actually stored, so the caller can
 *       see the difference rather than guess. (Fare-class deltas are the
 *       fare-class phase's; today every class prices at the base fare, which is
 *       also how {@code MockDB.payableTotal()} orders its fallbacks.)</li>
 *   <li><b>{@code flight.pricePerPassenger} / {@code totalPrice}.</b> The same
 *       rule — informational, ignored for pricing.</li>
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
            String airline,
            String flightClass,
            Boolean refundable,
            BigDecimal pricePerPassenger,
            Integer passengerCount,
            BigDecimal totalPrice
    ) {
    }
}
