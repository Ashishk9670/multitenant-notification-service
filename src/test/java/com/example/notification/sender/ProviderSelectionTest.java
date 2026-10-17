package com.example.notification.sender;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

class ProviderSelectionTest {

    @Configuration
    @EnableConfigurationProperties(ProviderProperties.class)
    static class Cfg { }

    ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Cfg.class, ProviderHttp.class, LogSenders.Email.class, LogSenders.Sms.class,
                    LogSenders.Push.class, TwilioSmsSender.class, FcmPushSender.class, SmtpEmailSender.class,
                    SesEmailSender.class)
            .withBean(JavaMailSender.class, JavaMailSenderImpl::new);

    @Test
    void defaultsToStubsWithoutAnyCredentials() {
        runner.run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(NotificationSender.class).values())
                    .extracting(Object::getClass).containsExactlyInAnyOrder(
                            LogSenders.Email.class, LogSenders.Sms.class, LogSenders.Push.class);
        });
    }

    @Test
    void realAdaptersReplaceOnlyTheirOwnChannelsStub() {
        runner.withPropertyValues(
                "notification.provider.sms.type=twilio",
                "notification.provider.sms.twilio.account-sid=AC1",
                "notification.provider.sms.twilio.auth-token=t",
                "notification.provider.sms.twilio.from=+1").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(NotificationSender.class).values())
                    .extracting(Object::getClass).containsExactlyInAnyOrder(
                            LogSenders.Email.class, TwilioSmsSender.class, LogSenders.Push.class);
        });
    }

    @Test
    void allThreeRealAdaptersCanBeSelected() {
        runner.withPropertyValues(
                "notification.provider.email.type=ses",
                "notification.provider.email.ses.region=us-east-1",
                "notification.provider.email.ses.access-key-id=k",
                "notification.provider.email.ses.secret-access-key=s",
                "notification.provider.email.ses.from=f@example.com",
                "notification.provider.sms.type=twilio",
                "notification.provider.sms.twilio.account-sid=AC1",
                "notification.provider.sms.twilio.auth-token=t",
                "notification.provider.sms.twilio.from=+1",
                "notification.provider.push.type=fcm",
                "notification.provider.push.fcm.project-id=p",
                "notification.provider.push.fcm.access-token=t").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBeansOfType(NotificationSender.class).values())
                    .extracting(Object::getClass).containsExactlyInAnyOrder(
                            SesEmailSender.class, TwilioSmsSender.class, FcmPushSender.class);
        });
    }

    @Test
    void smtpIsSelectableAndMissingCredentialsFailFastAtStartup() {
        runner.withPropertyValues("notification.provider.email.type=smtp", "notification.provider.email.smtp.from=a@b.com")
                .run(ctx -> assertThat(ctx.getBean(SmtpEmailSender.class).channel())
                        .isEqualTo(com.example.notification.model.Channel.EMAIL));
        runner.withPropertyValues("notification.provider.push.type=fcm").run(ctx -> assertThat(ctx).hasFailed());
    }
}
