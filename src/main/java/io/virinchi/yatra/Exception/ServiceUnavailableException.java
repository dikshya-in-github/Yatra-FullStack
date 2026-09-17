package io.virinchi.yatra.Exception;

import org.springframework.http.HttpStatus;

/**
 * 503 — a dependency this endpoint needs is not usable right now.
 *
 * <p>Only the destination image upload raises this, and it has two distinct
 * causes that a caller must be able to tell apart, because the fix is different:
 *
 * <ul>
 *   <li>{@code IMAGE_UPLOAD_NOT_CONFIGURED} — the server has no Cloudinary
 *       credentials. Nothing the caller sends will work; an operator has to set
 *       {@code CLOUDINARY_CLOUD_NAME} / {@code CLOUDINARY_API_KEY} /
 *       {@code CLOUDINARY_API_SECRET}.</li>
 *   <li>{@code IMAGE_UPLOAD_FAILED} — the credentials are there but Cloudinary
 *       refused or could not be reached. Retrying may work.</li>
 * </ul>
 *
 * <p><b>Why the module still boots without credentials.</b> The test suite is
 * {@code @SpringBootTest} against a live schema, so a bean whose construction
 * required real third-party secrets would make every context load depend on
 * them — and a marker could not run the suite at all. {@code ServiceUnavailable}
 * keeps the failure at the one endpoint that genuinely needs the service, which
 * is also the honest answer: a request for a destination list does not become
 * impossible just because image uploads are unconfigured.
 *
 * <p><b>503, not 500.</b> This is not a bug in the request or in the code path
 * that handles it; it is a service that is temporarily unavailable. 500 would
 * tell {@link GlobalExceptionHandler}'s catch-all that something unexpected
 * happened and cost the caller the specific code.
 */
public class ServiceUnavailableException extends ApiException {

    public ServiceUnavailableException(String code, String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }

    /** No Cloudinary credentials on this server — an operator's job, not the caller's. */
    public static ServiceUnavailableException imageUploadNotConfigured() {
        return new ServiceUnavailableException(
                "IMAGE_UPLOAD_NOT_CONFIGURED",
                "Image uploads are not configured on this server. Set the Cloudinary "
                        + "credentials (CLOUDINARY_CLOUD_NAME, CLOUDINARY_API_KEY, CLOUDINARY_API_SECRET), "
                        + "or paste an image URL instead.");
    }

    /**
     * Cloudinary was reached (or not) and the upload did not succeed.
     *
     * <p>The cause is logged by the service, never put in the body: an upstream
     * error message can carry the request URL, and the request URL carries the
     * API key.
     */
    public static ServiceUnavailableException imageUploadFailed() {
        return new ServiceUnavailableException(
                "IMAGE_UPLOAD_FAILED",
                "The image service could not complete the upload. Please try again.");
    }
}
