package com.example.notification.sender;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings for the per-channel circuit breakers that guard provider calls. */
@ConfigurationProperties(prefix = "notification.circuit-breaker")
public record CircuitBreakerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("50") float failureRateThreshold,
        @DefaultValue("20") int slidingWindowSize,
        @DefaultValue("5") int minimumNumberOfCalls,
        @DefaultValue("30s") Duration waitDurationInOpenState,
        @DefaultValue("3") int permittedCallsInHalfOpenState) {
}
