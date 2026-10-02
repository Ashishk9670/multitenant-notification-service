package com.example.notification.model;

import java.time.Instant;

/** Single-channel delivery unit; flows through channel topics, retry topics and the DLQ. */
public record DeliveryEvent(
        String id,
        String tenantId,
        Channel channel,
        Priority priority,
        String recipient,
        String subject,
        String body,
        int attempt,
        Instant createdAt,
        String lastError) {

    public DeliveryEvent withAttempt(int newAttempt, String error) {
        return new DeliveryEvent(id, tenantId, channel, priority, recipient, subject, body, newAttempt, createdAt, error);
    }
}
