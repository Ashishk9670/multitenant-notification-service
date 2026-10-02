package com.example.notification.service;

import com.example.notification.api.ApiException;
import com.example.notification.config.KafkaConfig;
import com.example.notification.config.NotificationProperties;
import com.example.notification.model.*;
import com.example.notification.template.MessageTemplate;
import com.example.notification.template.TemplateRenderer;
import com.example.notification.template.TemplateService;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/** Ingest pipeline: validate -> render -> dedupe -> rate limit -> quota -> publish. */
@Service
public class NotificationService {

    private final TemplateService templates;
    private final TemplateRenderer renderer;
    private final IdempotencyService idempotency;
    private final RateLimiter rateLimiter;
    private final QuotaService quota;
    private final KafkaTemplate<String, Object> kafka;
    private final NotificationProperties props;
    private final NotificationMetrics metrics;

    public NotificationService(TemplateService templates, TemplateRenderer renderer, IdempotencyService idempotency,
                               RateLimiter rateLimiter, QuotaService quota, KafkaTemplate<String, Object> kafka,
                               NotificationProperties props, NotificationMetrics metrics) {
        this.templates = templates;
        this.renderer = renderer;
        this.idempotency = idempotency;
        this.rateLimiter = rateLimiter;
        this.quota = quota;
        this.kafka = kafka;
        this.props = props;
        this.metrics = metrics;
    }

    public NotificationAccepted submit(String tenantId, String idempotencyKey, NotificationRequest req) {
        Priority priority = req.priority() == null ? Priority.NORMAL : req.priority();
        Map<Channel, Content> contents = buildContents(tenantId, req);

        String id = UUID.randomUUID().toString();
        boolean reserved = false;
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<String> existing = idempotency.reserve(tenantId, idempotencyKey, id);
            if (existing.isPresent()) {
                return new NotificationAccepted(existing.get(), true);
            }
            reserved = true;
        }
        boolean consumedQuota = false;
        try {
            RateLimiter.Decision d = rateLimiter.tryAcquire(tenantId);
            if (!d.allowed()) {
                metrics.rejected(tenantId, "rate_limited");
                throw new ApiException.RateLimited("Rate limit exceeded for tenant", d.retryAfterMillis());
            }
            if (!quota.tryConsume(tenantId)) {
                metrics.rejected(tenantId, "quota_exceeded");
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Daily quota exhausted for tenant");
            }
            consumedQuota = true;
            NotificationEvent event = new NotificationEvent(id, tenantId, priority, contents, req.recipients(), Instant.now());
            kafka.send(KafkaConfig.topicFor(props, priority), tenantId + ":" + id, event).get(10, TimeUnit.SECONDS);
            metrics.accepted(tenantId, priority);
            return new NotificationAccepted(id, false);
        } catch (ApiException e) {
            rollback(tenantId, idempotencyKey, reserved, consumedQuota);
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            rollback(tenantId, idempotencyKey, reserved, consumedQuota);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted while publishing");
        } catch (Exception e) {
            metrics.rejected(tenantId, "publish_failed");
            rollback(tenantId, idempotencyKey, reserved, consumedQuota);
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Unable to publish notification");
        }
    }

    private void rollback(String tenantId, String key, boolean reserved, boolean consumedQuota) {
        if (reserved) idempotency.release(tenantId, key);
        if (consumedQuota) quota.refund(tenantId);
    }

    private Map<Channel, Content> buildContents(String tenantId, NotificationRequest req) {
        boolean hasTemplate = req.templateName() != null && !req.templateName().isBlank();
        boolean hasRaw = req.body() != null && !req.body().isBlank();
        if (!hasTemplate && !hasRaw) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Either templateName or body is required");
        }
        Map<Channel, Content> out = new EnumMap<>(Channel.class);
        for (Channel ch : req.channels()) {
            String recipient = req.recipients().forChannel(ch);
            if (recipient == null || recipient.isBlank()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Missing recipient for channel " + ch);
            }
            try {
                if (hasTemplate) {
                    MessageTemplate t = templates.getByName(tenantId, req.templateName(), ch);
                    out.put(ch, new Content(renderer.render(t.getSubject(), req.variables()),
                            renderer.render(t.getBody(), req.variables())));
                } else {
                    out.put(ch, new Content(renderer.render(req.subject(), req.variables()),
                            renderer.render(req.body(), req.variables())));
                }
            } catch (TemplateRenderer.MissingVariablesException e) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
            }
        }
        return out;
    }
}
