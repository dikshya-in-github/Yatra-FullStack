package io.virinchi.yatra.Dto;

import io.virinchi.yatra.Model.Flight;
import io.virinchi.yatra.Model.Seat;

import java.util.List;

/**
 * The live seat map for one flight — {@code GET /api/flights/{id}/seats}.
 *
 * <p><b>What "live" means.</b> Every status here is read from the {@code seat}
 * table at request time. Nothing is cached and there is no stored availability
 * total to go stale, which is the teacher-flagged rule expressed as an endpoint:
 * {@code available} is computed as {@code seatCapacity − COUNT(BOOKED)} on the
 * spot, exactly as the admin flight table derives it.
 *
 * <p><b>Key names follow {@code FlightResponse}</b> ({@code no}, {@code seats},
 * {@code bookedSeats}) rather than the entity's columns, so the numbers a page
 * reads from the seat map and from a flight card are spelled the same way.
 * {@code available} is included because it is the figure a booking page needs and
 * re-deriving it client-side is how the two drift apart.
 *
 * <p><b>The map is ordered by seat number</b> ({@code 1A, 1B … 12D}), because a
 * caller renders it as a grid and a database {@code SELECT} promises no order.
 *
 * <p><b>Public by design.</b> The booking page has to show the map to signed-out
 * visitors, and the permit on {@code /api/flights/**} already covers this path
 * (risk R12 recorded it as a deliberate choice rather than an accident). A 401
 * here would be the silent kind of failure the airline logos lost a phase to: the
 * page would render an empty cabin rather than an error.
 */
public record SeatMapResponse(

        int flightId,
        String no,
        int seats,
        long bookedSeats,
        long available,
        List<SeatView> seatMap
) {

    /** One seat. {@code number} is the page's seat label ({@code "12C"}). */
    public record SeatView(String number, String status) {
    }

    public static SeatMapResponse of(Flight flight, List<Seat> seats) {
        long booked = seats.stream()
                .filter(seat -> "BOOKED".equalsIgnoreCase(seat.getStatus()))
                .count();

        return new SeatMapResponse(
                flight.getId(),
                flight.getFlightNo() == null ? "" : flight.getFlightNo(),
                flight.getSeatCapacity(),
                booked,
                flight.getSeatCapacity() - booked,
                seats.stream()
                        .map(seat -> new SeatView(
                                seat.getSeatNumber() == null ? "" : seat.getSeatNumber(),
                                seat.getStatus() == null ? "" : seat.getStatus()))
                        .toList());
    }
}
