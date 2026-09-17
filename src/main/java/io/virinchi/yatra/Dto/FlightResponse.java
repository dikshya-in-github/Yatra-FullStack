package io.virinchi.yatra.Dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Model.Flight;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * The public shape of a flight — the mock's exact keys
 * ({@code id, no, airlineId, from, to, dep, arr, aircraft, fare, seats,
 * bookedSeats, status}), with <b>one key added: {@code date}</b>.
 *
 * <p><b>Why the mock's key names and not the entity's.</b>
 * {@code admin-flights.js} reads {@code f.no}, {@code f.from}, {@code f.dep},
 * {@code f.seats}, {@code f.bookedSeats} — it builds its whole table from them
 * and filters on them. Renaming any of these to the column names
 * ({@code flightNo}, {@code origin}, {@code departTime}, {@code seatCapacity})
 * would mean rewriting the page; emitting the page's own keys means the real API
 * is a drop-in for the mock, which is the same call the airline module made.
 *
 * <p><b>{@code seats} and {@code bookedSeats} are two different things, and only
 * one is stored.</b> {@code seats} is {@code flight.seat_capacity} — what the
 * admin set. {@code bookedSeats} is <b>computed</b> from the {@code seat} table
 * every time (the teacher-flagged rule: available = capacity − booked, never
 * stored, never edited). The page derives {@code available} itself from the two,
 * which is why neither the entity nor the schema has a "booked" column.
 *
 * <p><b>{@code date} is omitted, never invented.</b> {@code @JsonInclude(NON_NULL)}
 * means a flight created from the admin form — which has no date input — answers
 * exactly the mock's record, and the key appears only once a date really exists.
 * A defaulted date would make a date-filtered storefront search match a day the
 * admin never scheduled.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FlightResponse(

        int id,
        String no,
        Integer airlineId,
        String from,
        String to,
        LocalDate date,
        String dep,
        String arr,
        String aircraft,
        BigDecimal fare,
        int seats,
        long bookedSeats,
        String status
) {

    /** The page's time inputs read and write {@code "HH:mm"}. */
    private static final DateTimeFormatter HH_MM = DateTimeFormatter.ofPattern("HH:mm");

    public static FlightResponse of(Flight flight, long bookedSeats) {
        return new FlightResponse(
                flight.getId(),
                blankIfNull(flight.getFlightNo()),
                flight.getAirline() == null ? null : flight.getAirline().getId(),
                code(flight.getOrigin()),
                code(flight.getDestination()),
                flight.getFlightDate(),
                time(flight.getDepartTime()),
                time(flight.getArriveTime()),
                blankIfNull(flight.getAircraft()),
                flight.getFare(),
                flight.getSeatCapacity(),
                bookedSeats,
                blankIfNull(flight.getStatus()));
    }

    /** A route endpoint is the airport code — {@code "KTM"}, not the city name. */
    private static String code(Destination destination) {
        return destination == null ? "" : blankIfNull(destination.getCode());
    }

    private static String time(java.time.LocalTime value) {
        return value == null ? "" : value.format(HH_MM);
    }

    /** The mock answers {@code ""}, never {@code null}, for an absent value. */
    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }
}
