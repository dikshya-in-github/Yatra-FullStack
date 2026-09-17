package io.virinchi.yatra.Dto;

/**
 * The body of {@code POST /api/admin/destinations/image} — the two values the
 * {@code destination} row keeps after an upload.
 *
 * <p>This is the Cloudinary half of rule 3 stated as a data structure: an upload
 * answers with a <b>URL and a handle</b>, never bytes. Compare
 * {@code AirlineController}'s logo write, which accepts the image itself as a
 * data URL because that image has to end up in a database column.
 *
 * <p><b>Both values are needed and they are not interchangeable.</b>
 * {@code imageUrl} is what the browser renders and can be rewritten by Cloudinary
 * or by an admin pointing at a different asset; {@code publicId} is the only
 * thing {@code uploader().destroy()} accepts, so without it a replaced image
 * becomes an orphaned file that counts against the account's storage quota and
 * can never be cleaned up from the app.
 */
public record ImageUploadResponse(

        String imageUrl,
        String publicId
) {
}
