package com.example.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.example.notification.api.ApiException;
import com.example.notification.config.NotificationProperties;
import com.example.notification.model.*;
import com.example.notification.template.MessageTemplate;
import com.example.notification.template.TemplateRenderer;
import com.example.notification.template.TemplateService;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.core.KafkaTemplate;

@SuppressWarnings("unchecked")
class NotificationServiceTest {

    TemplateService templates = mock(TemplateService.class);
    IdempotencyService idem = mock(IdempotencyService.class);
    RateLimiter limiter = mock(RateLimiter.class);
    QuotaService quota = mock(QuotaService.class);
    KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    NotificationProperties props = new NotificationProperties(
            new NotificationProperties.Topics("t.high", "t.normal", "t.low", "e", "s", "p", "r", "r.*", "dlq"),
            null, null, null, null, null);
    io.micrometer.core.instrument.simple.SimpleMeterRegistry registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
    NotificationMetrics metrics = new NotificationMetrics(registry);
    NotificationService service;

    @BeforeEach
    void setUp() {
        service = new NotificationService(templates, new TemplateRenderer(), idem, limiter, quota, kafka, props, metrics);
        when(limiter.tryAcquire(any())).thenReturn(new RateLimiter.Decision(true, 5, 0));
        when(quota.tryConsume(any())).thenReturn(true);
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.completedFuture(null));
        when(idem.reserve(any(), any(), any())).thenReturn(Optional.empty());
    }

    private NotificationRequest raw(Priority p) {
        return new NotificationRequest(Set.of(Channel.EMAIL), p, new Recipients("a@b.com", null, null),
                null, Map.of("n", "Ann"), "Subj {{n}}", "Hello {{n}}");
    }

    @Test
    void publishesToPriorityTopicWithRenderedContent() {
        NotificationAccepted res = service.submit("acme", null, raw(Priority.HIGH));
        assertThat(res.duplicate()).isFalse();
        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq("t.high"), startsWith("acme:"), captor.capture());
        NotificationEvent ev = (NotificationEvent) captor.getValue();
        assertThat(ev.contents().get(Channel.EMAIL).body()).isEqualTo("Hello Ann");
        assertThat(ev.contents().get(Channel.EMAIL).subject()).isEqualTo("Subj Ann");
        assertThat(registry.counter("notifications.accepted", "tenant", "acme", "priority", "HIGH").count()).isEqualTo(1.0);
    }

    private static String startsWith(String prefix) {
        return org.mockito.ArgumentMatchers.startsWith(prefix);
    }

    @Test
    void defaultsToNormalPriority() {
        service.submit("acme", null, raw(null));
        verify(kafka).send(eq("t.normal"), anyString(), any());
    }

    @Test
    void duplicateIdempotencyKeyShortCircuits() {
        when(idem.reserve(eq("acme"), eq("k1"), any())).thenReturn(Optional.of("existing-id"));
        NotificationAccepted res = service.submit("acme", "k1", raw(Priority.NORMAL));
        assertThat(res).isEqualTo(new NotificationAccepted("existing-id", true));
        verifyNoInteractions(kafka, limiter, quota);
    }

    @Test
    void rateLimitedReleasesIdempotencyKey() {
        when(limiter.tryAcquire("acme")).thenReturn(new RateLimiter.Decision(false, 0, 1500));
        assertThatThrownBy(() -> service.submit("acme", "k1", raw(Priority.NORMAL)))
                .isInstanceOf(ApiException.RateLimited.class)
                .hasFieldOrPropertyWithValue("retryAfterMillis", 1500L);
        verify(idem).release("acme", "k1");
        assertThat(registry.counter("notifications.rejected", "tenant", "acme", "reason", "rate_limited").count()).isEqualTo(1.0);
        verifyNoInteractions(kafka);
        verify(quota, never()).tryConsume(any());
    }

    @Test
    void quotaExhaustedRejectsAndReleasesKey() {
        when(quota.tryConsume("acme")).thenReturn(false);
        assertThatThrownBy(() -> service.submit("acme", "k2", raw(Priority.NORMAL)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verify(idem).release("acme", "k2");
        verifyNoInteractions(kafka);
    }

    @Test
    void kafkaFailureRefundsQuotaAndReturns503() {
        when(kafka.send(anyString(), anyString(), any())).thenReturn(CompletableFuture.failedFuture(new RuntimeException("down")));
        assertThatThrownBy(() -> service.submit("acme", "k3", raw(Priority.NORMAL)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
        verify(quota).refund("acme");
        verify(idem).release("acme", "k3");
    }

    @Test
    void requiresTemplateOrBody() {
        var req = new NotificationRequest(Set.of(Channel.EMAIL), null, new Recipients("a@b.com", null, null), null, null, null, null);
        assertThatThrownBy(() -> service.submit("acme", null, req))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void requiresRecipientForEachChannel() {
        var req = new NotificationRequest(Set.of(Channel.SMS), null, new Recipients("a@b.com", null, null), null, null, null, "hi");
        assertThatThrownBy(() -> service.submit("acme", null, req))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getMessage()).contains("SMS"));
    }

    @Test
    void missingTemplateVariableIs422() {
        var req = new NotificationRequest(Set.of(Channel.EMAIL), null, new Recipients("a@b.com", null, null), null, Map.of(), null, "Hi {{x}}");
        assertThatThrownBy(() -> service.submit("acme", null, req))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY));
    }

    @Test
    void usesStoredTemplate() {
        when(templates.getByName("acme", "welcome", Channel.EMAIL))
                .thenReturn(new MessageTemplate("acme", "welcome", Channel.EMAIL, "Welcome {{n}}", "Hi {{n}}"));
        var req = new NotificationRequest(Set.of(Channel.EMAIL), null, new Recipients("a@b.com", null, null), "welcome", Map.of("n", "Zed"), null, null);
        service.submit("acme", null, req);
        var captor = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(anyString(), anyString(), captor.capture());
        assertThat(((NotificationEvent) captor.getValue()).contents().get(Channel.EMAIL).subject()).isEqualTo("Welcome Zed");
    }
}
