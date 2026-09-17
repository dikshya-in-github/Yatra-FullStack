package io.virinchi.yatra;

import io.virinchi.yatra.Model.User;
import io.virinchi.yatra.Service.MailService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Roadmap Phase 11 — what {@code MailService} sends, and all the ways it deliberately does
 * not.
 *
 * <p><b>A plain unit test with no Spring context, and that is a decision.</b> A
 * {@code @SpringBootTest} here would load the real {@code JavaMailSender} — configured with
 * the author's own Gmail credentials on this machine — and the only way to keep it from
 * sending would be to mock the sender out, at which point the context proves nothing the
 * direct construction does not. The message itself is a value; asserting it needs the value,
 * not a container.
 *
 * <p>{@link DefaultListableBeanFactory} is used purely to produce a real
 * {@link ObjectProvider} for the constructor — registered for the "configured" cases, empty
 * for the "no mail configuration" one. That keeps the production shape (a provider, so a
 * context with no {@code spring.mail.host} still starts — risk R15) while letting the test
 * choose which of the three states {@code MailService} is in.
 *
 * <p>What is <b>not</b> asserted here: that Gmail accepts the message. That is a live,
 * credential-bearing check and it belongs to the roadmap's manual checkpoint, not to a test
 * suite — the suite's job is that the code composes and gates the message correctly, and
 * {@code AuthFlowTest} already proves registration is unaffected by mail being off.
 */
class MailServiceTest {

    private static final String USERNAME = "yatra.demo@gmail.com";
    private static final String PASSWORD = "app-password";

    /* ------------------------------------------------------------------ *
     *  the send that happens                                              *
     * ------------------------------------------------------------------ */

    @Test
    void sendsTheConfirmationToTheAccountsOwnAddress() {
        JavaMailSender sender = mock(JavaMailSender.class);
        MailService mail = mailService(sender, USERNAME, PASSWORD, true);

        boolean sent = mail.sendRegistrationConfirmation(user("Dikshya Ghising", "dikshya@example.com"));

        assertThat(sent).as("the message was handed to the mail server").isTrue();

        SimpleMailMessage message = captured(sender);
        assertThat(message.getTo()).containsExactly("dikshya@example.com");
        assertThat(message.getSubject()).contains("Yatra");
        assertThat(message.getText()).contains("Hello Dikshya Ghising,");
        assertThat(message.getText()).as("says how to sign in").contains("sign-in name");
    }

    /**
     * Gmail only accepts a {@code From} it authenticated, so the sender has to be the
     * configured account — a prettier {@code no-reply@yatra.com} would be rewritten or
     * rejected at the server.
     */
    @Test
    void sendsFromTheAuthenticatedAccount() {
        JavaMailSender sender = mock(JavaMailSender.class);
        MailService mail = mailService(sender, USERNAME, PASSWORD, true);

        mail.sendRegistrationConfirmation(user("Sita Rai", "sita@example.com"));

        assertThat(captured(sender).getFrom()).isEqualTo(USERNAME);
    }

    /** An account with no name still gets a readable message, not "Hello null,". */
    @Test
    void greetsAnAccountWithNoNameWithoutPrintingNull() {
        JavaMailSender sender = mock(JavaMailSender.class);
        MailService mail = mailService(sender, USERNAME, PASSWORD, true);

        User anonymous = user(null, "nameless@example.com");
        mail.sendRegistrationConfirmation(anonymous);

        assertThat(captured(sender).getText()).startsWith("Hello,").doesNotContain("null");
    }

    /* ------------------------------------------------------------------ *
     *  the sends that deliberately do not happen                          *
     * ------------------------------------------------------------------ */

