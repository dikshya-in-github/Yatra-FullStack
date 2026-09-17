package io.virinchi.yatra.Dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * The body of the flight admin writes ({@code POST}/{@code PUT}).
 *
 * <p><b>Field-for-field what {@code admin-flights.js} posts</b>
 * ({@code { no, airlineId, from, to, dep, arr, aircraft, fare, seats, status }}) —
 * the same keys the mock's flight records use — plus {@code ignoreUnknown} so a
 * future form field cannot break the endpoint.
 *
 * <p><b>The constraints below are the page's own validators, not invented ones.</b>
 * {@code admin-flights.js} already refuses a body that breaks any of these, so
 * mirroring them means the API accepts exactly what the page sends and the page
 * never sees a surprise 400. Where the two could drift, the page is the source:
 * the seat-capacity bound is {@code 1..999} because that is the range its input
 * enforces, and the flight-number shape is its own regex.
 *
 * <p><b>{@code from} / {@code to} are destination codes, not ids.</b> The select
 * elements are built from the page's {@code CITIES} map, so the value posted is
 * {@code "KTM"}, matching {@code destination.code} (unique, 3 letters). The
 * service resolves them, which is also where "no such airport" becomes a specific
 * 400 rather than a raw FK error.
 *
 * <p><b>{@code date} is deliberately optional and never defaulted.</b> The admin
 * form has no date input, so every flight created from today's UI arrives without
 * one — and inventing "today" would silently make the storefront's date-filtered
 * search return flights the admin never scheduled for that day.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlightRequest(

        @NotBlank(message = "Flight number is required.")
        @Size(max = 255, message = "Flight number must be at most 255 characters.")
        /* The page's own format after its uppercase/space-collapse: "U4 951".
           Case-insensitive and \s+ here because the service normalises before it
           checks uniqueness — validation runs first, so it must not reject a
           lowercase "u4 951" that it is about to canonicalise. */
        @Pattern(regexp = "(?i)[A-Z0-9]{2,3}\\s+\\d{1,4}[A-Z]?",
                message = "Use the format \"U4 951\" — airline code + number.")
        String no,

        @NotNull(message = "Select the operating airline.")
        @Positive(message = "Select the operating airline.")
        Integer airlineId,

        @NotNull(message = "Select the origin airport.")
        @Pattern(regexp = "(?i)[A-Z]{3}", message = "Origin must be a 3-letter airport code.")
        String from,

        @NotNull(message = "Select the destination airport.")
        @Pattern(regexp = "(?i)[A-Z]{3}", message = "Destination must be a 3-letter airport code.")
        String to,

        /* Optional on purpose — see the class comment. ISO yyyy-MM-dd. */
        LocalDate date,

        @NotNull(message = "Departure time is required.")
        LocalTime dep,

        @NotNull(message = "Arrival time is required.")
        LocalTime arr,

        @Size(max = 255, message = "Aircraft must be at most 255 characters.")
        String aircraft,

        @NotNull(message = "Base fare must be a positive amount.")
        @DecimalMin(value = "0.01", message = "Base fare must be a positive amount.")
        /* decimal(10,2) in the schema — refuse rather than let the database round. */
        @Digits(integer = 8, fraction = 2, message = "Base fare must have at most 2 decimal places.")
        BigDecimal fare,

        @NotNull(message = "Seat capacity must be a whole number between 1 and 999.")
        @Min(value = 1, message = "Seat capacity must be a whole number between 1 and 999.")
        @Max(value = 999, message = "Seat capacity must be a whole number between 1 and 999.")
        Integer seats,

        /* Blank is allowed and defaults to Active; anything else must be one of the two. */
        @Pattern(regexp = "(?i)Active|Inactive|",
                message = "Status must be Active or Inactive.")
        String status
) {
}
