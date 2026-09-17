package io.virinchi.yatra.Dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The body of {@code POST /api/admin/users} and {@code PUT /api/admin/users/{id}}
 * — what {@code admin-users.html}'s add/edit modal submits, plus two optional
 * fields the page never sends.
 *
 * <h2>Field for field what the page sends</h2>
 * <p>{@code admin-users.js} builds its payload as
 * {@code { name, email, phone, role, status }} — the five keys it validates in the
 * browser first. There is no {@code id} (it is the path on update) and no
 * {@code registeredAt} (the server owns the clock, and the mock's
 * {@code new Date().toISOString()} is the page inventing a timestamp the database
 * already knows how to produce).
 *
 * <h2>The two additions, and exactly what they are for</h2>
 * <ul>
 *   <li><b>{@code password} (optional).</b> The page deliberately has no password
 *       field — its own header says passwords belong to the backend's BCrypt and
 *       are never stored or displayed in the panel. So the panel's created account
 *       has <b>no credential at all</b>, and this field is the way out of the trap
 *       that creates: an account with no password cannot sign in, yet it has already
 *       taken the email/phone the person would have registered with, so they cannot
 *       register either. An admin provisioning a usable login (Postman, a script, or
 *       a later "reset" screen) sends one here and it is hashed exactly like
 *       {@code /api/auth/register}'s. Omitted or blank means "leave the stored hash
 *       alone" — never "set the password to blank".</li>
 *   <li><b>Blank means unchanged on update.</b> The page always sends all five
 *       fields, so this only matters to a hand-written caller: {@code null} and
 *       {@code ""} both mean "I am not changing this field", which is the opposite
 *       of clearing it. Stated plainly because the alternative reading (blank =
 *       clear) would let a typo in a Postman body wipe an account's phone
 *       number.</li>
 * </ul>
 *
 * <h2>Validation lives in two places on purpose</h2>
 * <p>{@code name} is {@code @NotBlank} here because it is required in every case.
 * The email/mobile rule is <b>not</b> expressible as a field annotation — the page
 * requires an email today, but the schema and {@code /api/auth/register} both allow
 * a phone-only account, so "at least one of the two" is enforced in
 * {@code UserService}, the same cross-field rule and the same place
 * {@code RegisterRequest} documents. The {@code @Pattern}s on {@code role} and
 * {@code status} accept blank as well as an absent value (the trailing {@code ?}),
 * because both are optional here; an unknown <i>value</i> is a 400 before the
 * service runs, which is the honest status for a malformed body.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AdminUserRequest(

        @NotBlank(message = "Full name is required.")
        String name,

        /* @Email tolerates null/blank: a phone-only account is legal, and update
           treats blank as "unchanged". */
        @Email(message = "Enter a valid email address.")
        String email,

        String phone,

        @Pattern(regexp = "(?i)\\s*(ADMIN|USER)?\\s*",
                message = "Role must be ADMIN or USER.")
        String role,

        @Pattern(regexp = "(?i)\\s*(Active|Inactive)?\\s*",
                message = "Status must be Active or Inactive.")
        String status,

        /*
         * Optional, and blank is a value rather than an error — so this is a @Pattern and
         * not @Size(min = 8), which would reject "" as a six-characters-too-short password
         * even though the service reads blank as "no password supplied". The alternation is
         * the whole rule: the empty string, or eight or more characters. (?s) so a password
         * containing a newline is measured as one line rather than rejected by the dot.
         */
        @Pattern(regexp = "(?s)(|.{8,})", message = "Password must be at least 8 characters.")
        String password
) {
}
