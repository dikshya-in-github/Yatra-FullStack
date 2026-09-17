package io.virinchi.yatra;

import io.virinchi.yatra.Model.User;
import io.virinchi.yatra.Service.MailService;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.Multipart;
import jakarta.mail.Session;
import jakarta.mail.Store;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Roadmap Phase 11's one manual checkpoint, made runnable: <b>the confirmation email is
 * actually sent and actually arrives.</b>
 *
 * <h2>Why this class exists at all</h2>
 * <p>{@code MailServiceTest} pins the message, its {@code From} and all four skip paths with
 * mocks, and {@code AuthFlowTest} proves registration is unaffected by mail being off — but
 * neither can answer "does Gmail accept it, and does it land?", which is <i>the</i> claim the
 * phase makes. That question is live, it needs credentials, and the roadmap recorded it as
 * not-run for exactly that reason. It is answered here instead of by hand, so the answer can
 * be reproduced rather than remembered.
 *
 * <h2>It never runs by itself — and that is the switch, not a comment</h2>
 * <p>{@link EnabledIfSystemProperty} means {@code ./mvnw test} <b>cannot</b> send anything,
 * whatever is configured on the machine:
 *
 * <pre>
 * ./mvnw test -Dtest=LiveMailProofTest#sendsTheConfirmationThroughGmailAndFindsItInTheMailbox -Dyatra.live-mail=true
 * ./mvnw test -Dtest=LiveMailProofTest#reportsTheNewestConfirmationInTheMailbox          -Dyatra.live-mail=true
 * </pre>
 *
 * <p>The class is deliberately named {@code …Test} rather than something surefire ignores, so
 * the default run reports it as a <b>skip</b> — a visible "there is a live check here that did
 * not run" instead of an invisible file nobody remembers to run. The property is the second
 * gate: the first line above selects the class, the second arms it. Belt and braces, because
 * the failure mode this protects against (a stray Gmail send from a test run) is one the
 * project already decided is unacceptable — see {@code yatra.mail.enabled} in
 * {@code application.properties}.
 *
 * <h2>What it proves, in two halves</h2>
 * <ol>
 *   <li><b>SMTP.</b> A genuine {@link JavaMailSenderImpl}, built from the same
 *       {@code spring.mail.*} keys Boot binds, is handed to the <i>real</i>
 *       {@link MailService} and {@code sendRegistrationConfirmation} is called. {@code true}
 *       means the SMTP round trip completed — Gmail authenticated, accepted the message and
 *       the server closed the transaction — because the method swallows every
 *       {@code MailException} and answers {@code false} instead.</li>
 *   <li><b>Delivery.</b> The mailbox is then read back over IMAPS and the message has to be
 *       <i>there</i>: same subject, and a body containing the copy the project composes. This
 *       is the half that makes it a proof rather than a promise — an accepted message can
 *       still be dropped, and "the API answered 200" never showed anything arriving.</li>
 * </ol>
 *
 * <h2>Why the recipient is the sending mailbox</h2>
 * <p>The credentials this project has are one Gmail account's app password, so the only inbox
 * that can be read back is that account's own — a send from it to itself. That is a real
 * limitation and worth naming: it exercises the same SMTP path a signup uses (same sender,
 * same message, same code), but it does not prove delivery <i>to a third party</i>, where a
 * spam filter could still have an opinion. Nothing here can close that gap; the honest
 * statement is what stands.
 *
 * <h2>Why there is no Spring context</h2>
 * <p>The same reasoning {@code MailServiceTest} gives: a {@code @SpringBootTest} would load
 * JPA against the live TiDB Cloud schema and bind the mail properties through Boot, and all
 * this check needs is the sender and the message. Properties are read from
 * {@code application.properties} and then the git-ignored {@code application-local.properties}
 * <b>in Boot's own precedence order</b> (later file wins, environment over both), so the run
 * uses the same credentials the application does without a container deciding that for us.
 *
 * <h2>No SMTP debug output, on purpose</h2>
 * <p>{@code mail.debug=true} would print the AUTH exchange — the app password in base64 — into
 * a terminal that the whole point of this exercise is to screenshot. The evidence here is the
 * receipt instead: the boolean from {@code MailService} plus the message read back out of the
 * inbox.
 */
@EnabledIfSystemProperty(named = "yatra.live-mail", matches = "true")
class LiveMailProofTest {

    /** Exactly the subject {@code MailService} composes — the message is found by it. */
    private static final String CONFIRMATION_SUBJECT = "Yatra — your account is ready";

    /** Gmail's IMAP endpoint. Not in {@code application.properties}: the app only ever sends. */
    private static final String IMAP_HOST = "imap.gmail.com";
    private static final int IMAP_PORT = 993;

    /** How long Gmail's own self-delivery may take before the check calls it lost. */
    private static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(90);

