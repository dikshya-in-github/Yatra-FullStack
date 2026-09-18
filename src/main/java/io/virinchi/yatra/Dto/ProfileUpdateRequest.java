package io.virinchi.yatra.Dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * The body of {@code POST /api/admin/profile} and of {@code POST}/{@code PUT
 * /api/users/me} — what {@code admin-profile.html}'s and {@code profile.html}'s
 * "Save changes" buttons both submit.
 *
 * <p><b>One record for both surfaces, because it is one form.</b> An administrator
 * editing their own row and a customer editing theirs send exactly the same three
 * keys, and the service methods behind them
 * ({@code UserService.updateOwnProfile}, which both controllers call) enforce exactly
 * the same rules. Two near-identical records would be a second place for the rules to
 * disagree, which is the failure this sharing exists to prevent.
 *
 * <h2>Three fields, and the brief's own rule about them</h2>
 * <p>{@code admin-profile.js} posts exactly {@code { name, email, phone }}. The mock
 * it replaces is explicit about why nothing else is here — its
 * {@code MockDB.updateUser} comment says <i>"Only the fields a profile form owns are
 * writable — never role, status or the id"</i>. This record makes that structural
 * rather than a rule somebody has to remember: <b>role and status are not fields of
 * this type</b>, so a caller cannot promote themselves by adding a key to the body.
 * {@link #canSignIn} — no such key exists; an unknown key is ignored rather than
 * bound, which {@code @JsonIgnoreProperties(ignoreUnknown = true)} states outright
 * (the same guard {@link AdminUserRequest} carries, and the reason sending
 * {@code "role": "ADMIN"} to this endpoint is a no-op instead of an error).
 *
 * <h2>Why blank is "unchanged" and not "clear"</h2>
 * <p>The page always submits all three fields, so blank only ever arrives from a
 * hand-written caller (Postman, a script). Reading blank as "clear it" would let a
 * typo wipe an administrator's email — the very address they sign in with — so the
 * service follows the convention {@link AdminUserRequest} already documents for the
 * roster's edit: <b>blank or absent means leave it alone</b>. A consequence worth
 * stating, because it is the desirable one: this endpoint can never remove an
 * account's last identifier, so it needs no "at least one of email or mobile" check.
 *
 * <h2>Where validation lives</h2>
 * <p>{@code name} is {@code @NotBlank} — the page's own validator refuses a blank
 * name first, and every other account write in this project requires one. The
 * {@code @Email} on {@code email} tolerates null and blank on purpose, because a
 * phone-only admin account is a legal row and blank means "unchanged" here. The
 * mobile number's rule is <b>not</b> a field annotation: the stored form is the
 * normalised 10 digits, and normalisation (stripping {@code +977}, spaces and
 * dashes) has to happen before the length can honestly be judged — so it is enforced
 * in {@code UserService.updateOwnProfile}, exactly where
 * {@code /api/auth/register} and {@code UserService.updateUser} enforce it, with the
 * frontend's own message.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProfileUpdateRequest(

        @NotBlank(message = "Full name is required.")
        String name,

        /* @Email accepts null and blank (Hibernate Validator treats "" as valid), which
           is what this needs: absent means unchanged, not malformed. */
        @Email(message = "Enter a valid email address.")
        String email,

        String phone
) {
}
