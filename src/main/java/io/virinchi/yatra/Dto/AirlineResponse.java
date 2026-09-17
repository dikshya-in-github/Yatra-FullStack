package io.virinchi.yatra.Dto;

import io.virinchi.yatra.Model.Airline;

/**
 * The public shape of an airline — the mock's exact keys
 * ({@code id, name, iata, description, status, logo}), with **one deliberate
 * difference: {@code logo} is never the image itself.**
 *
 * <p><b>The logo field keeps its name and its meaning.</b> Every consumer
 * ({@code searchFlight.js:145}, {@code admin-airlines.js:145-151},
 * {@code admin-flights.js:155-158}) puts this value straight into
 * {@code img.src} and already copes with *either* a path or a {@code data:} URL —
 * the mock itself seeds a path ({@code assets/imgs/airline-buddha.jpg}). So the
 * value here is {@code /api/airlines/{id}/logo}, a same-origin URL the browser
 * fetches as bytes, and **no page needs changing**. An airline with no logo
 * reports {@code ""}, the same empty string the mock uses.
 *
 * <p><b>The Base64 blob is never serialised to JSON.</b> Inlining it would
 * inflate every list response by ~33% per row for data no caller reads — that was
 * risk R8, and this DTO is the boundary that keeps it out.
 */
public record AirlineResponse(

        int id,
        String name,
        String iata,
        String description,
        String status,
        String logo
) {

    /** The bytes endpoint a page should point {@code img.src} at. */
    public static final String LOGO_PATH = "/api/airlines/%d/logo";

    public static AirlineResponse of(Airline airline) {
        return new AirlineResponse(
                airline.getId(),
                blankIfNull(airline.getName()),
                blankIfNull(airline.getIata()),
                blankIfNull(airline.getDescription()),
                blankIfNull(airline.getStatus()),
                hasLogo(airline) ? LOGO_PATH.formatted(airline.getId()) : "");
    }

    private static boolean hasLogo(Airline airline) {
        String logo = airline.getLogo();
        return logo != null && !logo.isBlank();
    }

    /** The mock answers {@code ""}, never {@code null}, for an absent value. */
    private static String blankIfNull(String value) {
        return value == null ? "" : value;
    }
}
