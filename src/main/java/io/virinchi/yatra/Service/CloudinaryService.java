package io.virinchi.yatra.Service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import io.virinchi.yatra.Config.CloudinaryConfig;
import io.virinchi.yatra.Dto.ImageUploadResponse;
import io.virinchi.yatra.Exception.ServiceUnavailableException;
import io.virinchi.yatra.Exception.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;

/**
 * The Cloudinary half of rule 3 — destination images go to a CDN, and the
 * database keeps only what points at them.
 *
 * <h2>An upload returns two values, and both are persisted</h2>
 * <p>The {@code secure_url} is what the browser renders; the {@code public_id} is
 * the only handle Cloudinary's destroy call accepts. Storing just the URL is the
 * tempting half-measure and it is what turns a replaced image into an orphan: the
 * row no longer knows which asset it used to own, so nothing in the app can ever
 * delete it, while it keeps counting against the account's storage. That is why
 * {@code Destination.imagePublicId} exists and why
 * {@link #delete(String)} — not the URL — is the delete path.
 *
 * <h2>Deletes are best-effort, and that is a decision</h2>
 * <p>{@link #delete(String)} never throws. Its caller is deleting or updating a
 * <i>destination row</i>, and that operation must succeed or fail on the
 * database's terms, not on whether a third party is reachable at that moment.
 * The alternative — failing the whole request because the remote asset could not
 * be removed — would leave an admin unable to edit a city while Cloudinary is
 * down, and would make the row and the asset disagree anyway. The trade-off is
 * named rather than hidden: a failed delete logs a warning and leaves an
 * orphaned asset, which is recoverable from the Cloudinary media library; a
 * refused row update is not recoverable by the admin at all.
 *
 * <h2>Not configured is a first-class answer</h2>
 * <p>Every method checks {@link CloudinaryConfig#isConfigured()} before touching
 * the client. With no credentials this class answers
 * {@code 503 IMAGE_UPLOAD_NOT_CONFIGURED} and the rest of the module works
 * normally — see the note on {@link CloudinaryConfig} for why that beats a
 * context that will not start.
 */
@Slf4j
@Service
public class CloudinaryService {

    /**
     * The largest upload accepted, mirroring the kind of limit Cloudinary's own
     * free tier imposes. A destination card renders at a few hundred pixels, so a
     * 10 MB file is a mistake (an uncompressed camera original, or a PDF
     * screenshot) rather than intent — and the failure is much clearer here than
     * as an upstream error.
     */
    private static final long MAX_BYTES = 10L * 1024 * 1024;

    /** The content types the admin page's file input accepts, plus PNG/JPEG aliases. */
    private static final String IMAGE_PREFIX = "image/";

    private final CloudinaryConfig config;

    public CloudinaryService(CloudinaryConfig config) {
        this.config = config;
    }

    /** Whether this server can accept uploads at all — read by the controller's docs and the tests. */
    public boolean isConfigured() {
        return config.isConfigured();
    }

    /**
     * Uploads an image and returns the pair the destination row keeps.
     *
     * @throws ValidationException        400 — no file, an empty file, a
     *                                    non-image, or one over {@link #MAX_BYTES}
     * @throws ServiceUnavailableException 503 — not configured, or Cloudinary
     *                                    refused/could not be reached
     */
    public ImageUploadResponse upload(MultipartFile file) {
        byte[] bytes = validate(file);

        // Checked before the client is touched, so an unconfigured server reports
        // that rather than an SDK exception.
        if (!config.isConfigured()) {
            throw ServiceUnavailableException.imageUploadNotConfigured();
        }

        try {
            Cloudinary client = config.client();

            // `resource_type: image` is the default but is stated explicitly: it
            // is what makes the returned public_id an image id rather than a
            // "raw" one, and the two are not interchangeable in a destroy call.
            Map<?, ?> result = client.uploader().upload(bytes, ObjectUtils.asMap(
                    "folder", config.folder(),
                    "resource_type", "image"));

            Object url = result.get("secure_url");
            Object publicId = result.get("public_id");

            if (url == null || publicId == null) {
                // An "ok" response without these two is not usable, so it is
                // treated as a failure rather than stored as a half-row.
                log.warn("Cloudinary upload succeeded but the response carried no secure_url/public_id");
                throw ServiceUnavailableException.imageUploadFailed();
            }

            return new ImageUploadResponse(String.valueOf(url), String.valueOf(publicId));
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof ServiceUnavailableException unavailable) {
                throw unavailable;
            }
            // The upstream message can contain the signed request URL, which
            // carries the api key — logged, never returned.
            log.warn("Cloudinary upload failed: {}", ex.getMessage());
            throw ServiceUnavailableException.imageUploadFailed();
        }
    }

    /**
     * Removes an asset. Best-effort by design — see the class note.
     *
     * @param publicId the stored handle; {@code null}/blank is a no-op, because a
     *                 destination may legitimately have an image URL with no
     *                 public id (it was pasted in, not uploaded)
     * @return {@code true} when the asset was removed, {@code false} when there
     *         was nothing to remove, it is not configured, or the call failed
     */
    public boolean delete(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            return false;
        }
        if (!config.isConfigured()) {
            log.warn("Not deleting Cloudinary asset {} — this server is not configured", publicId);
            return false;
        }

        try {
            Map<?, ?> result = config.client().uploader().destroy(publicId.trim(), ObjectUtils.emptyMap());

            // "ok" means removed; "not found" means it was already gone (an admin
            // may have tidied the media library) — both leave the row correct, so
            // neither is an error.
            Object outcome = result.get("result");
            return outcome != null && "ok".equalsIgnoreCase(String.valueOf(outcome));
        } catch (IOException | RuntimeException ex) {
            log.warn("Cloudinary delete failed for asset {}: {}", publicId, ex.getMessage());
            return false;
        }
    }

    /* ------------------------------------------------------------------ *
     *  Helpers                                                            *
     * ------------------------------------------------------------------ */

    /**
     * The local checks, done before the network is involved so a bad file costs
     * nothing: present, non-empty, image-typed, within the size limit.
     */
    private static byte[] validate(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ValidationException("Choose an image file to upload.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ValidationException(
                    "That image is too large (%.1f MB). The limit is %d MB."
                            .formatted(file.getSize() / 1024.0 / 1024.0, MAX_BYTES / 1024 / 1024));
        }

        String contentType = String.valueOf(file.getContentType()).toLowerCase(Locale.ROOT);
        if (!contentType.startsWith(IMAGE_PREFIX)) {
            // Content type comes from the client, so it is a sanity check rather
            // than a guarantee — Cloudinary validates the actual bytes. Checking
            // it here turns the common mistake (attaching a .pdf or a .txt) into a
            // clear 400 instead of a round trip that fails upstream.
            throw new ValidationException(
                    "That file is not an image (" + contentType + "). Upload a PNG, JPEG, or WebP.");
        }

        try {
            return file.getBytes();
        } catch (IOException ex) {
            log.warn("Could not read the uploaded file: {}", ex.getMessage());
            throw new ValidationException("The uploaded file could not be read. Please try again.");
        }
    }
}
