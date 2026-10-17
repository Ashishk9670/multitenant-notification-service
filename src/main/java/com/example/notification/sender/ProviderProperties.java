package com.example.notification.sender;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Selects the provider behind each channel. Every channel defaults to "stub" (log only, no credentials needed);
 * real adapters are opt-in, e.g. notification.provider.sms.type=twilio. Credentials come from configuration/env
 * and are never logged.
 */
@ConfigurationProperties(prefix = "notification.provider")
public record ProviderProperties(
        @DefaultValue Email email,
        @DefaultValue Sms sms,
        @DefaultValue Push push,
        @DefaultValue("10s") Duration timeout) {

    /** type: stub | smtp | ses */
    public record Email(@DefaultValue("stub") String type, @DefaultValue Ses ses, @DefaultValue Smtp smtp) { }

    /** AWS SES v2 REST API (SigV4). endpoint is overridable for sandboxes and tests. */
    public record Ses(String region, String accessKeyId, String secretAccessKey, String from, String endpoint) { }

    /** Uses Spring's JavaMailSender; configure the server with spring.mail.*. */
    public record Smtp(String from) { }

    /** type: stub | twilio */
    public record Sms(@DefaultValue("stub") String type, @DefaultValue Twilio twilio) { }

    public record Twilio(String accountSid, String authToken, String from,
                         @DefaultValue("https://api.twilio.com") String baseUrl) { }

    /** type: stub | fcm */
    public record Push(@DefaultValue("stub") String type, @DefaultValue Fcm fcm) { }

    /** FCM HTTP v1; accessToken is an OAuth2 bearer token (e.g. from a service account) supplied via config. */
    public record Fcm(String projectId, String accessToken,
                      @DefaultValue("https://fcm.googleapis.com") String baseUrl) { }
}
