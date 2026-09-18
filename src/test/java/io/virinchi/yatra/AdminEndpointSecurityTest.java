package io.virinchi.yatra;

import io.virinchi.yatra.Security.JwtUtil;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
        String token = jwtUtil.generate(1, "Dikshya Ghising", "admin@gmail.com", "ADMIN");

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
        String token = jwtUtil.generate(1, "Dikshya Ghising", "admin@gmail.com", "ADMIN");
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
     * Page routes must not be 401'd — they are the site, not the API, and
     * {@code SecurityConfig} permits {@code /*.html} outright.
     *
     * <p>This expected a <b>404</b> until Phases 11–12 added
     * {@code Controller/PageController}: the point was "not 401", and no page
     * controller existed to serve one. It now asserts the stronger thing that note
     * promised. {@code PageControllerTest} is what sweeps all 34 templates; this line
     * is here so that a security rule loosened or tightened later cannot start
     * 401ing the site without failing the build.
     */
    @Test
    void pageRoutesAreNotBlockedBySecurity() throws Exception {
        mockMvc.perform(get("/home.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
    }
}
