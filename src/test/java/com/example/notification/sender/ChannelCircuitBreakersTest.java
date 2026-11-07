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

    private DeliveryEvent event(String tenant, String recipient) {
        return new DeliveryEvent("id", tenant, Channel.EMAIL, Priority.NORMAL, recipient, "s", "b", 0, Instant.now(), null);
    }

    @Test
    void oneTenantsBadRecipientsDoNotBlockAnotherTenant() throws Exception {
        doThrow(new RecipientRejectedException("invalid address")).when(email)
                .send(org.mockito.ArgumentMatchers.argThat(e -> e.tenantId().equals("noisy")));
        for (int i = 0; i < 50; i++) {
            assertThatThrownBy(() -> breakers.send(email, event("noisy", "nobody@invalid"))).isInstanceOf(RecipientRejectedException.class);
        }
        assertThat(breakers.forChannel(Channel.EMAIL).getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        // a healthy tenant on the same channel is unaffected
        breakers.send(email, event("quiet", "ann@example.com"));
        verify(email, org.mockito.Mockito.times(51)).send(any());
    }

    @Test
    void recipientRejectionsAreNotRecordedAsFailuresOrSuccesses() throws Exception {
        doThrow(new RecipientRejectedException("bad")).when(email).send(any());
        for (int i = 0; i < 10; i++) {
            assertThatThrownBy(() -> breakers.send(email, event(Channel.EMAIL))).isInstanceOf(RecipientRejectedException.class);
        }
        var m = breakers.forChannel(Channel.EMAIL).getMetrics();
        assertThat(m.getNumberOfFailedCalls()).isZero();
        assertThat(m.getNumberOfSuccessfulCalls()).isZero();
    }

    @Test
    void providerFaultsStillOpenTheBreaker() throws Exception {
        doThrow(new ProviderException("smtp down", -1)).when(email).send(any());
        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> breakers.send(email, event(Channel.EMAIL))).isInstanceOf(ProviderException.class);
        }
        assertThat(breakers.forChannel(Channel.EMAIL).getState()).isEqualTo(CircuitBreaker.State.OPEN);
    }

    @Test
    void rejectedProbeCallsDoNotLeakHalfOpenPermits() throws Exception {
        CircuitBreaker cb = breakers.forChannel(Channel.EMAIL);
        cb.transitionToOpenState();
        cb.transitionToHalfOpenState(); // permits 2 probe calls
        doThrow(new RecipientRejectedException("bad")).when(email).send(any());
        // more probes than permits: each one must hand its permit back, so none is rejected as "not permitted"
        for (int i = 0; i < 6; i++) {
            assertThatThrownBy(() -> breakers.send(email, event(Channel.EMAIL))).isInstanceOf(RecipientRejectedException.class);
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "-1,true", "500,true", "502,true", "503,true", "429,true", "408,true", "401,true", "403,true",
        "400,false", "404,false", "410,false", "422,false"})
    void httpStatusClassification(int status, boolean providerFault) {
        assertThat(ChannelCircuitBreakers.countsAsProviderFailure(new ProviderException("x", status))).isEqualTo(providerFault);
    }

    @Test
    void unknownExceptionsCountBecauseTheyMayBeTheProvider() {
        assertThat(ChannelCircuitBreakers.countsAsProviderFailure(new IllegalStateException("?"))).isTrue();
        assertThat(ChannelCircuitBreakers.countsAsProviderFailure(new RecipientRejectedException("bad"))).isFalse();
    }
}