    /**
     * The switch the test suite and a no-mailbox demo rely on: no attempt is made at all,
     * so nothing can reach Gmail from a test run.
     */
    @Test
    void skipsEverythingWhenMailIsDisabled() {
        JavaMailSender sender = mock(JavaMailSender.class);
        MailService mail = mailService(sender, USERNAME, PASSWORD, false);

        boolean sent = mail.sendRegistrationConfirmation(user("Dikshya", "dikshya@example.com"));

        assertThat(sent).isFalse();
        assertThat(mail.isConfigured()).as("disabled is not configured").isFalse();
        verify(sender, never()).send(any(SimpleMailMessage.class));
    }

    /**
     * Enabled but unconfigured is a supported state, not a broken one: the send is skipped
     * rather than attempted, so signup is not held for the SMTP timeouts only to fail
     * anyway.
     */
    @Test
    void skipsWhenTheCredentialsAreMissing() {
        JavaMailSender sender = mock(JavaMailSender.class);
        MailService mail = mailService(sender, "", "", true);

        boolean sent = mail.sendRegistrationConfirmation(user("Dikshya", "dikshya@example.com"));

        assertThat(sent).isFalse();
        assertThat(mail.isConfigured()).isFalse();
        verify(sender, never()).send(any(SimpleMailMessage.class));
    }

    /** With no mail auto-configuration at all, the class still constructs and skips (R15). */
    @Test
    void skipsWhenThereIsNoMailSenderBean() {
        MailService mail = new MailService(provider(null), USERNAME, PASSWORD, true);

        assertThat(mail.isConfigured()).isFalse();
        assertThat(mail.sendRegistrationConfirmation(user("Dikshya", "dikshya@example.com"))).isFalse();
    }

    /** A mobile-only account is legal and has nowhere to receive mail — not a failure. */
    @Test
    void skipsAnAccountWithNoEmailAddress() {
        JavaMailSender sender = mock(JavaMailSender.class);
        MailService mail = mailService(sender, USERNAME, PASSWORD, true);

        boolean sent = mail.sendRegistrationConfirmation(user("Phone Only", null));

        assertThat(sent).isFalse();
        verify(sender, never()).send(any(SimpleMailMessage.class));
    }

    /**
     * The rule the whole class is shaped around: a mail failure is reported, never thrown.
     * The registration calling this has already committed, so an unreachable SMTP server
     * must not turn a created account into a failed signup — the person would be told to
     * try again and their address would now be taken.
     */
    @Test
    void aMailFailureIsSwallowedAndReported() {
        JavaMailSender sender = mock(JavaMailSender.class);
        doThrow(new MailSendException("Authentication failed"))
                .when(sender).send(any(SimpleMailMessage.class));

        MailService mail = mailService(sender, USERNAME, PASSWORD, true);

        boolean sent = mail.sendRegistrationConfirmation(user("Dikshya", "dikshya@example.com"));

        assertThat(sent).as("the failure is reported to the caller, not thrown at it").isFalse();
    }

    /* ------------------------------------------------------------------ *
     *  helpers                                                            *
     * ------------------------------------------------------------------ */

    private static MailService mailService(JavaMailSender sender, String username, String password,
                                           boolean enabled) {
        return new MailService(provider(sender), username, password, enabled);
    }

    /**
     * A real {@link ObjectProvider} from a throwaway bean factory: one registered
     * {@link JavaMailSender}, or none at all — which is exactly how the production
     * constructor sees a context with and without mail auto-configuration.
     */
    private static ObjectProvider<JavaMailSender> provider(JavaMailSender sender) {
        DefaultListableBeanFactory factory = new DefaultListableBeanFactory();
        if (sender != null) {
            factory.registerSingleton("mailSender", sender);
        }
        return factory.getBeanProvider(JavaMailSender.class);
    }

    private static SimpleMailMessage captured(JavaMailSender sender) {
        ArgumentCaptor<SimpleMailMessage> captor = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(captor.capture());
        return captor.getValue();
    }

    private static User user(String name, String email) {
        User user = new User();
        user.setName(name);
        user.setEmail(email);
        user.setRole("USER");
        user.setStatus("Active");
        return user;
    }
}
