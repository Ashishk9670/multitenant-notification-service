package com.example.notification.sender;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.mail.Address;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.InternetAddress;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;

/** Which SMTP failures are about one recipient (ignored by the circuit breaker) and which are about the server. */
class SmtpRecipientClassificationTest {

    @Test
    void invalidAddressIsARecipientProblem() throws Exception {
        SendFailedException invalid = new SendFailedException("550 no such user", null, new Address[0], new Address[0],
                new Address[] {new InternetAddress("nobody@example.com")});
        assertThat(SmtpEmailSender.isRecipientProblem(new MailSendException("failed", invalid))).isTrue();
    }

    @Test
    void invalidAddressInsideAMultiMessageFailureIsARecipientProblem() throws Exception {
        SendFailedException invalid = new SendFailedException("550", null, new Address[0], new Address[0],
                new Address[] {new InternetAddress("nobody@example.com")});
        var failed = new LinkedHashMap<Object, Exception>();
        failed.put("message", invalid);
        assertThat(SmtpEmailSender.isRecipientProblem(new MailSendException("failed", null, failed))).isTrue();
    }

    @Test
    void unparseableMessageIsARecipientProblem() {
        assertThat(SmtpEmailSender.isRecipientProblem(new MailParseException("bad header"))).isTrue();
    }

    @Test
    void connectionFailureIsAServerProblem() {
        assertThat(SmtpEmailSender.isRecipientProblem(new MailSendException("Couldn't connect to host", new MessagingException("connect failed")))).isFalse();
    }

    @Test
    void authenticationFailureIsAServerProblem() {
        assertThat(SmtpEmailSender.isRecipientProblem(new MailAuthenticationException("535 bad credentials"))).isFalse();
    }

    @Test
    void sendFailedWithoutInvalidAddressesIsAServerProblem() {
        SendFailedException transientFailure = new SendFailedException("452 try again later");
        assertThat(SmtpEmailSender.isRecipientProblem(new MailSendException("failed", transientFailure))).isFalse();
    }
}
