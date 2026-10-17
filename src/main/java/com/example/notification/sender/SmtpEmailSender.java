package com.example.notification.sender;

import com.example.notification.model.Channel;
import com.example.notification.model.DeliveryEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
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
            throw new ProviderException("smtp send failed: " + ex.getClass().getSimpleName(), -1);
        }
    }
}
