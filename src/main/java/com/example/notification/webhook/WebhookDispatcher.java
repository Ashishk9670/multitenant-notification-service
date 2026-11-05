package com.example.notification.webhook;

import com.example.notification.model.DeliveryEvent;
import com.example.notification.service.Backoff;
import com.example.notification.service.DeliveryStatus;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Posts signed delivery receipts to the tenant's webhook when a delivery reaches a terminal state (SENT or DEAD).
 * Each receipt is retried with exponential backoff up to notification.webhook.max-attempts. Receipts are best-effort
 * and in-memory: they never block or fail the delivery itself, and pending retries are lost on restart.
 */
@Component
public class WebhookDispatcher {

    /** Defers work; replaced by a synchronous fake in tests. */
    public interface Scheduler {
        void schedule(Runnable task, Duration delay);
    }

    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);

    private final WebhookService webhooks;
    private final WebhookUrlValidator validator;
    private final WebhookSigner signer;
    private final WebhookTransport transport;
    private final Backoff backoff;
    private final int maxAttempts;
    private final boolean enabled;
    private final MeterRegistry metrics;
    private final Scheduler scheduler;

    @Autowired
    public WebhookDispatcher(WebhookService webhooks, WebhookUrlValidator validator, WebhookSigner signer,
                             WebhookTransport transport, WebhookProperties props, MeterRegistry metrics) {
        this(webhooks, validator, signer, transport,
                new Backoff(props.initialDelay(), props.multiplier(), props.maxDelay(), props.jitter(), Math::random),
                props.maxAttempts(), props.enabled(), metrics, defaultScheduler());
    }

    public WebhookDispatcher(WebhookService webhooks, WebhookUrlValidator validator, WebhookSigner signer,
                             WebhookTransport transport, Backoff backoff, int maxAttempts, boolean enabled,
                             MeterRegistry metrics, Scheduler scheduler) {
        this.webhooks = webhooks;
        this.validator = validator;
        this.signer = signer;
        this.transport = transport;
        this.backoff = backoff;
        this.maxAttempts = maxAttempts;
        this.enabled = enabled;
        this.metrics = metrics;
        this.scheduler = scheduler;
    }

    private static Scheduler defaultScheduler() {
        ScheduledExecutorService ses = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "webhook-dispatch");
            t.setDaemon(true);
            return t;
        });
        return (task, delay) -> ses.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Never throws: a receipt problem must not affect delivery processing. */
    public void publish(DeliveryEvent e, DeliveryStatus status, String error) {
        if (!enabled || (status != DeliveryStatus.SENT && status != DeliveryStatus.DEAD)) return;
        try {
            webhooks.find(e.tenantId()).ifPresent(w -> {
                String body = payload(e, status, error);
                scheduler.schedule(() -> attempt(e, w.getUrl(), w.getSecret(), body, 1), Duration.ZERO);
            });
        } catch (RuntimeException ex) {
            log.warn("Could not enqueue webhook receipt for {}: {}", e.id(), ex.toString());
        }
    }

    void attempt(DeliveryEvent e, String url, String secret, String body, int n) {
        boolean ok = false;
        boolean retryable = true;
        try {
            URI uri = validator.validate(url); // re-check: DNS may have changed since registration
            long ts = Instant.now().getEpochSecond();
            Map<String, String> headers = Map.of(
                    "Content-Type", "application/json",
                    "User-Agent", "multitenant-notification-service-webhook/1",
                    "X-Webhook-Id", e.id() + ":" + e.channel(),
                    "X-Webhook-Timestamp", Long.toString(ts),
                    "X-Webhook-Signature", signer.sign(secret, ts, body));
            int code = transport.post(uri, headers, body);
            ok = code >= 200 && code < 300;
            if (!ok) log.info("Webhook for tenant {} returned HTTP {} (attempt {})", e.tenantId(), code, n);
        } catch (WebhookUrlValidator.InvalidWebhookUrlException ex) {
            retryable = false;
            log.warn("Webhook for tenant {} blocked: {}", e.tenantId(), ex.getMessage());
        } catch (Exception ex) {
            log.info("Webhook for tenant {} failed (attempt {}): {}", e.tenantId(), n, ex.toString());
        }
        if (ok) {
            metrics.counter("notifications.webhook", "tenant", e.tenantId(), "result", "success").increment();
        } else if (retryable && n < maxAttempts) {
            metrics.counter("notifications.webhook", "tenant", e.tenantId(), "result", "retry").increment();
            scheduler.schedule(() -> attempt(e, url, secret, body, n + 1), backoff.delayFor(n));
        } else {
            metrics.counter("notifications.webhook", "tenant", e.tenantId(), "result", "failure").increment();
        }
    }

    static String payload(DeliveryEvent e, DeliveryStatus status, String error) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("notificationId", e.id());
        m.put("tenantId", e.tenantId());
        m.put("channel", e.channel().name());
        m.put("status", status.name());
        m.put("attempts", e.attempt());
        m.put("error", error);
        m.put("timestamp", Instant.now().toString());
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> en : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(en.getKey()).append("\":");
            Object v = en.getValue();
            if (v == null) sb.append("null");
            else if (v instanceof Number) sb.append(v);
            else sb.append('"').append(escape(v.toString())).append('"');
        }
        return sb.append('}').toString();
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
