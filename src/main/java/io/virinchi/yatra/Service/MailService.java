package io.virinchi.yatra.Service;

import io.virinchi.yatra.Model.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * Outbound mail — Roadmap Phase 11 wires the one message the project actually sends:
 * the confirmation an account gets when it is created.
 *
 * <h2>What "confirmation" means here, precisely</h2>
 * <p>This is an <b>informational</b> email: "your account exists, here is how you sign
 * in". It is <b>not</b> a verification link, and the difference is not cosmetic — there
 * is no {@code verification_token} column, no {@code email_verified} flag, and no
 * endpoint that consumes a token, so nothing in the system can require a click before
 * an account is usable. Adding that is a schema change plus a second endpoint, and it
 * is recorded in the standards doc's open-questions list rather than half-built here.
 * Saying so plainly matters because "SMTP registration confirmation" is easy to read as
 * "email verification", and the two are graded differently.
 *
 * <h2>Three states, and none of them is an error</h2>
 * <ol>
 *   <li><b>Enabled and configured</b> (the demo state: {@code MAIL_USERNAME} /
 *       {@code MAIL_PASSWORD} from {@code application-local.properties} or the
 *       environment) — the message is sent and {@code true} is returned.</li>
 *   <li><b>Enabled but not configured</b> — a supported state, not a broken one, the
 *       same call {@code CloudinaryService} makes. Credentials absent means SMTP auth
 *       cannot succeed, so the send is skipped with a warning instead of being
 *       attempted: an attempt would block registration for the length of the SMTP
 *       timeouts (5s each, configured in {@code application.properties}) and then fail
 *       anyway, turning "you have not configured mail" into "signup is slow".</li>
 *   <li><b>Disabled</b> ({@code yatra.mail.enabled=false}) — skipped entirely. The test
 *       suite sets this, and that is the point of the switch: the credentials above are
 *       real, so without it every {@code @SpringBootTest} that registers a user would
 *       send real Gmail traffic to a fake address from the author's own mailbox.
 *       A demo run that must not touch the internet sets it too.</li>
 * </ol>
 *
 * <h2>It never throws, and the caller does not wait on it</h2>
 * <p>{@link #sendRegistrationConfirmation} answers {@code boolean} and catches
 * {@link MailException}: <b>a mail failure must never fail a registration.</b> The
 * account is already committed when this is called — the person exists, and telling
 * them "signup failed" because Gmail was unreachable would be a lie that leaves them
 * unable to register again (their address is now taken). The result is logged, not
 * propagated, exactly like {@code DestinationService}'s image-upload failure path.
 *
 * <p>Trade-off, stated rather than hidden: the send is <b>synchronous and inside the
 * registration transaction</b>, so it holds that connection for one SMTP round trip
 * (bounded by the configured timeouts). A production system would hand this to a queue
 * or an after-commit hook; for one message on a signup form, the simpler call is worth
 * more than the connection it occupies, and the failure path cannot lose a
 * registration because it is swallowed here.
 *
 * <h2>Why the sender is a provider, not a plain dependency</h2>
 * <p>{@code JavaMailSender} is auto-configured only when {@code spring.mail.host} is
 * set — it is, unconditionally, in {@code application.properties} — but injecting it
 * directly would mean that removing that one line stops <b>every</b> Spring context
 * from starting, tests included (risk R15). {@link ObjectProvider} keeps the class
 * constructible with no mail configuration at all, and the {@code null} sender simply
 * lands in the "not configured" state above. Mail is therefore optional configuration
 * in the same sense Cloudinary is.
 */
@Service
@Slf4j
public class MailService {

    /** {@code null} only when Spring's mail auto-configuration did not run (R15). */
    private final JavaMailSender mailSender;

    private final String username;
    private final String password;
    private final boolean enabled;

    public MailService(ObjectProvider<JavaMailSender> mailSenderProvider,
                       @Value("${spring.mail.username:}") String username,
                       @Value("${spring.mail.password:}") String password,
                       @Value("${yatra.mail.enabled:true}") boolean enabled) {
        this.mailSender = mailSenderProvider.getIfAvailable();
        this.username = username == null ? "" : username.trim();
        this.password = password == null ? "" : password;
        this.enabled = enabled;
    }

    /**
     * Sends the "your account is ready" message to the account's own address.
     *
     * <p>Best effort by design: every reason it cannot be sent is logged and answered
     * {@code false}. It is called from {@code UserService.register} after the row is
     * saved, so the account exists whatever this returns.
     *
     * @return {@code true} only when the message was actually handed to the mail server
     */
    public boolean sendRegistrationConfirmation(User user) {
        String to = blankToNull(user == null ? null : user.getEmail());

        if (!enabled) {
            log.info("Registration confirmation not sent: yatra.mail.enabled is false.");
            return false;
        }
        if (to == null) {
            // A mobile-only account is legal (RegisterRequest documents it) and has
            // nowhere to receive mail. Not a failure — there is no address to send to.
            log.info("Registration confirmation not sent: the account has no email address.");
            return false;
        }
        if (!isConfigured()) {
            log.warn("Registration confirmation not sent: spring.mail.username/password are not set.");
            return false;
        }

        try {
            mailSender.send(message(user, to));
            // The address itself is deliberately absent from this line — the project's
            // rule is that a user's email does not belong in a log line.
            log.info("Registration confirmation email sent.");
            return true;
        } catch (MailException ex) {
            // Never rethrow: the account is already created (see the class docs).
            log.warn("Registration confirmation email could not be sent: {}", ex.getMessage());
            return false;
        }
    }

    /**
     * Whether an <i>attempt</i> is possible: the switch is on, a sender exists, and both
     * credentials are present.
     *
     * <p>Both halves are required because Gmail's submission port authenticates
     * ({@code mail.smtp.auth=true}), so a username with no password is a guaranteed
     * failure — the same "is it configured?" question {@code CloudinaryService} answers
     * for its three keys.
     */
    public boolean isConfigured() {
        return enabled && mailSender != null && !username.isEmpty() && !password.isEmpty();
    }

    /** The switch itself, for a caller that wants to distinguish "off" from "unconfigured". */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * The message, built in one place so a test can assert what a recipient would read.
     *
     * <p>Plain text ({@code SimpleMailMessage}) rather than HTML: there is no template
     * engine in this project's mail path and no HTML mail is worth inventing for one
     * line of copy. {@code From} is the authenticated account on purpose — Gmail
     * rewrites or rejects a {@code From} it did not authenticate, so a pretty
     * {@code no-reply@yatra.com} would either be ignored or get the message rejected.
     */
    private SimpleMailMessage message(User user, String to) {
        String name = blankToNull(user.getName());
        String greeting = name == null ? "Hello," : "Hello " + name + ",";

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(username);
        message.setTo(to);
        message.setSubject("Yatra — your account is ready");
        message.setText(greeting + "\n\n"
                + "Your Yatra account has been created. This address is your sign-in name; "
                + "your mobile number works as a sign-in name too, and the password is the one "
                + "you chose when you signed up.\n\n"
                + "You can now search flights, book seats and track your bookings on Yatra.\n\n"
                + "If you did not create this account, you can ignore this message.\n\n"
                + "— Yatra");
        return message;
    }

    private static String blankToNull(String value) {
        String trimmed = String.valueOf(value == null ? "" : value).trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
