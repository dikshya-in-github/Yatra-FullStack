package io.virinchi.yatra.Service;

import io.virinchi.yatra.Exception.ConflictException;
import io.virinchi.yatra.Exception.ResourceNotFoundException;
import io.virinchi.yatra.Model.Airline;
import io.virinchi.yatra.Repository.AirlineRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Airline lifecycle — deletes, and the guard that makes them safe (R11).
 *
 * <p>The admin page's own confirm text says "This cannot be undone in the demo",
 * and the mock deletes without checking anything — because a `localStorage`
 * array has no foreign keys. The real schema does: `flight.airline_id`
 * references `airline`, so an airline that operates flights cannot simply
 * disappear.
 *
 * <p>Two things would go wrong without this guard, and the probe behind R4/R11
 * measured both:
 *
 * <ul>
 *   <li>deleting the airline while its flights are managed in the session throws
 *       {@code TransientPropertyValueException} — a <b>500</b>, not a 404 or a
 *       409, because it is a Hibernate misuse error, not a business refusal;</li>
 *   <li>with a clean session the database refuses it instead (error
 *       {@code 1451}), which the handler can only answer with a generic message —
 *       it cannot know that "disable it instead" was the right advice.</li>
 * </ul>
 *
 * <p>So the guard runs first and produces the specific, actionable 409.
 */
@Service
public class AirlineService {

    private final AirlineRepository airlines;
    private final FlightRepository flights;

    public AirlineService(AirlineRepository airlines, FlightRepository flights) {
        this.airlines = airlines;
        this.flights = flights;
    }

    /**
     * Deletes an airline that no flight uses.
     *
     * @throws ResourceNotFoundException 404 — no such airline
     * @throws ConflictException         409 — flights still operate under it;
     *                                   disable it instead
     */
    @Transactional
    public void deleteAirline(int airlineId) {
        Airline airline = airlines.findById(airlineId)
                .orElseThrow(() -> ResourceNotFoundException.of("Airline", airlineId));

        //Check pahile: count query le flights load gardaina, so delete ko bela kohi
        //managed child reference baki rahanna (R4 ko transient-reference trap).
        long flightCount = flights.countByAirlineId(airlineId);
        if (flightCount > 0) {
            throw ConflictException.airlineHasFlights(airline.getName(), flightCount);
        }

        airlines.delete(airline);
        airlines.flush();
    }
}
