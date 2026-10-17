package com.example.notification.webhook;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "notification.webhook")
public record WebhookProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("5") int maxAttempts,
        @DefaultValue("1s") Duration initialDelay,
        @DefaultValue("2.0") double multiplier,
        @DefaultValue("5m") Duration maxDelay,
        @DefaultValue("0.1") double jitter,
        @DefaultValue("5s") Duration timeout) {
}
