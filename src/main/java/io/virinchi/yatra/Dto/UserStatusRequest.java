package io.virinchi.yatra.Dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The body of {@code PUT /api/admin/users/{id}/status}: {@code { "status": "Inactive" }}.
 *
 * <p><b>Case-insensitive on purpose.</b> The page's own toggle writes the stored
 * title-case value ({@code Active}/{@code Inactive}) — {@code users.status} is
 * stored exactly as {@code MockDB.SEED_USERS} spells it, so unlike bookings there
 * is no upper-case storage form to translate — but a caller that has just read
 * {@code "ACTIVE"} out of its own head, or {@code "active"} out of a filter chip,
 * should not get a 400 for quoting the API back to itself. Both cases are accepted
 * and normalised to the stored spelling.
 *
 * <p><b>This is a status endpoint, not a delete.</b> Deactivating keeps the account,
 * its bookings and its payment trail; it only stops the account signing in (see
 * {@code ForbiddenException.accountDisabled}). Deleting is a separate call with its
 * own guards, and the admin panel offers both.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserStatusRequest(

        @NotBlank(message = "A status value is required.")
        @Pattern(regexp = "(?i)\\s*(Active|Inactive)\\s*",
                message = "Status must be Active or Inactive.")
        String status
) {
}
