package com.example.notification.model;

import java.time.Instant;
import java.util.Map;

/** Published to notifications.{high|normal|low}; fanned out per channel by the router. */
public record NotificationEvent(
        String id,
        String tenantId,
        Priority priority,
        Map<Channel, Content> contents,
        Recipients recipients,
        Instant createdAt) { }
