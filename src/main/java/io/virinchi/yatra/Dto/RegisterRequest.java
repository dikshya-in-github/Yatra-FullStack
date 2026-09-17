package io.virinchi.yatra.Dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The body of {@code POST /api/auth/register} — exactly what signup.html sends.
 *
 * <p>signup.html has <b>two</b> forms (one per tab) and serialises whichever one
 * was submitted with {@code Object.fromEntries(new FormData(form).entries())},
 * then adds {@code method}. So the email tab sends {@code email} and the mobile
 * tab sends {@code phone} — they are not both always present, which is why the
 * "one of them is required" rule is enforced in the service (a cross-field rule
 * Bean Validation cannot express on a field) rather than with {@code @NotBlank}
 * here.
 *
 * <p><b>{@code title}, {@code dob} and {@code method} are accepted and
 * deliberately not stored.</b> The signup form submits all three, but the
 * {@code users} table has no column for them, and inventing one would be a schema
 * change to satisfy a payload rather than a requirement. {@code method} is kept
 * on the DTO because it documents which tab was used; the other two are absorbed
 * by {@code ignoreUnknown} so the contract can stay broader than the table.
 *
 * <p>The password minimum mirrors {@code PASSWORD_MIN = 8} in
 * {@code assets/js/validation.js} (and {@code minlength="8"} on the form), so the
 * client and server reject the same passwords.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RegisterRequest(

        String title,

        @NotBlank(message = "First name is required.")
        String firstName,

        String middleName,

        @NotBlank(message = "Last name is required.")
        String lastName,

        /* @Email allows null/blank on purpose: the mobile tab sends no email. */
        @Email(message = "Enter a valid email address.")
        String email,

        String phone,

        @NotBlank(message = "Password is required.")
        @Size(min = 8, message = "Password must be at least 8 characters.")
        String password,

        String method
) {
}
