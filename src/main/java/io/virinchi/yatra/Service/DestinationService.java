package io.virinchi.yatra.Service;

import io.virinchi.yatra.Dto.DestinationRequest;
import io.virinchi.yatra.Exception.ConflictException;
import io.virinchi.yatra.Exception.DuplicateResourceException;
import io.virinchi.yatra.Exception.ResourceNotFoundException;
import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Repository.DestinationRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Destination lifecycle — reads, writes, deletes, and the Cloudinary asset behind
 * the image.
 *
 * <h2>Deletes</h2>
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
 *
 * <h2>The image is a URL, so this is where the two image paths visibly diverge</h2>
 * <p>{@code AirlineService} decodes Base64 out of a database column and hashes it
 * into an {@code ETag}; this class has no bytes to look at. It stores a string,
 * and its only image-related duties are keeping {@code imagePublicId} in step with
 * {@code imageUrl} and giving the old asset back to Cloudinary when the image is
 * replaced or the row is removed. Nothing here sniffs a content type, and there is
 * no bytes endpoint — because the file was never in the database.
 *
 * <h2>Two duplicate rules, only one of them enforced by the database</h2>
 * <p>{@code destination.code} carries a UNIQUE key, so a duplicate code would also
 * be refused by the database — the service check exists to answer a specific
 * {@code DESTINATION_CODE_EXISTS} instead of a generic constraint violation. The
 * <b>city</b> rule is the mock's ({@code admin-destinations.js} refuses a second
 * "Pokhara", because the wizard's arrival list would show it twice) and has no
 * constraint behind it. It is enforced here as a check, with the honest caveat: a
 * check without a constraint can be raced by two simultaneous admin creates. That
 * is acceptable for reference data only an admin writes, and adding a unique key
 * to a live cloud schema is the bigger risk — see R2.
 */
@Service
public class DestinationService {

    /**
     * The sort properties a caller may ask for, keyed by the name the <b>page</b>
     * uses — anything not listed falls back to {@code id}, so no request can order
     * by a column that does not exist. Kept tiny on purpose, the same rule R6
     * records for airlines and flights.
     */
    private static final Map<String, String> SORTABLE = Map.of(
            "id", "id",
            "city", "city",
            "code", "code",
            "airport", "airport",
            "status", "status");

    private static final String DEFAULT_SORT = "id";

    private final DestinationRepository destinations;
    private final FlightRepository flights;
    private final CloudinaryService images;

    public DestinationService(DestinationRepository destinations, FlightRepository flights,
                              CloudinaryService images) {
        this.destinations = destinations;
        this.flights = flights;
        this.images = images;
    }

    /* ------------------------------------------------------------------ *
     *  Reads                                                             *
     * ------------------------------------------------------------------ */

    /**
     * Every matching destination, sorted.
     *
     * <p>The shape both pages consume today: the storefront's {@code destinations}
     * grid and {@code admin-destinations.js} each filter and page client-side over
     * the full list.
     *
     * <p>Needs its own query rather than {@code Pageable.unpaged()} because
     * unpaged discards the {@code Sort}, which is exactly how pagination loses its
     * determinism.
     *
     * @param search matches the city, airport name or code, case-insensitively
     * @param status {@code Active} / {@code Inactive}; blank means any
     * @param sort   one of {@code id|city|code|airport|status} — anything else
     *               falls back to {@code id}
     */
    @Transactional(readOnly = true)
    public List<Destination> listAll(String search, String status, String sort) {
        return destinations.searchAll(like(search), blankToNull(status), sortFor(sort));
    }

    /** One page of matching destinations, plus the counts needed to walk the rest. */
    @Transactional(readOnly = true)
    public Page<Destination> listPage(String search, String status, String sort,
                                      int page, int size) {
        PageRequest request = Paging.request(page, size, sortFor(sort));

        return destinations.searchPage(like(search), blankToNull(status), request);
    }

    /**
     * @throws ResourceNotFoundException 404 — no such destination
     */
    @Transactional(readOnly = true)
    public Destination getDestination(int destinationId) {
        return destinations.findById(destinationId)
                .orElseThrow(() -> ResourceNotFoundException.of("Destination", destinationId));
    }

    /* ------------------------------------------------------------------ *
     *  Writes                                                            *
     * ------------------------------------------------------------------ */

    /**
     * Creates a destination.
     *
     * @throws DuplicateResourceException 409 {@code DESTINATION_CODE_EXISTS} or
     *                                   {@code DESTINATION_CITY_EXISTS}
     */
    @Transactional
    public Destination createDestination(DestinationRequest request) {
        String code = normalizeCode(request.code());
        String city = request.city().trim();

        if (destinations.findByCodeIgnoreCase(code).isPresent()) {
            throw DuplicateResourceException.destinationCodeExists(code);
        }
        if (destinations.findByCityIgnoreCase(city).isPresent()) {
            throw DuplicateResourceException.destinationCityExists(city);
        }

        Destination destination = new Destination();
        apply(destination, request, code, city);

        return destinations.save(destination);
    }

