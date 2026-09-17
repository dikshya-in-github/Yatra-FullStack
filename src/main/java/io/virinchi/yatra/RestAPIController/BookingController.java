package io.virinchi.yatra.RestAPIController;

import io.virinchi.yatra.Dto.BookingRequest;
import io.virinchi.yatra.Dto.BookingResponse;
import io.virinchi.yatra.Service.BookingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The booking write — wizard step 2's {@code POST /api/bookings}.
 *
 * <p><b>Public, and deliberately so.</b> The storefront lets a signed-out visitor
 * complete a booking, and the mock's {@code POST /api/bookings} has never asked for
 * a token; requiring one here would break the flow the page already has. It is
 * therefore the API's only public <i>write</i>, which is worth stating plainly
 * because the consequences are real: an unauthenticated caller can hold seats
 * (one hold per passenger, released when the pending booking is deleted) and there
 * is no rate limit or captcha yet. For the demo's scale that is acceptable and it
 * is recorded as a known limitation rather than a design intent — Phase 9/10 is
 * where an authenticated or throttled path belongs, and the token is already read
 * below, so a signed-in booking is attributed to its user today.
 *
 * <p><b>Where a seat is claimed.</b> Not here. This class translates HTTP into a
 * service call; {@link BookingService#createBooking} owns the transaction, the
 * {@code UPDATE … WHERE status = 'AVAILABLE'} claim that prevents double booking,
 * and the 409s that come out of it. Nothing about availability is decided in a
 * controller.
 *
 * <p><b>The seat map is not here either.</b> {@code GET /api/flights/{id}/seats} is
 * a read of the flight's own seat map, so it lives on
 * {@link FlightController} next to the other flight reads.
 */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    /**
     * Creates the {@code PENDING} booking and holds its seats.
     *
     * <p>Answers the mock's shape ({@code { bookingId, status }}) so
     * {@code booking.js} needs no change, plus the server-computed {@code amount}.
     *
     * @param authentication supplied by Spring Security; anonymous when the caller
     *                       sent no token, which is a supported case, not an error
     */
    @PostMapping("/bookings")
    public BookingResponse create(@Valid @RequestBody BookingRequest request,
                                 Authentication authentication) {
        return BookingResponse.of(bookingService.createBooking(request, userIdOf(authentication)));
    }

    /**
     * The signed-in user's id, or {@code null} for a guest.
     *
     * <p>{@code JwtAuthFilter} authenticates with the token's {@code sub} claim (the
     * user id) as the principal, so {@code getName()} is that id as a string.
     * Everything else is treated as anonymous: no {@code Authentication} at all, an
     * {@link AnonymousAuthenticationToken} (which Spring inserts for a permitted
     * request that carries no token), a blank name, or anything that is not a number
     * — a malformed principal must not 500 a booking, and an unattributable booking
     * is better than a lost one.
     */
    private static Integer userIdOf(Authentication authentication) {
        if (authentication == null
                || authentication instanceof AnonymousAuthenticationToken
                || !authentication.isAuthenticated()) {
            return null;
        }

        String subject = authentication.getName();
        if (subject == null || subject.isBlank() || "anonymousUser".equals(subject)) {
            return null;
        }

        try {
            return Integer.valueOf(subject.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
