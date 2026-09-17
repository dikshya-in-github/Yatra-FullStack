package io.virinchi.yatra.RestAPIController;

import io.virinchi.yatra.Dto.DestinationListResponse;
import io.virinchi.yatra.Dto.DestinationRequest;
import io.virinchi.yatra.Dto.DestinationResponse;
import io.virinchi.yatra.Dto.ImageUploadResponse;
import io.virinchi.yatra.Model.Destination;
import io.virinchi.yatra.Service.CloudinaryService;
import io.virinchi.yatra.Service.DestinationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * The destination module — the second image pattern, built beside the airline
 * BLOB so the contrast is demonstrable rather than theoretical.
 *
 * <h2>Two audiences, exactly like the airline module</h2>
 * <p>The reads are public because the storefront is: {@code destinations.html}
 * renders a card per airport, {@code homeLogged} builds its arrival dropdown from
 * the same list, and both do it for signed-out visitors. The writes are admin-only
 * and live under {@code /api/admin/destinations}. Enforced in three places, not
 * one: the {@code /api/admin/**} route rule, {@code @PreAuthorize("hasRole('ADMIN')")}
 * on each write, and {@code Authorities} owning the role prefix. Hiding a sidebar
 * link is not one of them.
 *
 * <h2>Where this differs from the airline module, and why</h2>
 * <p><b>The image never passes through the API's own storage.</b>
 * <ul>
 *   <li>{@code POST /api/admin/destinations/image} takes the file, sends it to
 *       Cloudinary, and answers with a {@code secure_url} + {@code public_id} —
 *       the pair the row stores. The bytes are gone once the call returns.</li>
 *   <li>The writes then take those two strings as ordinary fields. There is no
 *       data URL, no {@code MEDIUMBLOB}, no content-type sniffing, and <b>no
 *       bytes endpoint</b> — {@code GET /api/airlines/{id}/logo} has no
 *       counterpart here, because there is nothing in the database to serve.</li>
 * </ul>
 * That is rule 3's hybrid split in the API surface, and it is deliberate: the
 * assignment mandates database-stored images, the airline logo satisfies that
 * literally, and the destination's CDN delivery reflects how the same job is
 * actually done in production.
 *
 * <h2>A second read path, {@code GET /api/admin/destinations}</h2>
 * <p>The admin page calls this one, not the public path — the mock exposes both
 * and {@code admin-destinations.js} reads {@code resp.destinations} from the admin
 * route. It returns the identical body, under ADMIN, so wiring the page to the
 * real API in Phase 3 does not first require changing its URL.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DestinationController {

    private final DestinationService destinationService;
    private final CloudinaryService cloudinaryService;

    /* ------------------------------------------------------------------ *
     *  Public reads                                                       *
     * ------------------------------------------------------------------ */

    /**
     * The destination list.
     *
     * <p>Unpaged by default, paged on request — the same choice the airline list
     * makes and for the same reason: both pages filter and paginate client-side
     * over the whole list today, and a silent default page size would look like
     * missing airports on a page that has no idea it was truncated. Passing
     * {@code size} switches to a real database page and adds
     * {@code page}/{@code totalElements}/{@code totalPages}.
     *
     * @param search matches the city, airport name or code, case-insensitively
     * @param status {@code Active} / {@code Inactive}; blank means any
     * @param sort   one of {@code id|city|code|airport|status} — anything else
     *               falls back to {@code id}
     */
    @GetMapping("/destinations")
    public DestinationListResponse list(@RequestParam(required = false) String search,
                                        @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String sort,
                                        @RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size) {
        return page(search, status, sort, page, size);
    }

    /** One destination. */
    @GetMapping("/destinations/{id}")
    public DestinationResponse get(@PathVariable int id) {
        return DestinationResponse.of(destinationService.getDestination(id));
    }

    /* ------------------------------------------------------------------ *
     *  Admin reads                                                        *
     * ------------------------------------------------------------------ */

    /**
     * The admin table's read — the same body, behind ADMIN.
     *
     * <p>Public data behind an admin check looks redundant, and it is worth naming
     * why it stays: the page already calls this path, and the endpoint is part of
     * the admin surface that will later grow filters the storefront should not
     * have. Keeping it costs one delegating method; removing it would break
     * {@code admin-destinations.js} the moment {@code USE_MOCK_DATA} is flipped.
     */
    @GetMapping("/admin/destinations")
    @PreAuthorize("hasRole('ADMIN')")
    public DestinationListResponse adminList(@RequestParam(required = false) String search,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String sort,
                                             @RequestParam(required = false) Integer page,
                                             @RequestParam(required = false) Integer size) {
        return page(search, status, sort, page, size);
    }

    /* ------------------------------------------------------------------ *
     *  Admin writes                                                       *
     * ------------------------------------------------------------------ */

    /**
     * Uploads a destination image to Cloudinary and returns the pair to save.
     *
     * <p><b>Why the upload is its own endpoint rather than part of the create.</b>
     * The admin page has a file button and a URL field that are edited
     * independently, and the URL is only known after the upload succeeds. Making
     * the create itself multipart would force the whole form through a
     * {@code multipart/form-data} body, contradicting every other admin write
     * (JSON) and the mock's contract — and it would re-upload the same file on any
     * unrelated field edit. Two steps also mean a failed upload costs the admin
     * nothing but a retry, instead of losing the rest of the form.
     *
     * <p>Multipart, unlike the airline logo's data URL: there is no column to put
     * bytes in here, so the file only ever has to reach Cloudinary, and a
     * {@code MultipartFile} is exactly that without base64 inflation.
     */
    @PostMapping(value = "/admin/destinations/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ImageUploadResponse uploadImage(@RequestPart("file") MultipartFile file) {
        return cloudinaryService.upload(file);
    }

    /** Creates a destination. 409 if the code or the city is already taken. */
    @PostMapping("/admin/destinations")
    @PreAuthorize("hasRole('ADMIN')")
    public DestinationResponse create(@Valid @RequestBody DestinationRequest request) {
        return DestinationResponse.of(destinationService.createDestination(request));
    }

    /** Replaces a destination's fields, and hands a replaced image back to Cloudinary. */
    @PutMapping("/admin/destinations/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public DestinationResponse update(@PathVariable int id,
                                      @Valid @RequestBody DestinationRequest request) {
        return DestinationResponse.of(destinationService.updateDestination(id, request));
    }

    /**
     * Deletes a destination no flight's route uses — 409 with "disable it instead"
     * if one does, the same sentence the admin page's own check produces.
     */
    @DeleteMapping("/admin/destinations/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable int id) {
        destinationService.deleteDestination(id);
        return ResponseEntity.noContent().build();
    }

    /* ------------------------------------------------------------------ *
     *  Helpers                                                            *
     * ------------------------------------------------------------------ */

    /**
     * The one list body, shared by the public and admin paths so they cannot
     * drift apart — two endpoints that answer "the same" list differently is a
     * class of bug that only shows up after a page has been migrated to one of
     * them.
     */
    private DestinationListResponse page(String search, String status, String sort,
                                         Integer page, Integer size) {
        if (size == null) {
            List<Destination> rows = destinationService.listAll(search, status, sort);
            return DestinationListResponse.of(rows);
        }

        return DestinationListResponse.of(destinationService.listPage(
                search, status, sort, page == null ? 0 : page, size));
    }
}