    /**
     * Replaces a destination's fields, moving it to a different image if the
     * request carries one.
     *
     * <p>The Cloudinary cleanup runs <b>after</b> the row is saved, and only when
     * the image actually changed — deleting the asset first would mean a failed
     * save leaves the row pointing at a file that no longer exists, which is a
     * broken image for every visitor. The reverse order's cost is an orphaned
     * asset if the transaction later rolls back, and that is the cheaper failure
     * (see {@link CloudinaryService#delete(String)}).
     *
     * @throws ResourceNotFoundException  404 — no such destination
     * @throws DuplicateResourceException 409 — the code or city belongs to another row
     */
    @Transactional
    public Destination updateDestination(int destinationId, DestinationRequest request) {
        Destination destination = destinations.findById(destinationId)
                .orElseThrow(() -> ResourceNotFoundException.of("Destination", destinationId));

        String code = normalizeCode(request.code());
        String city = request.city().trim();

        destinations.findByCodeIgnoreCase(code)
                .filter(existing -> existing.getId() != destinationId)
                .ifPresent(existing -> {
                    throw DuplicateResourceException.destinationCodeExists(code);
                });
        destinations.findByCityIgnoreCase(city)
                .filter(existing -> existing.getId() != destinationId)
                .ifPresent(existing -> {
                    throw DuplicateResourceException.destinationCityExists(city);
                });

        // Captured before apply() overwrites it: this is the asset that has to be
        // handed back to Cloudinary once the row no longer points at it.
        String previousPublicId = destination.getImagePublicId();
        String previousUrl = destination.getImageUrl();

        apply(destination, request, code, city);
        Destination saved = destinations.save(destination);

        if (!Objects.equals(previousUrl, saved.getImageUrl())
                && !Objects.equals(previousPublicId, saved.getImagePublicId())) {
            images.delete(previousPublicId);
        }

        return saved;
    }

    /**
     * Deletes a destination no flight departs from or flies to.
     *
     * <p>The Cloudinary asset goes with it — otherwise the account accumulates
     * images nothing in the application can ever reference again. Best-effort: the
     * row removal is what the admin asked for, and a CDN hiccup must not turn it
     * into a failure.
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

        String publicId = destination.getImagePublicId();
        destinations.delete(destination);
        destinations.flush();

        images.delete(publicId);
    }

    /* ------------------------------------------------------------------ *
     *  Helpers                                                           *
     * ------------------------------------------------------------------ */

    /**
     * Applies a request onto an entity.
     *
     * <p>{@code imageUrl} is written as given, blank folded to {@code null} — the
     * mock's empty string, so a page rendering an absent image gets the same value
     * it already handles. <b>Unlike the airline logo, no interpretation is
     * needed:</b> the stored value is a URL and the form echoes back that same URL
     * on an edit, so writing it verbatim is correct here. The airline field's
     * three-way meaning (data URL / empty / echoed-URL) comes from its image being
     * bytes in a column; a URL has no such ambiguity.
     */
    private void apply(Destination destination, DestinationRequest request,
                       String code, String city) {
        destination.setCity(city);
        destination.setCode(code);
        destination.setAirport(request.airport().trim());
        destination.setDescription(blankToNull(request.description()));
        destination.setStatus(statusOrDefault(request.status()));

        // Read the stored URL BEFORE overwriting it — "did the image change?" is the
        // question the public id's fate depends on.
        String previousUrl = destination.getImageUrl();
        String nextUrl = blankToNull(request.imageUrl());
        String nextPublicId = blankToNull(request.imagePublicId());

        destination.setImageUrl(nextUrl);

        // The public id travels with the URL as a pair:
        //   no URL            → nothing to own, clear the id;
        //   a supplied id     → the upload endpoint's pair, store both;
        //   a new URL, no id  → a pasted link or a hand-typed Cloudinary URL, so the
        //                       old id must go: keeping it would make the next
        //                       cleanup delete an asset this row no longer shows;
        //   the same URL      → an edit that did not touch the image, so keep the
        //                       stored id rather than orphaning the asset.
        if (nextUrl == null) {
            destination.setImagePublicId(null);
        } else if (nextPublicId != null) {
            destination.setImagePublicId(nextPublicId);
        } else if (!Objects.equals(previousUrl, nextUrl)) {
            destination.setImagePublicId(null);
        }
    }

    /**
     * Canonical form of an airport code: uppercase, trimmed.
     *
     * <p>{@code destination.code} is {@code utf8mb4_bin}, so {@code "pkr"} and
     * {@code "PKR"} are two different values to the database — normalising before
     * the duplicate check is the only thing stopping the same airport existing
     * twice under different casing, exactly as {@code FlightService} does for a
     * flight number.
     */
    private static String normalizeCode(String code) {
        return String.valueOf(code == null ? "" : code).trim().toUpperCase(Locale.ROOT);
    }

    /** A {@code LIKE} pattern for the free-text search, or {@code null} for "no filter". */
    private static String like(String search) {
        String term = String.valueOf(search == null ? "" : search).trim().toLowerCase(Locale.ROOT);
        return term.isEmpty() ? null : "%" + term + "%";
    }

    private static String statusOrDefault(String status) {
        return status == null || status.isBlank() ? "Active" : status.trim();
    }

    private static String blankToNull(String value) {
        String trimmed = String.valueOf(value == null ? "" : value).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Resolves a requested sort to a whitelisted property, always finishing with
     * {@code id} so rows that share a city or status still page in a stable order.
     */
    private static Sort sortFor(String requested) {
        String key = String.valueOf(requested == null ? "" : requested).trim().toLowerCase(Locale.ROOT);
        String property = SORTABLE.getOrDefault(key, DEFAULT_SORT);

        return property.equals(DEFAULT_SORT)
                ? Sort.by(Sort.Order.asc(property))
                : Sort.by(Sort.Order.asc(property), Sort.Order.asc(DEFAULT_SORT));
    }
}
