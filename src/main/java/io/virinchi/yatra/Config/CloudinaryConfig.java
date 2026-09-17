package io.virinchi.yatra.Config;

import com.cloudinary.Cloudinary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * Cloudinary credentials, and the client they build.
 *
 * <h2>Why the client is built lazily instead of as a {@code @Bean}</h2>
 * <p>The obvious shape is a {@code @Bean Cloudinary cloudinary()} that reads
 * three {@code @Value}s and returns {@code new Cloudinary(...)}. It cannot be
 * used here, for a reason that is easy to miss: the Cloudinary constructor
 * <b>rejects blank credentials</b>, so with no credentials configured — which is
 * the state of a fresh clone, and of every machine that is not the author's —
 * that bean would fail and take <b>the whole application context</b> with it.
 * Every {@code @SpringBootTest} would then need real third-party secrets to load,
 * and the destination module's own CRUD endpoints, which need no image service at
 * all, would stop working.
 *
 * <p>So the failure is moved to the one call that genuinely needs Cloudinary:
 * {@link #isConfigured()} answers whether an upload can be attempted, and
 * {@link #client()} is only ever reached after that check has passed (see
 * {@code Service/CloudinaryService}). The cost is one boolean and a lazily
 * initialised field; the benefit is that "no credentials" is a clean
 * {@code 503 IMAGE_UPLOAD_NOT_CONFIGURED} on one endpoint instead of a context
 * that will not start.
 *
 * <h2>Credentials are environment variables, never source</h2>
 * <p>{@code application.properties} carries only placeholders
 * ({@code ${CLOUDINARY_API_SECRET:}}); the real values live in the git-ignored
 * {@code application-local.properties} or the environment. This is the same rule
 * the datasource and {@code yatra.jwt.secret} already follow, and it is why the
 * secret is read through {@code @Value} rather than written here.
 */
@Configuration
public class CloudinaryConfig {

    /** The default folder assets are filed under in the Cloudinary media library. */
    private static final String DEFAULT_FOLDER = "yatra/destinations";

    private final String cloudName;
    private final String apiKey;
    private final String apiSecret;
    private final String folder;

    /**
     * The client, built on first use. {@code volatile} + a local copy so the
     * double-checked initialisation is correct without synchronising every read —
     * this is called on the upload path, not in a loop, but a half-published
     * client is exactly the kind of bug that shows up once and is never
     * reproducible.
     */
    private volatile Cloudinary client;

    public CloudinaryConfig(
            @Value("${cloudinary.cloud-name:}") String cloudName,
            @Value("${cloudinary.api-key:}") String apiKey,
            @Value("${cloudinary.api-secret:}") String apiSecret,
            @Value("${cloudinary.folder:" + DEFAULT_FOLDER + "}") String folder) {
        this.cloudName = trim(cloudName);
        this.apiKey = trim(apiKey);
        this.apiSecret = trim(apiSecret);
        this.folder = trim(folder).isEmpty() ? DEFAULT_FOLDER : trim(folder);
    }

    /**
     * Whether an upload can be attempted at all.
     *
     * <p>All three values are required, not just two: Cloudinary signs every
     * request with the api secret, so a cloud name and a key without the secret
     * would authenticate the call and then fail its signature — a worse failure
     * than refusing up front.
     */
    public boolean isConfigured() {
        return !cloudName.isEmpty() && !apiKey.isEmpty() && !apiSecret.isEmpty();
    }

    /** The media-library folder a new asset is filed under. */
    public String folder() {
        return folder;
    }

    /**
     * The Cloudinary client.
     *
     * @throws IllegalStateException if called while {@link #isConfigured()} is
     *                               false — a programming error, not a user
     *                               error, because the caller is supposed to
     *                               check first. {@code CloudinaryService} turns
     *                               the check into the 503 the API reports.
     */
    public Cloudinary client() {
        if (!isConfigured()) {
            throw new IllegalStateException(
                    "Cloudinary is not configured — check isConfigured() before calling client().");
        }

        Cloudinary local = client;
        if (local == null) {
            synchronized (this) {
                local = client;
                if (local == null) {
                    // `secure: true` makes Cloudinary return an https secure_url.
                    // Without it the URL comes back over http, and a page served
                    // over https would block the image as mixed content.
                    local = new Cloudinary(Map.of(
                            "cloud_name", cloudName,
                            "api_key", apiKey,
                            "api_secret", apiSecret,
                            "secure", true));
                    client = local;
                }
            }
        }
        return local;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
