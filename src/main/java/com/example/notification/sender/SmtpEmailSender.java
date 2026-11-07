package com.example.notification.sender;

import com.example.notification.model.Channel;
import com.example.notification.model.DeliveryEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import jakarta.mail.Address;
import jakarta.mail.SendFailedException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Email over SMTP (also works with the SES SMTP interface or a local sandbox such as MailHog). */
@Component
@ConditionalOnProperty(name = "notification.provider.email.type", havingValue = "smtp")
public class SmtpEmailSender implements NotificationSender {

    private final JavaMailSender mail;
    private final String from;

    public SmtpEmailSender(ProviderProperties props, JavaMailSender mail) {
        this.from = TwilioSmsSender.require(props.email().smtp().from(), "notification.provider.email.smtp.from");
        this.mail = mail;
    }

    @Override
    public Channel channel() { return Channel.EMAIL; }

    @Override
    public void send(DeliveryEvent e) {
        SimpleMailMessage m = new SimpleMailMessage();
        m.setFrom(from);
        m.setTo(e.recipient());
        m.setSubject(e.subject() == null ? "" : e.subject());
        m.setText(e.body());
        try {
            mail.send(m);
        } catch (MailException ex) {
            if (isRecipientProblem(ex)) {
                throw new RecipientRejectedException("smtp rejected the recipient or message: " + ex.getClass().getSimpleName());
            }
            throw new ProviderException("smtp send failed: " + ex.getClass().getSimpleName(), -1);
        }
    }

    /** Invalid addresses and unusable messages are about this delivery; authentication and connection errors are about the server. */
    static boolean isRecipientProblem(MailException ex) {
        if (ex instanceof MailParseException || ex instanceof MailPreparationException) {
            return true;
        }
        if (ex instanceof MailAuthenticationException) {
            return false;
        }
        if (ex instanceof MailSendException mse) {
            for (Exception inner : mse.getMessageExceptions()) {
                if (hasInvalidAddresses(inner)) {
                    return true;
                }
            }
        }
        return hasInvalidAddresses(ex);
    }

    private static boolean hasInvalidAddresses(Throwable start) {
        for (Throwable t = start; t != null; t = t.getCause()) {
            if (t instanceof SendFailedException sfe) {
                Address[] invalid = sfe.getInvalidAddresses();
                return invalid != null && invalid.length > 0;
            }
        }
        return false;
    }
}
