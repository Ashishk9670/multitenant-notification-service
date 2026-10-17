package com.example.notification.sender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.notification.model.Channel;
import com.example.notification.model.DeliveryEvent;
import com.example.notification.model.Priority;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChannelCircuitBreakersTest {

    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    NotificationSender sms = mock(NotificationSender.class);
    NotificationSender email = mock(NotificationSender.class);
    ChannelCircuitBreakers breakers;

    @BeforeEach
    void setUp() {
        when(sms.channel()).thenReturn(Channel.SMS);
        when(email.channel()).thenReturn(Channel.EMAIL);
        breakers = new ChannelCircuitBreakers(
                new CircuitBreakerProperties(true, 50, 4, 4, Duration.ofSeconds(30), 2), registry);
    }

    private DeliveryEvent event(Channel ch) {
        return new DeliveryEvent("id", "acme", ch, Priority.NORMAL, "x", "s", "b", 0, Instant.now(), null);
    }

    @Test
    void opensAfterFailuresAndRejectsFast() throws Exception {
        doThrow(new IllegalStateException("down")).when(sms).send(any());
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> breakers.send(sms, event(Channel.SMS))).isInstanceOf(IllegalStateException.class);
        }
        assertThat(breakers.forChannel(Channel.SMS).getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThatThrownBy(() -> breakers.send(sms, event(Channel.SMS))).isInstanceOf(CallNotPermittedException.class);
        // 4 real calls only; the 5th was short-circuited
        verify(sms, org.mockito.Mockito.times(4)).send(any());
    }

    @Test
    void channelsAreIsolated() throws Exception {
        doThrow(new IllegalStateException("down")).when(sms).send(any());
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> breakers.send(sms, event(Channel.SMS))).isInstanceOf(IllegalStateException.class);
        }
        breakers.send(email, event(Channel.EMAIL));
        verify(email).send(any());
        assertThat(breakers.forChannel(Channel.EMAIL).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void stateAndCallMetricsAreExported() throws Exception {
        breakers.send(email, event(Channel.EMAIL));
        assertThat(registry.find("resilience4j.circuitbreaker.state").tag("name", "sms").gauges()).isNotEmpty();
        assertThat(registry.find("resilience4j.circuitbreaker.calls").tag("name", "email").tag("kind", "successful")
                .timer().count()).isEqualTo(1);
    }

    @Test
    void disabledBypassesBreaker() throws Exception {
        ChannelCircuitBreakers off = new ChannelCircuitBreakers(
                new CircuitBreakerProperties(false, 50, 4, 4, Duration.ofSeconds(30), 2), new SimpleMeterRegistry());
        doThrow(new IllegalStateException("down")).when(sms).send(any());
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> off.send(sms, event(Channel.SMS))).isInstanceOf(IllegalStateException.class);
        }
        verify(sms, org.mockito.Mockito.times(10)).send(any());
        verify(email, never()).send(any());
    }
}
