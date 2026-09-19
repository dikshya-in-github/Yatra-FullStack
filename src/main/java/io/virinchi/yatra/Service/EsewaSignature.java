package io.virinchi.yatra.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * eSewa's ePay v2 signature: HMAC-SHA256 over a comma-joined field list, Base64-encoded.
 *
 * <h2>The message is the contract, and it is byte-exact</h2>
 * <p>eSewa signs and verifies over the literal string
 * {@code "name1=value1,name2=value2,…"} — one {@code =} per field, fields joined by
 * {@code ,}, in the order {@code signed_field_names} lists them. There is no
 * encoding, no sorting and no escaping: a value containing a comma would be
 * ambiguous, which is why every field this project signs is a number, a UUID or a
 * product code. The order matters and is <b>not</b> alphabetical — it is whatever
 * {@code signed_field_names} says, which is why {@link #names(String)} exists to
 * split that list rather than re-deriving it.
 *
 * <p>This is the whole reason the class is pure: the only way to be sure the message
 * the server signs is the message eSewa verifies is to have one function build it,
 * used by the form <i>and</i> by the callback check. Encoding bugs here do not
 * degrade gracefully — they surface as {@code 401 Invalid signature} on eSewa's own
 * page, where no amount of application logging can see them.
 *
 * <h2>Comparison is constant time</h2>
 * <p>{@link #matches} uses {@link MessageDigest#isEqual} rather than
 * {@code equals}. The signature is not a secret (it travels in a URL), so this is
 * not defending the value itself — it is refusing to teach the codebase a
 * string-comparison habit on authentication material, and it costs one line.
 */
public final class EsewaSignature {

    /** The one algorithm ePay v2 uses. Stated once so the signer and verifier cannot disagree. */
    private static final String ALGORITHM = "HmacSHA256";

    private EsewaSignature() {
        // Static utility — construction is not part of the API.
    }

    /**
     * The exact string eSewa signs: {@code name=value} pairs in the given order.
     *
     * @param signedFieldNames the names, in order — for this project's outgoing form
     *                         {@code [total_amount, transaction_uuid, product_code]},
     *                         and for a callback whatever the payload's
     *                         {@code signed_field_names} says
     * @param fields           the values, keyed by name
     * @throws IllegalArgumentException if a named field is missing — an unsigned
     *                                  field cannot be silently omitted, because
     *                                  omitting it would produce a message that
     *                                  verifies against nothing
     */
    public static String message(List<String> signedFieldNames, Map<String, String> fields) {
        if (signedFieldNames == null || signedFieldNames.isEmpty()) {
            throw new IllegalArgumentException("No signed_field_names to build a message from.");
        }

        StringBuilder message = new StringBuilder();
        for (int i = 0; i < signedFieldNames.size(); i++) {
            String name = String.valueOf(signedFieldNames.get(i)).trim();
            String value = fields.get(name);

            if (value == null) {
                throw new IllegalArgumentException(
                        "Signed field \"" + name + "\" is missing from the payload.");
            }
            if (i > 0) {
                message.append(',');
            }
            message.append(name).append('=').append(value);
        }
        return message.toString();
    }

    /**
     * The Base64 signature for a field set — what goes in the outgoing form and what
     * a callback's own signature is checked against.
     */
    public static String sign(List<String> signedFieldNames, Map<String, String> fields, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));

            byte[] digest = mac.doFinal(
                    message(signedFieldNames, fields).getBytes(StandardCharsets.UTF_8));

            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException ex) {
            //Neither is reachable on any JDK this project runs on: HmacSHA256 is
            //mandated by the JLS-adjacent JCA spec, and the key is a non-empty String.
            //Wrapped rather than declared so callers do not have to invent a policy
            //for a failure that cannot happen.
            throw new IllegalStateException("Could not sign the eSewa payload.", ex);
        }
    }

    /** Whether a callback's signature matches the one computed over its own payload. */
    public static boolean matches(String expected, String provided) {
        if (expected == null || provided == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * {@code signed_field_names} as a list, in order.
     *
     * <p>Blanks are dropped rather than kept as empty names: eSewa sends the list
     * without spaces, and a trailing comma would otherwise become a field named
     * {@code ""} that no payload can supply — turning a valid callback into a
     * confusing {@code IllegalArgumentException} from {@link #message}.
     */
    public static List<String> names(String signedFieldNames) {
        List<String> names = new ArrayList<>();
        for (String name : String.valueOf(signedFieldNames == null ? "" : signedFieldNames).split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
        return names;
    }
}
