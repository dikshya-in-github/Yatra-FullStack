package io.virinchi.yatra.Dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of the destination admin writes ({@code POST}/{@code PUT}).
 *
 * <p>Field-for-field what {@code admin-destinations.js} builds in its submit
 * handler ({@code { city, code, airport, description, imageUrl, status }}), plus
 * {@code ignoreUnknown} so a future form field cannot break the endpoint.
 *
 * <h2>{@code imageUrl} carries a URL; there is no Base64 here</h2>
 * <p>That is the whole point of the hybrid split (rule 3). An airline's logo is
 * bytes in the database, decoded and served from {@code /api/airlines/{id}/logo};
 * a destination's image is a Cloudinary {@code secure_url} — already absolute,
 * already on a CDN, so the column holds text and there is <b>no bytes
 * endpoint</b>. The two paths deliberately do not share a shape, which is what
 * makes them worth showing side by side.
 *
 * <h2>{@code imagePublicId} is accepted but never returned</h2>
 * <p>The upload endpoint ({@code POST /api/admin/destinations/image}) answers with
 * both values, and the admin sends the pair back when saving, so the row can be
 * tied to the asset it owns. It is not part of
 * {@link DestinationResponse}: nothing in the UI reads it, and an opaque storage
 * handle in a response body is the kind of field that gets written back verbatim
 * (the lesson from the airline {@code logo} field). Absent means "the URL alone
 * is all I have", which is a legitimate way to create a destination.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DestinationRequest(

        @NotBlank(message = "City name is required.")
        @Size(max = 255, message = "City name must be at most 255 characters.")
        String city,

        /*
         * Three letters, like every IATA airport code. The page additionally
         * restricts this to the 11 codes searchFlight.js knows — deliberately NOT
         * enforced here: that list belongs to the frontend's airport map, and an
         * API that refused a valid twelfth code would be enforcing a page's
         * limitation. See the note on DestinationService.
         */
        @NotBlank(message = "Airport code is required.")
        @Pattern(regexp = "(?i)[A-Z]{3}", message = "Airport code must be exactly 3 letters (e.g. PKR).")
        String code,

        @NotBlank(message = "Airport name is required.")
        @Size(max = 255, message = "Airport name must be at most 255 characters.")
        String airport,

        /* The column is varchar(1000). */
        @Size(max = 1000, message = "Description must be at most 1000 characters.")
        String description,

        /*
         * A Cloudinary secure_url (or any image URL). No @Pattern: an empty
         * string is meaningful here — it is how the form says "remove the image",
         * which also drops the stored public id.
         */
        @Size(max = 255, message = "Image URL must be at most 255 characters.")
        String imageUrl,

        @Size(max = 255, message = "Image public id must be at most 255 characters.")
        String imagePublicId,

        /* Blank is allowed and defaults to Active; anything else must be one of the two. */
        @Pattern(regexp = "(?i)Active|Inactive|",
                message = "Status must be Active or Inactive.")
        String status
) {
}
