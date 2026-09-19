package io.virinchi.yatra.Config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * eSewa ePay v2 settings — fix-plan §10.
 *
 * <h2>Why this is not a bean that can fail the context</h2>
 * <p>The same reason {@link CloudinaryConfig} is not one: the test suite is
 * {@code @SpringBootTest} classes booting against a live schema, so a bean whose
 * construction demanded a third-party secret would make every context load depend on
 * it. Here the cost would be worse than Cloudinary's, because the payment module's
 * other endpoints ({@code POST /api/payments/initiate}, {@code verify}, the admin
 * ledger, the refund) need no gateway at all — they would all stop working because
 * something they never call is unconfigured.
 *
 * <p>So {@link #isConfigured()} answers whether this server can walk the real eSewa
 * flow, and the one endpoint that genuinely needs it
 * ({@code GET /api/payments/esewa/checkout/{bookingId}}) answers
 * {@code 503 ESEWA_NOT_CONFIGURED} when it cannot. Nothing else changes behaviour.
 *
 * <h2>The secret is not in the source</h2>
 * <p>{@code application.properties} carries the sandbox URLs and the test product
 * code — those are published values, not credentials — and only a placeholder for
 * the signing key ({@code ${ESEWA_SECRET_KEY:}}). The value itself lives in the
 * git-ignored {@code application-local.properties} or the environment, exactly as
 * the datasource, {@code yatra.jwt.secret} and the Cloudinary credentials do. The
 * eSewa UAT key is eSewa's own published sandbox key, so it is not a Yatra secret in
 * the confidentiality sense — but the habit is the point, and the production key
 * (which {@code ePay} v2 requires for a live merchant) is a real secret that must
 * never be committed.
 *
 * <h2>The three URLs are configuration, not constants</h2>
 * <p>{@link #formUrl()} and {@link #statusUrl()} point at eSewa's <b>UAT</b>
 * environment by their values, not by a flag: going live is editing two lines, which
 * is the right shape when the alternative is a {@code production: true} switch that
 * some future reader has to trace through the code to trust.
 */
@Configuration
public class EsewaConfig {

    private final String formUrl;
    private final String statusUrl;
    private final String productCode;
    private final String secretKey;

    public EsewaConfig(
            @Value("${yatra.esewa.form-url:}") String formUrl,
            @Value("${yatra.esewa.status-url:}") String statusUrl,
            @Value("${yatra.esewa.product-code:}") String productCode,
            @Value("${yatra.esewa.secret-key:}") String secretKey) {
        this.formUrl = trim(formUrl);
        this.statusUrl = trim(statusUrl);
        this.productCode = trim(productCode);
        this.secretKey = trim(secretKey);
    }

    /**
     * Whether the real eSewa flow can be walked at all.
     *
     * <p>All four values are required, for a reason worth stating: signing the form
     * needs the key, submitting it needs the form URL, and the callback's
     * independent confirmation needs the status URL and the product code. Checking
     * only the key would let the flow start and then fail at the one step the
     * customer cannot recover from — after their money has left their wallet.
     */
    public boolean isConfigured() {
        return !secretKey.isEmpty() && !formUrl.isEmpty()
                && !statusUrl.isEmpty() && !productCode.isEmpty();
    }

    /** eSewa's hosted payment page — where the auto-submitting form POSTs. */
    public String formUrl() {
        return formUrl;
    }

    /** eSewa's transaction status API — the callback's independent confirmation. */
    public String statusUrl() {
        return statusUrl;
    }

    /** {@code EPAYTEST} on the sandbox; the merchant's own code in production. */
    public String productCode() {
        return productCode;
    }

    /** The HMAC-SHA256 signing key. Never logged, never returned in a response. */
    public String secretKey() {
        return secretKey;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
