package com.example.notification.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import com.example.notification.config.NotificationProperties;
import com.example.notification.messaging.ChannelConsumers;
import com.example.notification.messaging.Headers;
import com.example.notification.messaging.PriorityRouter;
import com.example.notification.model.*;
import com.example.notification.sender.ChannelCircuitBreakers;
import com.example.notification.sender.CircuitBreakerProperties;
import com.example.notification.sender.NotificationSender;
import com.example.notification.service.Backoff;
import com.example.notification.service.DeliveryLogService;
import com.example.notification.service.IdempotencyService;
import com.example.notification.service.NotificationMetrics;
import com.example.notification.service.NotificationService;
import com.example.notification.service.QuotaService;
import com.example.notification.service.RateLimiter;
import com.example.notification.template.TemplateRenderer;
import com.example.notification.template.TemplateService;
import com.example.notification.webhook.WebhookDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonSerializer;

/**
 * Contract test: every event the service really publishes (built by the production classes, serialised by the
 * production Kafka JsonSerializer) must validate against the JSON schemas declared in asyncapi.yaml.
 */
@SuppressWarnings("unchecked")
class KafkaEventContractTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final AsyncApiContract CONTRACT = new AsyncApiContract();
    static final String NOTIFICATION = "/components/schemas/NotificationEvent";
    static final String DELIVERY = "/components/schemas/DeliveryEvent";

    final NotificationProperties props = new NotificationProperties(
            new NotificationProperties.Topics("t.high", "t.normal", "t.low", "ch.email", "ch.sms", "ch.push",
                    "retry", "retry.*", "dlq"),
            null, new NotificationProperties.Retry(2, Duration.ofSeconds(1), 2.0, Duration.ofMinutes(1), 0), null, null, null);
    final KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    final List<ProducerRecord<String, Object>> published = new ArrayList<>();

    @BeforeEach
    void captureAll() {
        when(kafka.send(anyString(), anyString(), any())).thenAnswer(inv -> {
            published.add(new ProducerRecord<>(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2)));
            return CompletableFuture.completedFuture(null);
        });
        when(kafka.send(any(ProducerRecord.class))).thenAnswer(inv -> {
            published.add(inv.getArgument(0));
            return CompletableFuture.completedFuture(null);
        });
    }

    private static JsonNode wire(Object value) throws Exception {
        try (JsonSerializer<Object> ser = new JsonSerializer<>()) {
            return JSON.readTree(ser.serialize("any-topic", value));
        }
    }

    private void assertValid(String schema, ProducerRecord<String, Object> rec) throws Exception {
        JsonNode node = wire(rec.value());
        assertThat(CONTRACT.validate(schema, node)).as("payload on %s: %s", rec.topic(), node).isEmpty();
    }

    private ChannelConsumers consumers(NotificationSender email) {
        when(email.channel()).thenReturn(Channel.EMAIL);
        Backoff backoff = new Backoff(Duration.ofSeconds(1), 2.0, Duration.ofMinutes(1), 0, () -> 0.5);
        return new ChannelConsumers(List.of(email), kafka, props, backoff, mock(DeliveryLogService.class),
                new NotificationMetrics(registry),
                new ChannelCircuitBreakers(new CircuitBreakerProperties(false, 50, 20, 5, Duration.ofSeconds(30), 3), registry),
                mock(WebhookDispatcher.class));
    }

    private static DeliveryEvent delivery(int attempt) {
        return new DeliveryEvent(UUID.randomUUID().toString(), "acme", Channel.EMAIL, Priority.NORMAL, "a@b.com",
                "Subject", "Body", attempt, Instant.now(), null);
    }

    @Test
    void notificationEventPublishedByIngestMatchesSchema() throws Exception {
        TemplateService templates = mock(TemplateService.class);
        RateLimiter limiter = mock(RateLimiter.class);
        QuotaService quota = mock(QuotaService.class);
        IdempotencyService idem = mock(IdempotencyService.class);
        when(limiter.tryAcquire(any())).thenReturn(new RateLimiter.Decision(true, 5, 0));
        when(quota.tryConsume(any())).thenReturn(true);
        when(idem.reserve(any(), any(), any())).thenReturn(Optional.empty());
        var service = new NotificationService(templates, new TemplateRenderer(), idem, limiter, quota, kafka, props,
                new NotificationMetrics(registry));

        for (Priority p : Priority.values()) {
            service.submit("acme", null, new NotificationRequest(Set.of(Channel.EMAIL, Channel.SMS, Channel.PUSH), p,
                    new Recipients("a@b.com", "+15551234567", "device-token"), null, Map.of("n", "Ann"),
                    "Hi {{n}}", "Hello {{n}}"));
        }

        assertThat(published).hasSize(3);
        for (var rec : published) assertValid(NOTIFICATION, rec);
    }

    @Test
    void deliveryEventsFannedOutByRouterMatchSchema() throws Exception {
        PriorityRouter router = new PriorityRouter(kafka, props, mock(DeliveryLogService.class));
        Map<Channel, Content> contents = new LinkedHashMap<>();
        contents.put(Channel.EMAIL, new Content("s", "email body"));
        contents.put(Channel.SMS, new Content(null, "sms body"));
        contents.put(Channel.PUSH, new Content(null, "push body"));
        router.high(new NotificationEvent(UUID.randomUUID().toString(), "acme", Priority.HIGH, contents,
                new Recipients("a@b.com", "+15551234567", "tok"), Instant.now()));

        assertThat(published).extracting(ProducerRecord::topic).containsExactlyInAnyOrder("ch.email", "ch.sms", "ch.push");
        for (var rec : published) assertValid(DELIVERY, rec);
    }

    @Test
    void retryAndDlqEventsMatchSchemaAndRetryHeaderIsEightByteEpochMillis() throws Exception {
        NotificationSender email = mock(NotificationSender.class);
        doThrow(new IllegalStateException("provider down")).when(email).send(any());
        ChannelConsumers c = consumers(email);

        c.email(delivery(0)); // -> retry.1
        c.email(delivery(1)); // -> retry.2
        c.email(delivery(2)); // exhausted -> dlq

        assertThat(published).extracting(ProducerRecord::topic).containsExactly("retry.1", "retry.2", "dlq");
        for (var rec : published) {
            assertValid(DELIVERY, rec);
            JsonNode node = wire(rec.value());
            assertThat(node.get("lastError").asText()).contains("provider down");
        }
        JsonNode headerSchema = CONTRACT.root().at("/components/messages/RetryDeliveryEvent/headers/required");
        assertThat(headerSchema.get(0).asText()).isEqualTo(Headers.NOT_BEFORE);
        for (var rec : published.subList(0, 2)) {
            byte[] h = rec.headers().lastHeader(Headers.NOT_BEFORE).value();
            assertThat(h).hasSize(Long.BYTES);
            assertThat(ByteBuffer.wrap(h).getLong()).isGreaterThan(System.currentTimeMillis() - 1000);
        }
        assertThat(published.get(2).headers().lastHeader(Headers.NOT_BEFORE)).isNull();
    }

    @Test
    void redeliveryFromRetryTopicMatchesSchema() throws Exception {
        NotificationSender email = mock(NotificationSender.class);
        ChannelConsumers c = consumers(email);
        c.retry(delivery(1), null);
        assertThat(published).hasSize(1);
        assertValid(DELIVERY, published.get(0));
    }

    @Test
    void schemaRejectsMalformedPayloads() throws Exception {
        // Guards against a vacuous contract: the validator must actually fail on bad data.
        JsonNode good = wire(delivery(0));
        assertThat(CONTRACT.validate(DELIVERY, good)).isEmpty();

        var missing = ((com.fasterxml.jackson.databind.node.ObjectNode) good.deepCopy());
        missing.remove("tenantId");
        assertThat(CONTRACT.validate(DELIVERY, missing)).isNotEmpty();

        var badEnum = ((com.fasterxml.jackson.databind.node.ObjectNode) good.deepCopy());
        badEnum.put("channel", "FAX");
        assertThat(CONTRACT.validate(DELIVERY, badEnum)).isNotEmpty();

        var badId = ((com.fasterxml.jackson.databind.node.ObjectNode) good.deepCopy());
        badId.put("id", "not-a-uuid");
        assertThat(CONTRACT.validate(DELIVERY, badId)).isNotEmpty();

        var badAttempt = ((com.fasterxml.jackson.databind.node.ObjectNode) good.deepCopy());
        badAttempt.put("attempt", -1);
        assertThat(CONTRACT.validate(DELIVERY, badAttempt)).isNotEmpty();
    }

    @Test
    void everyChannelTopicInAsyncApiIsCoveredByThisTest() {
        // If a new message type is added to asyncapi.yaml, extend this test.
        Set<String> messages = new java.util.TreeSet<>();
        CONTRACT.root().at("/components/messages").fieldNames().forEachRemaining(messages::add);
        assertThat(messages).containsExactlyInAnyOrder("NotificationEvent", "DeliveryEvent", "RetryDeliveryEvent");
    }
}
