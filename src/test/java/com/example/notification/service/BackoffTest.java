package com.example.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class BackoffTest {

    private Backoff noJitter(Duration initial, double mult, Duration max) {
        return new Backoff(initial, mult, max, 0.0, () -> 0.5);
    }

    @Test
    void growsExponentially() {
        Backoff b = noJitter(Duration.ofSeconds(1), 2.0, Duration.ofMinutes(10));
        assertThat(b.delayFor(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(b.delayFor(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(b.delayFor(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(b.delayFor(4)).isEqualTo(Duration.ofSeconds(8));
    }

    @Test
    void cappedAtMax() {
        Backoff b = noJitter(Duration.ofSeconds(1), 3.0, Duration.ofSeconds(10));
        assertThat(b.delayFor(10)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void jitterStaysWithinBounds() {
        Backoff low = new Backoff(Duration.ofSeconds(10), 2.0, Duration.ofHours(1), 0.2, () -> 0.0);
        Backoff high = new Backoff(Duration.ofSeconds(10), 2.0, Duration.ofHours(1), 0.2, () -> 1.0);
        assertThat(low.delayFor(1)).isEqualTo(Duration.ofSeconds(8));
        assertThat(high.delayFor(1)).isEqualTo(Duration.ofSeconds(12));
    }

    @Test
    void jitterNeverExceedsMax() {
        Backoff b = new Backoff(Duration.ofSeconds(10), 2.0, Duration.ofSeconds(10), 0.5, () -> 1.0);
        assertThat(b.delayFor(3)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void rejectsInvalidArguments() {
        Backoff b = noJitter(Duration.ofSeconds(1), 2.0, Duration.ofMinutes(1));
        assertThatThrownBy(() -> b.delayFor(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Backoff(Duration.ZERO, 2, Duration.ofSeconds(1), 0, () -> 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Backoff(Duration.ofSeconds(1), 0.5, Duration.ofSeconds(1), 0, () -> 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Backoff(Duration.ofSeconds(1), 2, Duration.ofSeconds(1), 1.0, () -> 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
