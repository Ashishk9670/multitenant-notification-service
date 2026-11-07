package com.example.notification.sender;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.notification.model.Channel;
import com.example.notification.model.DeliveryEvent;
import com.example.notification.model.Priority;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class LogSendersTest {
    private final NotificationSender email = new LogSenders.Email();

    private DeliveryEvent to(String recipient, int attempt) {
        return new DeliveryEvent("id", "acme", Channel.EMAIL, Priority.NORMAL, recipient, "s", "b", attempt, Instant.now(), null);
    }

    @Test
    void failRecipientsAreRejectedAsBadRecipientsAndNeverCountAgainstTheBreaker() {
        assertThatThrownBy(() -> email.send(to("fail@example.com", 0))).isInstanceOf(RecipientRejectedException.class);
        assertThatThrownBy(() -> email.send(to("fail@example.com", 5))).isInstanceOf(RecipientRejectedException.class);
    }

    @Test
    void flakyRecipientsSimulateATransientProviderFailureThenSucceed() {
        assertThatThrownBy(() -> email.send(to("flaky@example.com", 0))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> email.send(to("flaky@example.com", 1))).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> email.send(to("flaky@example.com", 2))).doesNotThrowAnyException();
    }

    @Test
    void ordinaryRecipientsAreDelivered() {
        assertThatCode(() -> email.send(to("ann@example.com", 0))).doesNotThrowAnyException();
    }
}
