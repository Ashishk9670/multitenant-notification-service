package com.example.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;
import net.jqwik.api.constraints.Scale;

/** Invariants of exponential backoff with jitter for arbitrary configuration. */
class BackoffPropertyTest {

    @Property
    void delayIsNeverNegativeAndNeverExceedsMax(@ForAll @LongRange(min = 1, max = 60_000) long initialMs,
                                                @ForAll @Scale(6) @DoubleRange(min = 1.0, max = 10.0) double multiplier,
                                                @ForAll @LongRange(min = 1, max = 3_600_000) long maxMs,
                                                @ForAll @Scale(6) @DoubleRange(min = 0.0, max = 0.99) double jitter,
                                                @ForAll @Scale(6) @DoubleRange(min = 0.0, max = 0.999999) double rnd,
                                                @ForAll @IntRange(min = 1, max = 2_000) int attempt) {
        var b = new Backoff(Duration.ofMillis(initialMs), multiplier, Duration.ofMillis(maxMs), jitter, () -> rnd);
        Duration d = b.delayFor(attempt);
        assertThat(d.toMillis()).isBetween(0L, maxMs);
    }

    @Property
    void withoutJitterDelaysAreMonotonicAndCapped(@ForAll @LongRange(min = 1, max = 10_000) long initialMs,
                                                  @ForAll @Scale(6) @DoubleRange(min = 1.0, max = 5.0) double multiplier,
                                                  @ForAll @LongRange(min = 1, max = 600_000) long maxMs) {
        var b = new Backoff(Duration.ofMillis(initialMs), multiplier, Duration.ofMillis(maxMs), 0, () -> 0.5);
        long prev = 0;
        for (int n = 1; n <= 60; n++) {
            long d = b.delayFor(n).toMillis();
            assertThat(d).isGreaterThanOrEqualTo(prev).isLessThanOrEqualTo(maxMs);
            prev = d;
        }
        assertThat(b.delayFor(1).toMillis()).isEqualTo(Math.min(initialMs, maxMs));
    }

    @Property
    void jitteredDelayStaysWithinTheJitterBand(@ForAll @LongRange(min = 100, max = 10_000) long initialMs,
                                               @ForAll @Scale(6) @DoubleRange(min = 1.0, max = 4.0) double multiplier,
                                               @ForAll @Scale(6) @DoubleRange(min = 0.0, max = 0.9) double jitter,
                                               @ForAll @Scale(6) @DoubleRange(min = 0.0, max = 0.999999) double rnd,
                                               @ForAll @IntRange(min = 1, max = 8) int attempt) {
        long max = Long.MAX_VALUE / 4;
        var b = new Backoff(Duration.ofMillis(initialMs), multiplier, Duration.ofMillis(max), jitter, () -> rnd);
        double base = initialMs * Math.pow(multiplier, attempt - 1);
        long d = b.delayFor(attempt).toMillis();
        assertThat((double) d).isBetween(Math.floor(base * (1 - jitter)) - 1, Math.ceil(base * (1 + jitter)) + 1);
    }

    @Property
    void invalidConfigurationIsRejected(@ForAll @Scale(6) @DoubleRange(min = -5.0, max = 0.999) double badMultiplier) {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new Backoff(Duration.ofSeconds(1), badMultiplier, Duration.ofMinutes(1), 0, () -> 0.5));
    }
}
