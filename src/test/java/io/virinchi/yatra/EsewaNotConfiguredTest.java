package io.virinchi.yatra;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * "No eSewa credentials" is a supported state — fix-plan §10.
 *
 * <h2>The claim this pins</h2>
 * <p>{@link io.virinchi.yatra.Config.EsewaConfig} refuses to be a bean that can fail the
 * context (the same decision {@code CloudinaryConfig} records), so a deployment with no
 * signing key must behave in exactly one way: the gateway handoff answers
 * {@code 503 ESEWA_NOT_CONFIGURED} and <b>every other payment endpoint keeps working</b>.
 * Without this class that is an assertion in a comment, and the failure it describes —
 * a fresh clone whose whole payment module is dead because one secret is absent — is the
 * kind that is discovered late and misdiagnosed as a security problem.
 *
 * <h2>Why its own context</h2>
 * <p>{@code properties = "yatra.esewa.secret-key="} overrides the sandbox key in the
 * git-ignored {@code application-local.properties}, which means a distinct context cache
 * key and one extra Spring Boot start for this class. That is the cost of testing the
 * unconfigured path <i>at all</i> — the alternative is an assertion nobody can run, and
 * the configured path is what every other suite exercises.
 *
 * <p>Nothing here needs a booking, a seat or a flight: the 503 is raised before the
 * booking is even looked up, which is itself part of the point — an unconfigured gateway
 * must not read or write anything.
 */
@SpringBootTest(properties = "yatra.esewa.secret-key=")
@AutoConfigureMockMvc
class EsewaNotConfiguredTest {

    @Autowired private MockMvc mockMvc;

    /** The handoff says exactly what is missing, and it is the only endpoint affected. */
    @Test
    void theCheckoutAnswers503NamingTheMissingConfiguration() throws Exception {
        mockMvc.perform(get("/api/payments/esewa/checkout/1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("ESEWA_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ESEWA_SECRET_KEY")));
    }

    /**
     * The rest of the payment API answers its own errors rather than a gateway 503 —
     * which is the whole difference between "this deployment cannot take eSewa payments"
     * and "the payment module is down".
     */
    @Test
    void theOtherPaymentEndpointsAreUnaffected() throws Exception {
        // An unknown booking is the booking module's own 404, not the gateway's 503.
        mockMvc.perform(post("/api/payments/initiate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bookingId\":999999999,\"method\":\"esewa\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("BOOKING_NOT_FOUND"));

        mockMvc.perform(post("/api/payments/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"txnId\":\"9A00000001\",\"bookingId\":999999999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("BOOKING_NOT_FOUND"));
    }
}
