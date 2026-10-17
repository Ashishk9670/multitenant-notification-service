package com.example.notification.sender;

import com.example.notification.model.Channel;
import com.example.notification.model.DeliveryEvent;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * One circuit breaker per channel (provider), so an outage of the SMS provider does not stop email or push.
 * State and call metrics are exported as resilience4j_circuitbreaker_* (tag name = channel, e.g. "sms").
 * When a breaker is open the call is rejected immediately with CallNotPermittedException, which the consumer
 * treats like any other delivery failure (retry with backoff, then DLQ).
 */
@Component
public class ChannelCircuitBreakers {

    private final CircuitBreakerRegistry registry;
    private final boolean enabled;

    public ChannelCircuitBreakers(CircuitBreakerProperties props, MeterRegistry meterRegistry) {
        this.enabled = props.enabled();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(props.failureRateThreshold())
                .slidingWindowSize(props.slidingWindowSize())
                .minimumNumberOfCalls(props.minimumNumberOfCalls())
                .waitDurationInOpenState(props.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(props.permittedCallsInHalfOpenState())
                .build();
        this.registry = CircuitBreakerRegistry.of(config);
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(registry).bindTo(meterRegistry);
        for (Channel ch : Channel.values()) {
            registry.circuitBreaker(name(ch));
        }
    }

    public CircuitBreaker forChannel(Channel channel) {
        return registry.circuitBreaker(name(channel));
    }

    /** Runs the send through the channel's breaker (or directly when breakers are disabled). */
    public void send(NotificationSender sender, DeliveryEvent event) throws Exception {
        if (!enabled) {
            sender.send(event);
            return;
        }
        CircuitBreaker cb = forChannel(sender.channel());
        cb.acquirePermission();
        long start = System.nanoTime();
        try {
            sender.send(event);
        } catch (Exception ex) {
            cb.onError(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS, ex);
            throw ex;
        }
        cb.onSuccess(System.nanoTime() - start, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    static String name(Channel channel) {
        return channel.name().toLowerCase();
    }
}
