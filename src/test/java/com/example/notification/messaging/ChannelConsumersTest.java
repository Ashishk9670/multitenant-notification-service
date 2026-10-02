package com.example.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.notification.config.NotificationProperties;
import com.example.notification.model.*;
import com.example.notification.sender.NotificationSender;
import com.example.notification.service.Backoff;
import com.example.notification.service.DeliveryLogService;
import com.example.notification.service.DeliveryStatus;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

@SuppressWarnings("unchecked")
class ChannelConsumersTest {

    KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
    DeliveryLogService log = mock(DeliveryLogService.class);
    NotificationSender email = mock(NotificationSender.class);
    NotificationProperties props = new NotificationProperties(
            new NotificationProperties.Topics("h", "n", "l", "ch.email", "ch.sms", "ch.push", "retry", "retry.*", "dlq"),
            null, new NotificationProperties.Retry(2, Duration.ofSeconds(1), 2.0, Duration.ofMinutes(1), 0), null, null, null);
    ChannelConsumers consumers;

    @BeforeEach
    void setUp() {
        when(email.channel()).thenReturn(Channel.EMAIL);
        Backoff backoff = new Backoff(Duration.ofSeconds(1), 2.0, Duration.ofMinutes(1), 0, () -> 0.5);
        consumers = new ChannelConsumers(List.of(email), kafka, props, backoff, log);
    }

    private DeliveryEvent event(int attempt) {
        return new DeliveryEvent("id1", "acme", Channel.EMAIL, Priority.NORMAL, "a@b.com", "s", "b", attempt, Instant.now(), null);
    }

    @Test
    void successMarksSent() throws Exception {
        consumers.email(event(0));
        verify(email).send(any());
        verify(log).record(any(), org.mockito.ArgumentMatchers.eq(DeliveryStatus.SENT), org.mockito.ArgumentMatchers.isNull());
        verifyNoInteractions(kafka);
    }

    @Test
    void firstFailureGoesToRetryTopic1WithNotBeforeHeader() throws Exception {
        doThrow(new IllegalStateException("boom")).when(email).send(any());
        long before = System.currentTimeMillis();
        consumers.email(event(0));
        ArgumentCaptor<ProducerRecord<String, Object>> cap = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(cap.capture());
        ProducerRecord<String, Object> rec = cap.getValue();
        assertThat(rec.topic()).isEqualTo("retry.1");
        assertThat(((DeliveryEvent) rec.value()).attempt()).isEqualTo(1);
        long notBefore = ByteBuffer.wrap(rec.headers().lastHeader(Headers.NOT_BEFORE).value()).getLong();
        assertThat(notBefore).isBetween(before + 1000, System.currentTimeMillis() + 1000);
        verify(log).record(any(), org.mockito.ArgumentMatchers.eq(DeliveryStatus.RETRYING), any());
    }

    @Test
    void secondRetryUsesDoubledDelay() throws Exception {
        doThrow(new IllegalStateException("boom")).when(email).send(any());
        long before = System.currentTimeMillis();
        consumers.email(event(1));
        ArgumentCaptor<ProducerRecord<String, Object>> cap = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(kafka).send(cap.capture());
        assertThat(cap.getValue().topic()).isEqualTo("retry.2");
        long notBefore = ByteBuffer.wrap(cap.getValue().headers().lastHeader(Headers.NOT_BEFORE).value()).getLong();
        assertThat(notBefore - before).isBetween(2000L, 2500L);
    }

    @Test
    void exhaustedRetriesGoToDlq() throws Exception {
        doThrow(new IllegalStateException("boom")).when(email).send(any());
        consumers.email(event(2));
        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(org.mockito.ArgumentMatchers.eq("dlq"), org.mockito.ArgumentMatchers.anyString(), cap.capture());
        assertThat(((DeliveryEvent) cap.getValue()).lastError()).contains("boom");
        verify(log).record(any(), org.mockito.ArgumentMatchers.eq(DeliveryStatus.DEAD), any());
    }

    @Test
    void retryConsumerRedispatchesDueEventToChannelTopic() throws Exception {
        byte[] due = ByteBuffer.allocate(8).putLong(System.currentTimeMillis() - 10).array();
        consumers.retry(event(1), due);
        verify(kafka).send(org.mockito.ArgumentMatchers.eq("ch.email"), org.mockito.ArgumentMatchers.anyString(), any());
    }

    @Test
    void retryConsumerWaitsUntilNotBefore() throws Exception {
        byte[] soon = ByteBuffer.allocate(8).putLong(System.currentTimeMillis() + 150).array();
        long start = System.currentTimeMillis();
        consumers.retry(event(1), soon);
        assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(100);
        verify(kafka).send(org.mockito.ArgumentMatchers.eq("ch.email"), org.mockito.ArgumentMatchers.anyString(), any());
    }
}
