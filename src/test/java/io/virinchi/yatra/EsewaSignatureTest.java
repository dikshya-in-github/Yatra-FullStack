package io.virinchi.yatra;

import io.virinchi.yatra.Service.EsewaSignature;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The eSewa signature, pinned as a string.
 *
 * <h2>Why this class has no Spring context</h2>
 * <p>Signing is arithmetic over bytes, and the failure it guards against is silent:
 * get the message wrong by one character and the only symptom is eSewa's own page
 * answering "invalid signature" — from a server this project cannot read logs on. A
 * unit test that runs in milliseconds is the right place to freeze the exact string,
 * while {@code EsewaGatewayTest} covers the flow around it.
 *
 * <h2>The one hard-coded signature</h2>
 * <p>{@link #theDocumentedSandboxFormSignsToTheDocumentedSignature()} signs the exact
 * field set eSewa's own form example uses and asserts the exact Base64 the same page
 * publishes for it. Everything else here proves the function is self-consistent —
 * sign then verify, tamper then fail — which a consistently wrong implementation would
 * also pass. This one test is what ties the implementation to eSewa's published pair,
 * and it is why the algorithm is not merely "internally consistent".
 */
class EsewaSignatureTest {

    /** eSewa's published sandbox secret. Its own documentation hands it out. */
    private static final String SANDBOX_SECRET = "8gBm/:&EnhH.1/q";

    private static final List<String> SIGNED =
            List.of("total_amount", "transaction_uuid", "product_code");

    /**
     * The published pair, reproduced: {@code total_amount=110, transaction_uuid=241028,
     * product_code=EPAYTEST} → {@code i94zsd3oXF6ZsSr/kGqT4sSzYQzjj1W/waxjWyRwaME=}.
     *
     * <p><b>Why this example and not the other one eSewa prints.</b> The same page's
     * HMAC section shows {@code total_amount=100,transaction_uuid=11-201-13,
     * product_code=EPAYTEST} answering {@code 4Ov7pCI1zIOdwtV2BRMUNjz1upIlT/COTxfLhWvVurE=},
     * and that pair does <b>not</b> reproduce: HMAC-SHA256 over that input with the
     * documented UAT key is {@code 5DZywcrTKD0gia/rsSMcrRHmJl+4Tbol6S+lWgdJ94E=}. The
     * {@code i94zsd...} signature printed beside the form is the one that verifies, so
     * that is the pair this test freezes. Recorded rather than silently picked because a
     * reader checking eSewa's docs will find both, and the difference is not this
     * code's to fix — but it is worth knowing which one the implementation agrees with.
     */
    @Test
    void theDocumentedSandboxFormSignsToTheDocumentedSignature() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("total_amount", "110");
        fields.put("transaction_uuid", "241028");
        fields.put("product_code", "EPAYTEST");

        // The message is spelled out character for character, before the digest is
        // checked against eSewa's own value.
        assertThat(EsewaSignature.message(SIGNED, fields))
                .isEqualTo("total_amount=110,transaction_uuid=241028,product_code=EPAYTEST");

        // If this ever fails, the signature the server sends is wrong and no amount of
        // flow testing will say why — so it is asserted, not recomputed.
        assertThat(EsewaSignature.sign(SIGNED, fields, SANDBOX_SECRET))
                .isEqualTo("i94zsd3oXF6ZsSr/kGqT4sSzYQzjj1W/waxjWyRwaME=");
    }

    /**
     * The order is the contract. eSewa verifies over {@code signed_field_names}' own
     * order, so the same three fields in a different order are a different signature —
     * which is exactly the bug that would pass a "sign then verify" test written with a
     * single implementation.
     */
    @Test
    void theFieldOrderChangesTheSignature() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("total_amount", "100");
        fields.put("transaction_uuid", "11-201-13");
        fields.put("product_code", "EPAYTEST");

        assertThat(EsewaSignature.sign(SIGNED, fields, SANDBOX_SECRET))
                .as("eSewa's documented order")
                .isNotEqualTo(EsewaSignature.sign(
                        List.of("product_code", "transaction_uuid", "total_amount"),
                        fields, SANDBOX_SECRET));
    }

    /** A round trip, and the three ways it must break. */
    @Test
    void aSignatureVerifiesAgainstItsOwnPayloadAndNothingElse() {
        Map<String, String> fields = callbackFields();

        String signature = EsewaSignature.sign(
                EsewaSignature.names(fields.get("signed_field_names")), fields, SANDBOX_SECRET);

        assertThat(EsewaSignature.matches(signature, signature)).isTrue();
        assertThat(EsewaSignature.matches(signature, signature + "="))
                .as("an appended character is not a match").isFalse();
        assertThat(EsewaSignature.matches(signature, null)).isFalse();
        assertThat(EsewaSignature.matches(null, signature)).isFalse();

        Map<String, String> tampered = new LinkedHashMap<>(fields);
        tampered.put("total_amount", "1");
        assertThat(EsewaSignature.sign(
                EsewaSignature.names(tampered.get("signed_field_names")), tampered, SANDBOX_SECRET))
                .as("one changed figure is a different signature")
                .isNotEqualTo(signature);

        assertThat(EsewaSignature.sign(
                EsewaSignature.names(fields.get("signed_field_names")), fields, SANDBOX_SECRET + "x"))
                .as("and so is another key")
                .isNotEqualTo(signature);
    }

    /**
     * A named field with no value is refused rather than skipped. This is the failure a
     * silent omission would hide: eSewa would hash a shorter message than the server
     * did, and the signature check would fail with no clue which field went missing.
     */
    @Test
    void aSignedFieldThatIsMissingFromThePayloadIsRefused() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("total_amount", "100");

        assertThatThrownBy(() -> EsewaSignature.message(SIGNED, fields))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transaction_uuid");
    }

    /** {@code signed_field_names} is a CSV the payload supplies; blanks are not names. */
    @Test
    void theSignedFieldListIsReadFromThePayloadAndBlanksAreDropped() {
        assertThat(EsewaSignature.names("transaction_code,status,total_amount"))
                .containsExactly("transaction_code", "status", "total_amount");
        assertThat(EsewaSignature.names(" transaction_code , status "))
                .as("spaces are trimmed — eSewa's own list has none, but a proxy may add them")
                .containsExactly("transaction_code", "status");
        assertThat(EsewaSignature.names("status,,total_amount"))
                .as("an empty name would need a field no payload can supply")
                .containsExactly("status", "total_amount");
        assertThat(EsewaSignature.names(null)).isEmpty();
    }

    /** The callback's five-field set, in the order eSewa sends it. */
    private static Map<String, String> callbackFields() {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("transaction_code", "0K3R17T");
        fields.put("status", "COMPLETE");
        fields.put("total_amount", "8299.99");
        fields.put("transaction_uuid", "11-201-13");
        fields.put("product_code", "EPAYTEST");
        fields.put("signed_field_names",
                "transaction_code,status,total_amount,transaction_uuid,product_code,signed_field_names");
        return fields;
    }
}
