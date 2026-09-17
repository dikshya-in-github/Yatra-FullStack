package io.virinchi.yatra.Service;

import io.virinchi.yatra.Exception.ConflictException;
import io.virinchi.yatra.Exception.ResourceNotFoundException;
import io.virinchi.yatra.Model.Flight;
import io.virinchi.yatra.Repository.BookingRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import io.virinchi.yatra.Repository.SeatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Flight lifecycle — deletes only (R4's sibling case).
 *
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
 */
@Service
public class FlightService {

    private final FlightRepository flights;
    private final BookingRepository bookings;
    private final SeatRepository seats;

    public FlightService(FlightRepository flights, BookingRepository bookings, SeatRepository seats) {
        this.flights = flights;
        this.bookings = bookings;
        this.seats = seats;
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
        Flight flight = flights.findById(flightId)
                .orElseThrow(() -> ResourceNotFoundException.of("Flight", flightId));

        //Check pahile: booking hunxa vane DB le pani delete garna dindaina, tara
        //tyo raw FK error 500 banxa — admin lai 409 + "Inactive garne" path dinu parxa.
        long bookingCount = bookings.countByFlightId(flightId);
        if (bookingCount > 0) {
            throw ConflictException.flightHasBookings(flight.getFlightNo(), bookingCount);
        }

        //Delete order (R4): seats pahile, flight paxi.
        seats.deleteAll(seats.findByFlightId(flightId));
        seats.flush();
        flights.delete(flight);
        flights.flush();
    }
}
