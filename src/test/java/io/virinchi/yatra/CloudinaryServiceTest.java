package io.virinchi.yatra;

import io.virinchi.yatra.Config.CloudinaryConfig;
import io.virinchi.yatra.Exception.ApiException;
import io.virinchi.yatra.Exception.ServiceUnavailableException;
import io.virinchi.yatra.Exception.ValidationException;
import io.virinchi.yatra.Service.CloudinaryService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Cloudinary service with no Spring context and no database — deliberately.
 *
 * <p>Everything asserted here is about states that must be true <b>without</b> a
 * Cloudinary account, which is what makes the test deterministic on every machine
 * including a marker's: the credentials are passed in as empty strings, so the
 * result cannot depend on the environment or on
 * {@code application-local.properties}.
 *
 * <p>What is NOT tested here, and cannot be: a real upload. That needs live
 * credentials, and asserting it in a suite would either fail on a machine without
 * them or silently skip — and a silently skipped test is worse than a documented
 * manual check. The live upload is the roadmap's Phase 8 checkpoint; this file
 * covers the half that must hold when nobody has configured anything.
 *
 * <p>The split also follows the standing rule that DB-free tests stay DB-free:
 * {@code GlobalExceptionHandlerTest} uses MockMvc standalone for the same reason,
 * and here the whole test is a plain constructor call.
 */
class CloudinaryServiceTest {

    /** A real 1×1 PNG, so the size and content-type checks see a plausible file. */
    private static final byte[] PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8DwHwAFAAH/q842iQAAAABJRU5ErkJggg==");

    /* ------------------------------------------------------------------ *
     *  the supported "not configured" state                              *
     * ------------------------------------------------------------------ */

    /**
     * The heart of it: with no credentials the upload refuses with its own code
     * rather than an SDK exception or a 500.
     *
     * <p>Asserted on the <b>code</b>, not just the message, because that is what a
     * caller branches on — and because it is the difference between "nobody set
     * the credentials" (an operator's job) and "Cloudinary is down" (retry).
     */
    @Test
    void anUnconfiguredServerRefusesAnUploadWithItsOwnCodeAndA503() {
        CloudinaryService cloudinary = unconfigured();

        assertThatThrownBy(() -> cloudinary.upload(image("cat.png", "image/png", PNG)))
                .isInstanceOf(ServiceUnavailableException.class)
                .satisfies(ex -> {
                    ApiException api = (ApiException) ex;
                    assertThat(api.getCode()).isEqualTo("IMAGE_UPLOAD_NOT_CONFIGURED");
                    assertThat(api.getStatus().value()).isEqualTo(503);
                })
                // The message has to say what to do about it: the API key is an
                // operator's fix, and "paste a URL instead" is the caller's.
                .hasMessageContaining("CLOUDINARY_API_SECRET");
    }

    /**
     * A partial configuration is not a configuration. Cloudinary signs every
     * request with the api secret, so a cloud name and key alone would
     * authenticate and then fail the signature — a worse failure than refusing.
     */
    @Test
    void twoOfThreeCredentialsIsStillNotConfigured() {
        assertThat(new CloudinaryConfig("demo-cloud", "", "", "").isConfigured()).isFalse();
        assertThat(new CloudinaryConfig("demo-cloud", "key", "", "").isConfigured()).isFalse();
        assertThat(new CloudinaryConfig("", "key", "secret", "").isConfigured()).isFalse();
        assertThat(new CloudinaryConfig("demo-cloud", "key", "secret", "").isConfigured()).isTrue();
    }

    /** Blank-but-present values (the properties' normal empty-string default) count as missing. */
    @Test
    void whitespaceOnlyCredentialsCountAsMissing() {
        assertThat(new CloudinaryConfig("  ", "\t", " ", "").isConfigured()).isFalse();
    }

    /**
     * Building the client without credentials is a programming error, not a user
     * error — {@code CloudinaryService} checks first and turns the check into the
     * 503. This proves the guard is real, so a future caller that skips the check
     * fails loudly in a unit test instead of at runtime.
     */
    @Test
    void buildingTheClientWithNoCredentialsIsARefusedProgrammingError() {
        assertThatThrownBy(() -> new CloudinaryConfig("", "", "", "").client())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not configured");
    }

    /**
     * Deleting when unconfigured is a no-op that returns false — never an
     * exception. The caller is removing or updating a destination <i>row</i>, and
     * that must not fail because a third party is unreachable.
     */
    @Test
    void deleteIsANoOpThatNeverThrowsWhenUnconfigured() {
        CloudinaryService cloudinary = unconfigured();

        assertThat(cloudinary.delete("yatra/destinations/whatever")).isFalse();
        assertThat(cloudinary.delete(null)).isFalse();
        assertThat(cloudinary.delete("   ")).isFalse();
    }

    /* ------------------------------------------------------------------ *
     *  local validation — checked before the network, on any server      *
     * ------------------------------------------------------------------ */

    @Test
    void aMissingOrEmptyFileIsRejectedBeforeAnythingElse() {
        CloudinaryService cloudinary = unconfigured();

        assertThatThrownBy(() -> cloudinary.upload(null))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Choose an image file");

        assertThatThrownBy(() -> cloudinary.upload(image("empty.png", "image/png", new byte[0])))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("Choose an image file");
    }

    /**
     * The common mistake — attaching a PDF or a text file. Checked locally so it
     * costs a 400 rather than a round trip that fails upstream with a message
     * about the file's contents.
     */
    @Test
    void aNonImageIsRejectedWithTheTypeItActuallyIs() {
        assertThatThrownBy(() -> unconfigured().upload(
                image("syllabus.pdf", "application/pdf", "%PDF-1.7".getBytes())))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("application/pdf");
    }

    /** Over the 10 MB limit, refused before it is read into memory or sent anywhere. */
    @Test
    void anOversizedImageIsRejectedLocally() {
        byte[] tooBig = new byte[10 * 1024 * 1024 + 1];

        assertThatThrownBy(() -> unconfigured().upload(image("huge.png", "image/png", tooBig)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("too large");
    }

    /**
     * The order of the two checks is deliberate and worth pinning: a bad file is a
     * 400 even on an unconfigured server, because "that is not an image" is true
     * regardless of whether Cloudinary has been set up. Reversing them would
     * answer every malformed request with a configuration complaint.
     */
    @Test
    void aBadFileIsA400EvenWhenTheServerIsNotConfigured() {
        assertThatThrownBy(() -> unconfigured().upload(
                image("notes.txt", "text/plain", "hello".getBytes())))
                .isInstanceOf(ValidationException.class);
    }

    /* ------------------------------------------------------------------ *
     *  fixtures                                                          *
     * ------------------------------------------------------------------ */

    private static CloudinaryService unconfigured() {
        return new CloudinaryService(new CloudinaryConfig("", "", "", ""));
    }

    private static MockMultipartFile image(String name, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", name, contentType, bytes);
    }
}
