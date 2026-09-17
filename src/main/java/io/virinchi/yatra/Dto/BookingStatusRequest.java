package io.virinchi.yatra.Dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The body of {@code PUT /api/admin/bookings/{id}/status}: {@code { "status": "Confirmed" }}.
 *
 * <p><b>Case-insensitive on purpose, and that is not laxness.</b> Two vocabularies
 * already meet at this endpoint: the entity stores {@code PENDING/CONFIRMED/CANCELLED}
 * (what {@code BookingService.createBooking} writes) while the admin page's filters
 * and badges speak {@code Pending/Confirmed/Cancelled} (what
 * {@link AdminBookingResponse} deliberately emits so {@code canCancel} works).
 * A caller copying the value it just read out of a list response would otherwise
 * get a 400 for quoting the API back to itself, so both are accepted and the
 * service normalises to the stored form.
 *
 * <p><b>An unknown value is a 400, not a 409.</b> {@code @Pattern} answers
 * {@code VALIDATION_FAILED} before the service runs, which is the honest status:
 * "Cancelled" spelled wrong is a malformed request, whereas a <i>well-formed</i>
 * status the booking's current state forbids is a conflict — that distinction is
 * the whole difference between this DTO and
 * {@code ConflictException.invalidStatusTransition(...)}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BookingStatusRequest(

        @NotBlank(message = "A status value is required.")
        @Pattern(regexp = "(?i)\\s*(PENDING|CONFIRMED|CANCELLED)\\s*",
                message = "Status must be one of PENDING, CONFIRMED or CANCELLED.")
        String status
) {
}
