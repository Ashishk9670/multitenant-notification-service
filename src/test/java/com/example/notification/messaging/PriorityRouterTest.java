package com.example.notification.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.example.notification.config.NotificationProperties;
import com.example.notification.model.*;
import com.example.notification.service.DeliveryLogService;
import com.example.notification.service.DeliveryStatus;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaTemplate;

@SuppressWarnings("unchecked")
class PriorityRouterTest {

    @Test
    void fansOutOneDeliveryPerChannel() {
        KafkaTemplate<String, Object> kafka = mock(KafkaTemplate.class);
        DeliveryLogService log = mock(DeliveryLogService.class);
        var props = new NotificationProperties(
                new NotificationProperties.Topics("h", "n", "l", "ch.email", "ch.sms", "ch.push", "r", "r.*", "dlq"),
                null, null, null, null, null);
        PriorityRouter router = new PriorityRouter(kafka, props, log);

        Map<Channel, Content> contents = new LinkedHashMap<>();
        contents.put(Channel.EMAIL, new Content("s", "email body"));
        contents.put(Channel.PUSH, new Content(null, "push body"));
        var ev = new NotificationEvent("n1", "acme", Priority.HIGH, contents,
                new Recipients("a@b.com", "+100", "tok"), Instant.now());
        router.high(ev);

        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq("ch.email"), anyString(), cap.capture());
        verify(kafka).send(eq("ch.push"), anyString(), cap.capture());
        verify(kafka, never()).send(eq("ch.sms"), anyString(), any());
        DeliveryEvent email = (DeliveryEvent) cap.getAllValues().get(0);
        assertThat(email.recipient()).isEqualTo("a@b.com");
        assertThat(email.body()).isEqualTo("email body");
        assertThat(email.attempt()).isZero();
        verify(log, times(2)).record(any(), eq(DeliveryStatus.ACCEPTED), any());
    }
}