    /** How far back the mailbox is scanned. Gmail keeps thousands; the newest 50 are enough. */
    private static final int SCAN_DEPTH = 50;

    /* ------------------------------------------------------------------ *
     *  the send that must happen, and the delivery that must follow       *
     * ------------------------------------------------------------------ */

    @Test
    void sendsTheConfirmationThroughGmailAndFindsItInTheMailbox() throws Exception {
        Properties config = configuration();
        Credentials credentials = credentials(config);

        MailService mail = mailService(gmailSender(config, credentials), credentials);

        assertThat(mail.isConfigured())
                .as("a sender, a username and a password were all read from the configuration")
                .isTrue();

        // A minute of slack for clock skew between this machine and Gmail's headers.
        Date watermark = Date.from(Instant.now().minusSeconds(60));

        boolean sent = mail.sendRegistrationConfirmation(account(credentials.username()));

        report("recipient", credentials.username());
        report("MailService.sendRegistrationConfirmation(...) returned", sent);
        assertThat(sent)
                .as("Gmail accepted the message — an authentication or handshake failure would "
                        + "have been swallowed into a false")
                .isTrue();

        Delivered delivered = awaitDelivery(credentials, watermark);

        report("delivered subject", delivered.subject());
        report("delivered received-date", delivered.received());
        report("delivered from/to", delivered.from() + " -> " + delivered.to());

        assertThat(delivered.subject()).isEqualTo(CONFIRMATION_SUBJECT);
        assertThat(delivered.body())
                .as("the bytes in the inbox are the copy MailService composes")
                .contains("Your Yatra account has been created", "sign-in name");
    }

    /* ------------------------------------------------------------------ *
     *  the read-only half, on its own                                     *
     * ------------------------------------------------------------------ */

    /**
     * Shows the newest confirmation already sitting in the mailbox and sends nothing.
     *
     * <p>This is the check that covers the <i>other</i> producer: a message sent by
     * {@code POST /api/auth/register} on a running application. That run is the end-to-end
     * checkpoint, and this method is how its message is confirmed after the fact — reading a
     * mailbox cannot create traffic, so it is safe to point at a live account at any time.
     */
    @Test
    void reportsTheNewestConfirmationInTheMailbox() throws Exception {
        Credentials credentials = credentials(configuration());

        Optional<Delivered> newest = newestConfirmation(credentials, new Date(0));

        assertThat(newest)
                .as("at least one 'Yatra — your account is ready' message exists in %s",
                        credentials.username())
                .isPresent();

        Delivered delivered = newest.orElseThrow();
        report("newest confirmation subject", delivered.subject());
        report("newest confirmation received", delivered.received());
        report("newest confirmation from/to", delivered.from() + " -> " + delivered.to());
        report("newest confirmation body (first line)",
                String.valueOf(delivered.body()).lines().findFirst().orElse(""));
    }

    /* ------------------------------------------------------------------ *
     *  the mailbox, read over IMAPS                                       *
     * ------------------------------------------------------------------ */

