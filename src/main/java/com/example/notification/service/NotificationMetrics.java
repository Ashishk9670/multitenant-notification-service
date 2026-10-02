package com.example.notification.service;

import com.example.notification.model.Channel;
import com.example.notification.model.Priority;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Per-tenant business metrics. Tenant cardinality is bounded because every tenant must hold an issued API key.
 * Exposed to Prometheus as notifications_{accepted,rejected,sent,failed,retried,dlq}_total.
 */
@Component
public class NotificationMetrics {

    private final MeterRegistry registry;

    public NotificationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void accepted(String tenant, Priority priority) {
        registry.counter("notifications.accepted", "tenant", tenant, "priority", priority.name()).increment();
    }

    /** reason: rate_limited | quota_exceeded | publish_failed */
    public void rejected(String tenant, String reason) {
        registry.counter("notifications.rejected", "tenant", tenant, "reason", reason).increment();
    }

    public void sent(String tenant, Channel channel) {
        registry.counter("notifications.sent", "tenant", tenant, "channel", channel.name()).increment();
    }

    /** One failed delivery attempt (it is then either retried or dead-lettered). */
    public void failed(String tenant, Channel channel) {
        registry.counter("notifications.failed", "tenant", tenant, "channel", channel.name()).increment();
    }

    public void retried(String tenant, Channel channel) {
        registry.counter("notifications.retried", "tenant", tenant, "channel", channel.name()).increment();
    }

    public void deadLettered(String tenant, Channel channel) {
        registry.counter("notifications.dlq", "tenant", tenant, "channel", channel.name()).increment();
    }
}
