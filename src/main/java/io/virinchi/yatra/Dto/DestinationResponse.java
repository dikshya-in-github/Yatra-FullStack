package io.virinchi.yatra.Dto;

import io.virinchi.yatra.Model.Destination;

/**
 * The public shape of a destination — the mock's exact keys
 * ({@code id, city, code, airport, description, imageUrl, status}).
 *
 * <p><b>{@code imageUrl} is the URL itself, not a pointer to a bytes endpoint.</b>
 * This is the contrast the hybrid split exists to show: an airline answers with
 * {@code /api/airlines/{id}/logo} because its image is bytes inside the database,
 * while a destination answers with the Cloudinary URL it was given, because the
 * bytes were never in the database to begin with. `destinations.html` and
 * `admin-destinations.js` put this value straight into {@code img.src}, and both
 * already treat a missing image as the empty string (the mock's own seed has
 * {@code imageUrl: ""} on six of its eleven rows) — so nothing about the page
 * changes when the real API serves it.
 *
 * <p>{@code imagePublicId} is deliberately <b>absent</b>: it is a storage handle,
 * not display data, and nothing in the UI reads it. The service keeps it on the
 * entity and uses it to delete the asset when the image is replaced or the
 * destination is removed.
 */
public record DestinationResponse(

        int id,
        String city,
        String code,
        String airport,
        String description,
        String imageUrl,
        String status
) {

    public static DestinationResponse of(Destination destination) {
        return new DestinationResponse(
                destination.getId(),
                blankIfNull(destination.getCity()),
                blankIfNull(destination.getCode()),
                blankIfNull(destination.getAirport()),
                blankIfNull(destination.getDescription()),
                blankIfNull(destination.getImageUrl()),
                blankIfNull(destination.getStatus()));
    }

    /** The mock answers {@code ""}, never {@code null}, for an absent value. */
    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }
}