    /**
     * Waits for the message to appear, then returns what was read.
     *
     * <p>Each attempt opens its own connection and re-reads the folder rather than polling an
     * open one: Gmail exposes new mail to a fresh {@code EXAMINE}, and a stale view is the
     * one way this check could report a false negative.
     */
    private static Delivered awaitDelivery(Credentials credentials, Date since) throws Exception {
        Instant deadline = Instant.now().plus(DELIVERY_TIMEOUT);

        while (true) {
            Optional<Delivered> found = newestConfirmation(credentials, since);
            if (found.isPresent()) {
                return found.get();
            }
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("Gmail accepted the message but it had not appeared in "
                        + "IMAP INBOX of " + credentials.username() + " after "
                        + DELIVERY_TIMEOUT.toSeconds() + "s. Note that IMAP access must be enabled "
                        + "on the account for this half of the check to be able to run at all.");
            }
            Thread.sleep(5_000);
        }
    }

    /**
     * The newest message in INBOX that is the confirmation and is not older than {@code since},
     * reduced to plain values before the connection closes.
     *
     * <p>Reduction matters: a {@link Message} is only readable while its folder is open, so
     * returning one would hand the caller an object that fails on the next line.
     */
    private static Optional<Delivered> newestConfirmation(Credentials credentials, Date since)
            throws Exception {

        Session session = Session.getInstance(new Properties());

        try (Store store = session.getStore("imaps")) {
            store.connect(IMAP_HOST, IMAP_PORT, credentials.username(), credentials.password());

            Folder inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_ONLY);
            try {
                int count = inbox.getMessageCount();
                for (int index = count; index > Math.max(0, count - SCAN_DEPTH); index--) {
                    Message message = inbox.getMessage(index);

                    if (!CONFIRMATION_SUBJECT.equals(message.getSubject())) {
                        continue;
                    }
                    Date received = receivedAt(message);
                    if (received == null || received.before(since)) {
                        continue;
                    }
                    // Newest first, so the first match is the one this run is looking for.
                    return Optional.of(new Delivered(
                            message.getSubject(),
                            bodyOf(message),
                            String.valueOf(message.getFrom()[0]),
                            message.getAllRecipients() == null
                                    ? ""
                                    : String.valueOf(message.getAllRecipients()[0]),
                            received));
                }
                return Optional.empty();
            } finally {
                inbox.close(false);
            }
        }
    }

    /** Gmail fills {@code Received} in its own store; {@code Date} is the fallback. */
    private static Date receivedAt(Message message) throws Exception {
        Date received = message.getReceivedDate();
        return received != null ? received : message.getSentDate();
    }

    /** {@code SimpleMailMessage} sends {@code text/plain}; the multipart walk covers a Gmail rewrite. */
    private static String bodyOf(Message message) throws Exception {
        Object content = message.getContent();
        if (content instanceof String text) {
            return text;
        }
        if (content instanceof Multipart multipart) {
            StringBuilder body = new StringBuilder();
            for (int part = 0; part < multipart.getCount(); part++) {
                Object partContent = multipart.getBodyPart(part).getContent();
                body.append(partContent instanceof String text ? text : String.valueOf(partContent));
            }
            return body.toString();
        }
        return String.valueOf(content);
    }

    /* ------------------------------------------------------------------ *
     *  configuration, exactly as Boot resolves it                         *
     * ------------------------------------------------------------------ */

    /**
     * {@code application.properties}, then the git-ignored {@code application-local.properties}
     * over the top (the {@code local} profile {@code application.properties} activates), then
     * the environment over both — Boot's own precedence, applied by hand.
     */
    private static Properties configuration() {
        Properties config = new Properties();
        load(config, "application.properties");
        load(config, "application-local.properties");
        override(config, "spring.mail.username", System.getenv("MAIL_USERNAME"));
        override(config, "spring.mail.password", System.getenv("MAIL_PASSWORD"));
        return config;
    }

    private static void load(Properties into, String resource) {
        try (InputStream stream = LiveMailProofTest.class.getResourceAsStream("/" + resource)) {
            if (stream != null) {
                into.load(stream);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Could not read " + resource, ex);
        }
    }

    private static void override(Properties into, String key, String value) {
        if (value != null && !value.isBlank()) {
            into.setProperty(key, value);
        }
    }

    private static Credentials credentials(Properties config) {
        String username = required(config, "spring.mail.username").trim();
        // Not trimmed, matching MailService's own handling: an app password is a credential, and
        // Gmail shows it in space-separated groups, so a run that failed here is the signal that
        // the stored value needs its spaces removed rather than silently "fixed" for the check.
        String password = required(config, "spring.mail.password");
        return new Credentials(username, password);
    }

    private static String required(Properties config, String key) {
        String value = config.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new AssertionError(key + " is not set — this check needs the same credentials "
                    + "the application uses (application-local.properties, or MAIL_USERNAME / "
                    + "MAIL_PASSWORD in the environment).");
        }
        return value;
    }

    /**
     * A real {@link JavaMailSenderImpl} over the configured keys — the class Boot's own mail
     * auto-configuration instantiates, so the check exercises production's sender rather than a
     * test double.
     */
    private static JavaMailSender gmailSender(Properties config, Credentials credentials) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(required(config, "spring.mail.host"));
        sender.setPort(Integer.parseInt(config.getProperty("spring.mail.port", "587")));
        sender.setUsername(credentials.username());
        sender.setPassword(credentials.password());

        Properties session = new Properties();
        for (String name : config.stringPropertyNames()) {
            if (name.startsWith("spring.mail.properties.")) {
                session.setProperty(
                        name.substring("spring.mail.properties.".length()), config.getProperty(name));
            }
        }
        sender.setJavaMailProperties(session);
        return sender;
    }

    private static MailService mailService(JavaMailSender sender, Credentials credentials) {
        return new MailService(provider(sender), credentials.username(), credentials.password(), true);
    }

    /** The production shape: the sender arrives as a provider, not as a required dependency (R15). */
    private static ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        factory.registerSingleton("mailSender", sender);
        return factory.getBeanProvider(JavaMailSender.class);
    }

    /** A registered account, as {@code UserService.register} would have saved it. */
    private static User account(String email) {
        User user = new User();
        user.setName("Yatra live mail check");
        user.setEmail(email);
        user.setRole("USER");
        user.setStatus("Active");
        return user;
    }

    private static void report(String label, Object value) {
        System.out.println("[live-mail] " + label + ": " + value);
    }

    private record Credentials(String username, String password) {
    }

    private record Delivered(String subject, String body, String from, String to, Date received) {
    }
}
