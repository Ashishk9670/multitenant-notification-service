package com.example.notification.webhook;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.notification.model.Channel;
import com.example.notification.model.DeliveryEvent;
import com.example.notification.model.Priority;
import com.example.notification.service.Backoff;
import com.example.notification.service.DeliveryStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WebhookDispatcherTest {

    record Call(URI uri, Map<String, String> headers, String body) { }

    /** Collects scheduled tasks; drain() runs them in order, recording requested delays. */
    static class ManualScheduler implements WebhookDispatcher.Scheduler {
        final Deque<Runnable> queue = new ArrayDeque<>();
        final List<Duration> delays = new ArrayList<>();

        public void schedule(Runnable task, Duration delay) {
            delays.add(delay);
            queue.add(task);
        }

        void drain() {
            while (!queue.isEmpty()) queue.poll().run();
        }
    }

    WebhookService service = mock(WebhookService.class);
    WebhookSigner signer = new WebhookSigner();
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ManualScheduler scheduler = new ManualScheduler();
    List<Call> calls = new ArrayList<>();
    Deque<Object> responses = new ArrayDeque<>(); // Integer status or Exception
    WebhookUrlValidator validator = new WebhookUrlValidator(
            h -> h.matches("[0-9.]+") ? InetAddress.getAllByName(h) : new InetAddress[] {InetAddress.getByName("93.184.216.34")});
    WebhookDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        WebhookTransport transport = (uri, headers, body) -> {
            calls.add(new Call(uri, headers, body));
            Object r = responses.isEmpty() ? 200 : responses.poll();
            if (r instanceof Exception e) throw e;
            return (Integer) r;
        };
        Backoff backoff = new Backoff(Duration.ofSeconds(1), 2.0, Duration.ofMinutes(1), 0, () -> 0.5);
        dispatcher = new WebhookDispatcher(service, validator, signer, transport, backoff, 3, true, registry, scheduler);
        when(service.find("acme")).thenReturn(Optional.of(
                new Webhook("acme", "https://hooks.example.com/r", "whsec_test", Instant.now())));
    }

    private DeliveryEvent event() {
        return new DeliveryEvent("n1", "acme", Channel.EMAIL, Priority.NORMAL, "a@b.com", "s", "b", 1, Instant.now(), null);
    }

    private double count(String result) {
        var c = registry.find("notifications.webhook").tag("tenant", "acme").tag("result", result).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    void postsSignedReceiptOnSent() {
        dispatcher.publish(event(), DeliveryStatus.SENT, null);
        scheduler.drain();
        assertThat(calls).hasSize(1);
        Call c = calls.get(0);
        assertThat(c.uri().toString()).isEqualTo("https://hooks.example.com/r");
        assertThat(c.body()).contains("\"notificationId\":\"n1\"").contains("\"status\":\"SENT\"")
                .contains("\"channel\":\"EMAIL\"").contains("\"error\":null");
        long ts = Long.parseLong(c.headers().get("X-Webhook-Timestamp"));
        assertThat(signer.verify("whsec_test", ts, c.body(), c.headers().get("X-Webhook-Signature"))).isTrue();
        assertThat(c.headers()).containsEntry("Content-Type", "application/json");
        assertThat(count("success")).isEqualTo(1);
    }

    @Test
    void deadReceiptEscapesErrorText() {
        dispatcher.publish(event(), DeliveryStatus.DEAD, "boom \"quoted\"\nline");
        scheduler.drain();
        assertThat(calls.get(0).body()).contains("\"status\":\"DEAD\"").contains("boom \\\"quoted\\\"\\nline");
    }

    @Test
    void nonTerminalStatusesAreIgnored() {
        dispatcher.publish(event(), DeliveryStatus.RETRYING, "x");
        dispatcher.publish(event(), DeliveryStatus.ACCEPTED, null);
        scheduler.drain();
        assertThat(calls).isEmpty();
    }

    @Test
    void noWebhookRegisteredMeansNothingSent() {
        when(service.find("acme")).thenReturn(Optional.empty());
        dispatcher.publish(event(), DeliveryStatus.SENT, null);
        scheduler.drain();
        assertThat(calls).isEmpty();
    }

    @Test
    void retriesWithBackoffThenSucceeds() {
        responses.add(500);
        responses.add(new java.io.IOException("timeout"));
        responses.add(204);
        dispatcher.publish(event(), DeliveryStatus.SENT, null);
        scheduler.drain();
        assertThat(calls).hasSize(3);
        assertThat(scheduler.delays).containsExactly(Duration.ZERO, Duration.ofSeconds(1), Duration.ofSeconds(2));
        assertThat(count("retry")).isEqualTo(2);
        assertThat(count("success")).isEqualTo(1);
        assertThat(count("failure")).isZero();
    }

    @Test
    void givesUpAfterMaxAttempts() {
        responses.add(500);
        responses.add(502);
        responses.add(503);
        responses.add(200); // must not be reached
        dispatcher.publish(event(), DeliveryStatus.SENT, null);
        scheduler.drain();
        assertThat(calls).hasSize(3);
        assertThat(count("failure")).isEqualTo(1);
        assertThat(count("success")).isZero();
    }

    @Test
    void blockedUrlAtSendTimeIsNotRetriedOrPosted() {
        when(service.find("acme")).thenReturn(Optional.of(
                new Webhook("acme", "https://169.254.169.254/x", "whsec_test", Instant.now())));
        dispatcher.publish(event(), DeliveryStatus.SENT, null);
        scheduler.drain();
        assertThat(calls).isEmpty();
        assertThat(scheduler.delays).hasSize(1);
        assertThat(count("failure")).isEqualTo(1);
    }

    @Test
    void lookupFailureNeverPropagates() {
        when(service.find("acme")).thenThrow(new IllegalStateException("db down"));
        dispatcher.publish(event(), DeliveryStatus.SENT, null);
        assertThat(calls).isEmpty();
    }

    @Test
    void disabledDispatcherDoesNothing() {
        WebhookDispatcher off = new WebhookDispatcher(service, validator, signer, (u, h, b) -> 200,
                new Backoff(Duration.ofSeconds(1), 2.0, Duration.ofMinutes(1), 0, () -> 0.5), 3, false, registry, scheduler);
        off.publish(event(), DeliveryStatus.SENT, null);
        scheduler.drain();
        assertThat(scheduler.delays).isEmpty();
    }
}
