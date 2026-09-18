package io.virinchi.yatra.Service;

import io.virinchi.yatra.Dto.AirlineRequest;
import io.virinchi.yatra.Exception.ConflictException;
import io.virinchi.yatra.Exception.DuplicateResourceException;
import io.virinchi.yatra.Exception.ResourceNotFoundException;
import io.virinchi.yatra.Model.Airline;
import io.virinchi.yatra.Repository.AirlineRepository;
import io.virinchi.yatra.Repository.FlightRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Airline lifecycle — reads, writes, deletes, and the logo bytes.
 *
 * <h2>Deletes</h2>
 * <p>The admin page's own confirm text says "This cannot be undone in the demo",
 * and the mock deletes without checking anything — because a `localStorage`
 * array has no foreign keys. The real schema does: `flight.airline_id`
 * references `airline`, so an airline that operates flights cannot simply
 * disappear.
 *
 * <p>Two things would go wrong without this guard, and a probe
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
 *
 * <h2>Paging and ordering</h2>
 * <p>Every read below passes an <b>explicit</b> sort, and the caller cannot
 * choose an arbitrary one — {@link #sortFor(String)} whitelists four properties.
 * Two reasons, both concrete: paging without `ORDER BY` lets a row appear on two
 * pages or none (InnoDB/TiDB promise no order, and the page-size the admin UI
 * uses makes that visible), and an unrestricted sort property would let a caller
 * ask for `sort=logo`, dragging a MEDIUMBLOB into the ORDER BY — a table scan
 * over every image in the database.
 */
@Service
public class AirlineService {

    /**
     * The sort properties a caller may ask for. Deliberately tiny: anything not
     * listed falls back to {@code id}, so no request can order by the blob or by
     * a column that does not exist.
     */
    private static final Map<String, String> SORTABLE = Map.of(
            "id", "id",
            "name", "name",
            "iata", "iata",
            "status", "status");

    private static final String DEFAULT_SORT = "id";

    private final AirlineRepository airlines;
    private final FlightRepository flights;

    public AirlineService(AirlineRepository airlines, FlightRepository flights) {
        this.airlines = airlines;
        this.flights = flights;
    }

    /* ------------------------------------------------------------------ *
     *  Reads                                                              *
     * ------------------------------------------------------------------ */

    /**
     * Every matching airline, sorted — the shape the pages consume today, where
     * {@code admin-airlines.js} pages the table client-side over the full list.
     *
     * <p>Needs its own query rather than {@code Pageable.unpaged()} because
     * unpaged discards the {@code Sort}, which is exactly how pagination loses
     * its determinism.
     */
    @Transactional(readOnly = true)
    public List<Airline> listAll(String search, String status, String sort) {
        String name = search == null ? "" : search.trim();
        String state = blankToNull(status);
        Sort order = sortFor(sort);

        return state == null
                ? airlines.findByNameContainingIgnoreCase(name, order)
                : airlines.findByNameContainingIgnoreCaseAndStatus(name, state, order);
    }

    /** One page of matching airlines, plus the counts needed to walk the rest. */
    @Transactional(readOnly = true)
    public Page<Airline> listPage(String search, String status, String sort,
                                  int page, int size) {
        String name = search == null ? "" : search.trim();
        String state = blankToNull(status);
        PageRequest request = Paging.request(page, size, sortFor(sort));

        return state == null
                ? airlines.findByNameContainingIgnoreCase(name, request)
                : airlines.findByNameContainingIgnoreCaseAndStatus(name, state, request);
    }

    /**
     * @throws ResourceNotFoundException 404 — no such airline
     */
    @Transactional(readOnly = true)
    public Airline getAirline(int airlineId) {
        return airlines.findById(airlineId)
                .orElseThrow(() -> ResourceNotFoundException.of("Airline", airlineId));
    }

    /* ------------------------------------------------------------------ *
     *  Writes                                                             *
     * ------------------------------------------------------------------ */

    /**
     * Creates an airline.
     *
     * @throws DuplicateResourceException 409 {@code IATA_EXISTS} — the code is
     *                                   UNIQUE because flight numbers are built
     *                                   from it ("U4 951")
     */
    @Transactional
    public Airline createAirline(AirlineRequest request) {
        String iata = normalizeIata(request.iata());

        if (airlines.findByIata(iata).isPresent()) {
            throw DuplicateResourceException.iataExists();
        }

        Airline airline = new Airline();
        apply(airline, request, iata);

        return airlines.save(airline);
    }

    /**
     * Replaces an airline's fields. The IATA code may change, but not to one
     * another airline already owns.
     *
     * @throws ResourceNotFoundException 404 — no such airline
     * @throws DuplicateResourceException 409 {@code IATA_EXISTS}
     */
    @Transactional
    public Airline updateAirline(int airlineId, AirlineRequest request) {
        Airline airline = airlines.findById(airlineId)
                .orElseThrow(() -> ResourceNotFoundException.of("Airline", airlineId));

        String iata = normalizeIata(request.iata());
        airlines.findByIata(iata)
                .filter(existing -> existing.getId() != airlineId)
                .ifPresent(existing -> {
                    throw DuplicateResourceException.iataExists();
                });

        apply(airline, request, iata);

        return airlines.save(airline);
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
        //managed child reference baki rahanna (transient-reference trap).
        long flightCount = flights.countByAirlineId(airlineId);
        if (flightCount > 0) {
            throw ConflictException.airlineHasFlights(airline.getName(), flightCount);
        }

        airlines.delete(airline);
        airlines.flush();
    }

    /* ------------------------------------------------------------------ *
     *  The logo                                                           *
     * ------------------------------------------------------------------ */

    /** An image ready to be written to the response body. */
    public record Logo(byte[] bytes, String contentType, String etag) {
    }

    /**
     * Decodes the stored logo for {@code GET /api/airlines/{id}/logo}.
     *
     * <p>The column holds Base64 text, and the admin page uploads a
     * {@code data:image/…;base64,…} URL, so the stored value is normally a full
     * data URL. The declared media type is taken from its header when present and
     * otherwise sniffed from the bytes — a bare Base64 string is still accepted
     * (the brief's "store the image as Base64 in the DB" reading), it just has to
     * be identified by its content.
     *
     * <p>The ETag is a hash of the decoded bytes, so it changes exactly when the
     * image does. It is what makes the browser revalidate a cached logo with a
     * {@code 304} instead of re-downloading it: the storefront requests one per
     * flight card.
     *
     * @throws ResourceNotFoundException 404 — no such airline, or it has no logo
     */
    @Transactional(readOnly = true)
    public Logo loadLogo(int airlineId) {
        Airline airline = airlines.findById(airlineId)
                .orElseThrow(() -> ResourceNotFoundException.of("Airline", airlineId));

        String stored = airline.getLogo();
        if (stored == null || stored.isBlank()) {
            throw ResourceNotFoundException.of("Airline logo", airlineId);
        }

        String base64 = stored.trim();
        String contentType = null;

        if (base64.regionMatches(true, 0, "data:", 0, 5)) {
            int comma = base64.indexOf(',');
            if (comma > 0) {
                String header = base64.substring(5, comma);
                int semicolon = header.indexOf(';');
                String declared = (semicolon >= 0 ? header.substring(0, semicolon) : header).trim();
                if (declared.matches("[\\w.+-]+/[\\w.+-]+")) {
                    contentType = declared;
                }
                base64 = base64.substring(comma + 1);
            }
        }

        // Strict decoder (not the MIME one) on purpose: whitespace is stripped
        // explicitly, and anything else that is not Base64 stays an error rather
        // than being silently skipped into a corrupt image.
        byte[] bytes = Base64.getDecoder()
                .decode(base64.replaceAll("\\s", "").getBytes(StandardCharsets.UTF_8));

        if (contentType == null) {
            contentType = sniff(bytes);
        }

        return new Logo(bytes, contentType, "\"" + sha256Hex(bytes) + "\"");
    }

    /* ------------------------------------------------------------------ *
     *  Helpers                                                            *
     * ------------------------------------------------------------------ */

    /**
     * Applies a request onto an entity.
     *
     * <p><b>The {@code logo} rule, because one field carries three meanings.</b>
     * The admin form posts:
     * <ul>
     *   <li>a <b>data URL</b> — a freshly chosen file: replace the image;</li>
     *   <li>an <b>empty string</b> — the admin cleared it: remove the image;</li>
     *   <li>the <b>URL this API itself returned</b> — an edit where no new file
     *       was chosen, because {@code admin-airlines.js} stages the row's
     *       existing {@code logo} value back into the form. Storing that would
     *       overwrite the image with the string "/api/airlines/3/logo", so a
     *       non-data value is treated as "unchanged".</li>
     * </ul>
     * The trade-off is explicit: a malformed logo value is ignored rather than
     * rejected, which keeps every existing page working. Phase 9 should either
     * send {@code ""} when nothing changed or use a dedicated logo endpoint.
     */
    private void apply(Airline airline, AirlineRequest request, String iata) {
        airline.setName(request.name().trim());
        airline.setIata(iata);
        airline.setDescription(blankToNull(request.description()));
        airline.setStatus(statusOrDefault(request.status()));

        String logo = request.logo();
        if (logo == null) {
            return; // absent → leave whatever is stored
        }
        if (logo.isBlank()) {
            airline.setLogo(null); // explicitly cleared
        } else if (logo.trim().regionMatches(true, 0, "data:", 0, 5)) {
            airline.setLogo(logo.trim());
        }
    }

    /** The page uppercases already; the API does not rely on that. */
    private static String normalizeIata(String iata) {
        return String.valueOf(iata == null ? "" : iata).trim().toUpperCase(Locale.ROOT);
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
     * {@code id} so rows that share a name or status still page in a stable order.
     */
    private static Sort sortFor(String requested) {
        String key = String.valueOf(requested == null ? "" : requested).trim().toLowerCase(Locale.ROOT);
        String property = SORTABLE.getOrDefault(key, DEFAULT_SORT);

        return property.equals(DEFAULT_SORT)
                ? Sort.by(Sort.Order.asc(property))
                : Sort.by(Sort.Order.asc(property), Sort.Order.asc(DEFAULT_SORT));
    }

    /** Identifies the image content from its first bytes, as a fallback. */
    private static String sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return "image/jpeg";
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length >= 6 && ascii(b, 0, 6).startsWith("GIF8")) {
            return "image/gif";
        }
        if (b.length >= 12 && ascii(b, 0, 4).equals("RIFF") && ascii(b, 8, 4).equals("WEBP")) {
            return "image/webp";
        }
        String head = new String(b, 0, Math.min(b.length, 256), StandardCharsets.UTF_8)
                .trim().toLowerCase(Locale.ROOT);
        if (head.startsWith("<svg") || head.startsWith("<?xml")) {
            return "image/svg+xml";
        }
        return "application/octet-stream";
    }

    private static String ascii(byte[] bytes, int offset, int length) {
        return new String(bytes, offset, length, StandardCharsets.ISO_8859_1);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                        .append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 is required of every JRE, so this cannot happen in practice.
            throw new IllegalStateException("SHA-256 is unavailable in this JVM", ex);
        }
    }
}
