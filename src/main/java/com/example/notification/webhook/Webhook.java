package com.example.notification.webhook;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** A tenant's delivery-receipt endpoint plus the shared secret used to sign payloads. */
@Entity
@Table(name = "webhooks")
public class Webhook {
    @Id
    @Column(name = "tenant_id")
    private String tenantId;
    @Column(nullable = false, length = 2048)
    private String url;
    @Column(nullable = false)
    private String secret;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Webhook() { }

    public Webhook(String tenantId, String url, String secret, Instant now) {
        this.tenantId = tenantId;
        this.url = url;
        this.secret = secret;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getTenantId() { return tenantId; }
    public String getUrl() { return url; }
    public String getSecret() { return secret; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setUrl(String url, Instant now) {
        this.url = url;
        this.updatedAt = now;
    }

    public void setSecret(String secret, Instant now) {
        this.secret = secret;
        this.updatedAt = now;
    }
}
