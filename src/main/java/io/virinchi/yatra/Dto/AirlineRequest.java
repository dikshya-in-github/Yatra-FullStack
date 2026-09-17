package io.virinchi.yatra.Dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The body of the airline admin writes ({@code POST}/{@code PUT}).
 *
 * <p>Field-for-field what {@code admin-airlines.js} posts
 * ({@code { name, iata, description, status, logo }}), plus
 * {@code ignoreUnknown} so a future form field cannot break the endpoint.
 *
 * <p><b>{@code logo} deliberately has no format annotation.</b> The page sends
 * three different things in that one field — a data URL from a fresh upload, an
 * empty string when the admin clears it, or (on any edit where no new file was
 * chosen) the URL the API itself supplied, echoed straight back. A pattern
 * demanding {@code data:} would reject that last case with a 400 and break every
 * edit, so the interpretation lives in {@code Service/AirlineService} where it
 * can distinguish the three. See that method for the rule.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AirlineRequest(

        @NotBlank(message = "Airline name is required.")
        @Size(max = 255, message = "Airline name must be at most 255 characters.")
        String name,

        @NotBlank(message = "IATA code is required.")
        @Pattern(regexp = "[A-Za-z0-9]{2}", message = "IATA code must be two letters or digits.")
        String iata,

        /* The column is varchar(1000). */
        @Size(max = 1000, message = "Description must be at most 1000 characters.")
        String description,

        /* Blank is allowed and defaults to Active; anything else must be one of the two. */
        @Pattern(regexp = "(?i)Active|Inactive|",
                message = "Status must be Active or Inactive.")
        String status,

        String logo
) {
}
