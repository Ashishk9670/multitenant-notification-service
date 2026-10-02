package com.example.notification.service;

import com.example.notification.model.Channel;
import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "deliveries")
public class Delivery {
    @Id
    private String id;
    @Column(name = "notification_id", nullable = false)
    private String notificationId;
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryStatus status = DeliveryStatus.ACCEPTED;
    @Column(nullable = false)
    private int attempts;
    @Column(name = "last_error")
    private String lastError;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Delivery() { }

    public Delivery(String id, String notificationId, String tenantId, Channel channel, Instant createdAt) {
        this.id = id;
        this.notificationId = notificationId;
        this.tenantId = tenantId;
        this.channel = channel;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
    }

    public String getId() { return id; }
    public String getNotificationId() { return notificationId; }
    public String getTenantId() { return tenantId; }
    public Channel getChannel() { return channel; }
    public DeliveryStatus getStatus() { return status; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setStatus(DeliveryStatus s) { this.status = s; }
    public void setAttempts(int a) { this.attempts = a; }
    public void setLastError(String e) { this.lastError = e; }
    public void setUpdatedAt(Instant t) { this.updatedAt = t; }
}
