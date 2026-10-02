package com.example.notification.template;

import com.example.notification.model.Channel;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "templates")
public class MessageTemplate {
    @Id
    private UUID id;
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;
    @Column(nullable = false)
    private String name;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Channel channel;
    private String subject;
    @Column(nullable = false, columnDefinition = "text")
    private String body;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MessageTemplate() { }

    public MessageTemplate(String tenantId, String name, Channel channel, String subject, String body) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.name = name;
        this.channel = channel;
        this.subject = subject;
        this.body = body;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void update(String name, Channel channel, String subject, String body) {
        this.name = name;
        this.channel = channel;
        this.subject = subject;
        this.body = body;
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getName() { return name; }
    public Channel getChannel() { return channel; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
