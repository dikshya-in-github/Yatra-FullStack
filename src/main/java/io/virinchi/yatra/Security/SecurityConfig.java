package io.virinchi.yatra.Security;

import io.virinchi.yatra.Dto.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The security skeleton.
 *
 * <p>Two landmines this deliberately defuses:
 * <ol>
 *   <li><b>The project's own pages must never be blocked.</b> With
 *       {@code spring-boot-starter-security} on the classpath, every route is
 *       secured by default and the whole site 401s. The permit rules below are
 *       what stop that.</li>
 *   <li><b>{@code hasRole('ADMIN')} needs {@code ROLE_ADMIN}.</b> The
 *       {@code hasRole(...)} convention is kept per the author's instruction;
 *       {@link Authorities} is what makes the prefix correct, so no
 *       {@code hasAuthority("ADMIN")} appears anywhere.</li>
 * </ol>
 *
 * <p>Method-level {@code @PreAuthorize} is enabled here so controllers can
 * annotate admin writes without touching this class again.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final ObjectMapper objectMapper;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Stateless JWT API: no cookie, so there is no CSRF token to carry.
                // Re-enable csrf if a cookie-based session is ever introduced.
                .csrf(csrf -> csrf.disable())
                // We authenticate with the Bearer token only — the generated
                // default user and the browser login form stay out of the way.
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // The 34 pages + assets are public. There is no index.html;
                        // home.html is the entry point (add a redirect later).
                        .requestMatchers("/", "/*.html", "/assets/**", "/favicon.ico").permitAll()
                        // Spring Security 6+ authorizes ALL dispatcher types, including
                        // ERROR. Without this, the forward to /error after a genuine 404
                        // or 500 would itself be denied and every anonymous error would
                        // surface as a bogus 401, hiding the real status.
                        .requestMatchers("/error").permitAll()
                        .requestMatchers("/api/auth/**").permitAll()
                        // The booking write is public too: booking.html creates the
                        // PENDING booking and holds its seats for signed-out
                        // visitors, exactly as the mock's POST /api/bookings does.
                        // This is the API's ONLY public write — the double-booking
                        // guard inside BookingService is what keeps it honest, and
                        // there is no rate limit yet (a known limitation, not an
                        // oversight; Phase 9/10 territory). Named as one exact path
                        // rather than a prefix so no future /api/bookings/{id}
                        // read or admin write is opened by accident.
                        .requestMatchers(HttpMethod.POST, "/api/bookings").permitAll()
                        // The airline READS are public because the storefront is:
                        // searchFlight.html shows an airline on every flight card,
                        // to signed-out visitors too. GET-only, so the admin
                        // writes on /api/admin/airlines stay protected.
                        // Without this line the logo endpoint answers 401 to a
                        // guest and the page just drops the image — an "<img>
                        // onerror" fallback, so the failure is silent (risk R8).
                        .requestMatchers(HttpMethod.GET, "/api/airlines/**").permitAll()
                        // The flight READS the storefront needs are public for the
                        // same reason — searchFlight.html queries flights for
                        // signed-out visitors without a token. GET-only, so the
                        // admin CRUD on /api/admin/flights stays protected, and
                        // granted ahead of the endpoint that needs it because the
                        // failure mode is silence: a 401 here would look like "no
                        // flights found" rather than an auth error.
                        // Phase 6 used it: GET /api/flights/{id}/seats is the seat
                        // map, and it is public by design — a booking page must show
                        // the cabin to visitors, and a 401 here would render an empty
                        // cabin rather than an error, the same silent failure the
                        // airline logos hit (R8). Decided, not inherited by accident.
                        .requestMatchers(HttpMethod.GET, "/api/flights/**").permitAll()
                        // The destination READS the storefront needs are public for
                        // the same reason as the two above: destinations.html draws a
                        // card per airport and homeLogged builds its arrival dropdown
                        // from this list, both without a token. GET-only, so the admin
                        // CRUD on /api/admin/destinations stays protected. Note this
                        // rule does NOT open the admin read: /api/admin/destinations is
                        // matched by /api/admin/** below, not by this pattern.
                        .requestMatchers(HttpMethod.GET, "/api/destinations/**").permitAll()
                        // Admin access is enforced here and on the methods,
                        // never by hiding frontend links.
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                // Errors keep the project's one shape, so a 401/403 never returns
                // Spring's HTML page to a fetch() caller in api.js.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint((request, response, ex) ->
                                writeError(response, HttpStatus.UNAUTHORIZED,
                                        "NOT_AUTHENTICATED", "Sign-in required."))
                        .accessDeniedHandler((request, response, ex) ->
                                writeError(response, HttpStatus.FORBIDDEN,
                                        "FORBIDDEN", "You do not have permission to perform this action.")))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Writes {@code { "error": "CODE", "message": "..." }} — the same body
     * {@code GlobalExceptionHandler} produces, so the frontend has one shape to
     * parse whether a failure came from the filter chain or a controller.
     */
    private void writeError(HttpServletResponse response, HttpStatus status,
                            String code, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(new ErrorResponse(code, message)));
    }
}
