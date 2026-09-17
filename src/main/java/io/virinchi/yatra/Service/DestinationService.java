package io.virinchi.yatra.Service;

import io.virinchi.yatra.Exception.ConflictException;
import io.virinchi.yatra.Exception.ResourceNotFoundException;
import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Repository.DestinationRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Destination lifecycle — deletes, and the guard that makes them safe (R11).
 *
 * <p>A destination is referenced by a flight <b>twice</b> — as
 * {@code flight.origin_id} and as {@code flight.destination_id} — so the check
 * has to cover both directions of the route. Missing one would let a marker
 * delete the origin end of a route and hit a raw FK error, which is the kind of
 * half-guard that looks fine until exactly the wrong row is picked.
 *
 * <p>This is also the one place the frontend already had the right idea:
 * {@code admin-destinations.js} refuses with "<i>…is used by N flight(s) —
 * disable it instead.</i>" The mock only knows about its own localStorage
 * flights; the API check below is what makes that promise true.
 */
@Service
public class DestinationService {

    private final DestinationRepository destinations;
    private final FlightRepository flights;

    public DestinationService(DestinationRepository destinations, FlightRepository flights) {
        this.destinations = destinations;
        this.flights = flights;
    }

    /**
     * Deletes a destination no flight departs from or flies to.
     *
     * @throws ResourceNotFoundException 404 — no such destination
     * @throws ConflictException         409 — a flight's route still uses it
     */
    @Transactional
    public void deleteDestination(int destinationId) {
        Destination destination = destinations.findById(destinationId)
                .orElseThrow(() -> ResourceNotFoundException.of("Destination", destinationId));

        //Origin ra destination duitai ho — eutai id dui choti pass garera derived
        //query le OR banaunxa (countByOriginIdOrDestinationId).
        long flightCount = flights.countByOriginIdOrDestinationId(destinationId, destinationId);
        if (flightCount > 0) {
            throw ConflictException.destinationHasFlights(
                    destination.getCity(), destination.getCode(), flightCount);
        }

        destinations.delete(destination);
        destinations.flush();
    }
}
