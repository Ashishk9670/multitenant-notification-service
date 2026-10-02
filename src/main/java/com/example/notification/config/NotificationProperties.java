package com.example.notification.config;

import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("notification")
public record NotificationProperties(
        Topics topics,
        Consumers consumers,
        Retry retry,
        RateLimit rateLimit,
        Quota quota,
        @DefaultValue("24h") Duration idempotencyTtl) {

    public record Topics(String high, String normal, String low, String email, String sms, String push,
                         String retryPrefix, String retryPattern, String dlq) { }

    public record Consumers(@DefaultValue("6") int high, @DefaultValue("3") int normal, @DefaultValue("1") int low) { }

    public record Retry(@DefaultValue("3") int maxRetries,
                        @DefaultValue("2s") Duration initialDelay,
                        @DefaultValue("2.0") double multiplier,
                        @DefaultValue("5m") Duration maxDelay,
                        @DefaultValue("0.1") double jitter) { }

    public record Limit(@DefaultValue("20") double capacity, @DefaultValue("10") double refillPerSecond) { }

    public record RateLimit(@DefaultValue("20") double capacity,
                            @DefaultValue("10") double refillPerSecond,
                            @DefaultValue Map<String, Limit> tenants) {
        public Limit forTenant(String tenantId) {
            Limit l = tenants == null ? null : tenants.get(tenantId);
            return l != null ? l : new Limit(capacity, refillPerSecond);
        }
    }

    public record Quota(@DefaultValue("10000") long dailyLimit, @DefaultValue Map<String, Long> tenants) {
        public long forTenant(String tenantId) {
            Long l = tenants == null ? null : tenants.get(tenantId);
            return l != null ? l : dailyLimit;
        }
    }
}
