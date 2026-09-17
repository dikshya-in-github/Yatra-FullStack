package io.virinchi.yatra;

import io.virinchi.yatra.Security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end proof of the role-prefix fix, with a **real signed JWT** — the verification
 * the author asked for: "a protected admin endpoint returns 200, not 403".
 *
 * <p>The protected endpoint lives in <b>test sources</b>, not production, so no
 * throwaway {@code /api/admin/ping} ships in the app. Component scanning picks
 * the nested controller up from {@link Import}.
 *
 * <p>Note {@code @AutoConfigureMockMvc}'s package: Boot 4 moved it to
 * {@code o.s.boot.webmvc.test.autoconfigure} (it was
 * {@code o.s.boot.test.autoconfigure.web.servlet} in Boot 3). Verified against
 * the jar, not assumed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminEndpointSecurityTest.AdminPingController.class)
class AdminEndpointSecurityTest {

    @RestController
    @RequestMapping("/api/admin")
    static class AdminPingController {
        @GetMapping("/ping")
        String ping() {
            return "admin-ok";
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtUtil jwtUtil;

    /** The proof: a real ADMIN token gets past hasRole('ADMIN'). */
    @Test
    void adminTokenReachesTheProtectedEndpoint() throws Exception {
        String token = jwtUtil.generate(1, "Dikshya Ghising", "admin@yatra.com", "ADMIN");

        mockMvc.perform(get("/api/admin/ping").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string("admin-ok"));
    }

    @Test
    void authenticatedNonAdminIsForbidden() throws Exception {
        String token = jwtUtil.generate(2, "Anju Karki", "anju.karki@example.com", "USER");

        mockMvc.perform(get("/api/admin/ping").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("\"error\":\"FORBIDDEN\"")));
    }

    @Test
    void noTokenIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/admin/ping"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(containsString("\"error\":\"NOT_AUTHENTICATED\"")));
    }

    @Test
    void aTamperedSignatureIsRejected() throws Exception {
        String token = jwtUtil.generate(1, "Dikshya Ghising", "admin@yatra.com", "ADMIN");
        String tampered = token.substring(0, token.length() - 3) + "aaa";

        mockMvc.perform(get("/api/admin/ping").header(HttpHeaders.AUTHORIZATION, "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    /** Static assets must be served, not 401'd. */
    @Test
    void staticAssetsAreNotBlocked() throws Exception {
        mockMvc.perform(get("/assets/js/config.js")).andExpect(status().isOk());
    }

    /**
     * Page routes must not be 401'd. No page controller exists yet, so the
     * expected status is 404 — the point is that it is NOT 401, i.e. security let
     * the request through. This assertion tightens to 200 once the Thymeleaf
     * page controllers exist.
     */
    @Test
    void pageRoutesAreNotBlockedBySecurity() throws Exception {
        mockMvc.perform(get("/home.html")).andExpect(status().isNotFound());
    }
}
