package com.example.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.notification.config.NotificationProperties.Limit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TokenBucketRateLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000);

    private TokenBucketRateLimiter limiter(double cap, double rate) {
        return new TokenBucketRateLimiter(t -> new Limit(cap, rate), now::get);
    }

    @Test
    void allowsUpToCapacityThenRejects() {
        var l = limiter(3, 1);
        assertThat(l.tryAcquire("a").allowed()).isTrue();
        assertThat(l.tryAcquire("a").allowed()).isTrue();
        var third = l.tryAcquire("a");
        assertThat(third.allowed()).isTrue();
        assertThat(third.remaining()).isZero();
        var denied = l.tryAcquire("a");
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfterMillis()).isEqualTo(1000);
    }

    @Test
    void refillsOverTime() {
        var l = limiter(2, 2);
        l.tryAcquire("a");
        l.tryAcquire("a");
        assertThat(l.tryAcquire("a").allowed()).isFalse();
        now.addAndGet(500); // 1 token
        assertThat(l.tryAcquire("a").allowed()).isTrue();
        assertThat(l.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void doesNotExceedCapacityAfterLongIdle() {
        var l = limiter(2, 10);
        now.addAndGet(60_000);
        assertThat(l.tryAcquire("a").allowed()).isTrue();
        assertThat(l.tryAcquire("a").allowed()).isTrue();
        assertThat(l.tryAcquire("a").allowed()).isFalse();
    }

    @Test
    void tenantsAreIsolated() {
        var l = limiter(1, 0.1);
        assertThat(l.tryAcquire("a").allowed()).isTrue();
        assertThat(l.tryAcquire("a").allowed()).isFalse();
        assertThat(l.tryAcquire("b").allowed()).isTrue();
    }

    @Test
    void perTenantLimitsAreHonoured() {
        var l = new TokenBucketRateLimiter(t -> t.equals("vip") ? new Limit(5, 1) : new Limit(1, 1), now::get);
        for (int i = 0; i < 5; i++) assertThat(l.tryAcquire("vip").allowed()).isTrue();
        assertThat(l.tryAcquire("vip").allowed()).isFalse();
        assertThat(l.tryAcquire("basic").allowed()).isTrue();
        assertThat(l.tryAcquire("basic").allowed()).isFalse();
    }
}
