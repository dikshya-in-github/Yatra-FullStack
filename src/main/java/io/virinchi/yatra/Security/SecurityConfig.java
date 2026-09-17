package io.virinchi.yatra.Security;

import io.virinchi.yatra.Dto.ErrorResponse;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
 * The security skeleton (Roadmap Phase 3).
 *
 * <p>Two landmines this deliberately defuses (Backend Roadmap Phase 0):
 * <ol>
 *   <li><b>R10 — the project's own pages must never be blocked.</b> With
 *       {@code spring-boot-starter-security} on the classpath, every route is
 *       secured by default and the whole site 401s. The permit rules below are
 *       what stop that.</li>
 *   <li><b>R3 — {@code hasRole('ADMIN')} needs {@code ROLE_ADMIN}.</b> The
 *       {@code hasRole(...)} convention is kept per the author's instruction;
 *       {@link Authorities} is what makes the prefix correct, so no
 *       {@code hasAuthority("ADMIN")} appears anywhere.</li>
 * </ol>
 *
 * <p>Method-level {@code @PreAuthorize} is enabled here so Phase 4 onward can
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
                        // Rule 4: admin access is enforced here and on the methods,
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
