package com.example.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.notification.config.NotificationProperties.Limit;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.DoubleRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Scale;

/** Invariants of the token bucket that must hold for every capacity, refill rate and call pattern. */
class TokenBucketPropertyTest {

    /** A step is "advance the clock by N ms, then try to acquire". */
    @Provide
    Arbitrary<List<Integer>> steps() {
        return Arbitraries.integers().between(0, 5_000).list().ofMinSize(1).ofMaxSize(200);
    }

    @Property
    void neverAdmitsMoreThanCapacityPlusRefill(@ForAll @Scale(4) @DoubleRange(min = 1, max = 100) double capacity,
                                               @ForAll @Scale(4) @DoubleRange(min = 0.1, max = 100) double rate,
                                               @ForAll("steps") List<Integer> advances) {
        AtomicLong now = new AtomicLong(1_000_000);
        var limiter = new TokenBucketRateLimiter(t -> new Limit(capacity, rate), now::get);
        long allowed = 0;
        long elapsedMs = 0;
        for (int adv : advances) {
            now.addAndGet(adv);
            elapsedMs += adv;
            if (limiter.tryAcquire("t").allowed()) allowed++;
        }
        assertThat((double) allowed).isLessThanOrEqualTo(capacity + rate * elapsedMs / 1000.0 + 1e-6);
    }

    @Property
    void remainingStaysWithinBounds(@ForAll @Scale(4) @DoubleRange(min = 1, max = 100) double capacity,
                                    @ForAll @Scale(4) @DoubleRange(min = 0.1, max = 100) double rate,
                                    @ForAll("steps") List<Integer> advances) {
        AtomicLong now = new AtomicLong(0);
        var limiter = new TokenBucketRateLimiter(t -> new Limit(capacity, rate), now::get);
        for (int adv : advances) {
            now.addAndGet(adv);
            var d = limiter.tryAcquire("t");
            assertThat(d.remaining()).isBetween(0L, (long) Math.floor(capacity));
            assertThat(d.retryAfterMillis()).isGreaterThanOrEqualTo(0);
            assertThat(d.allowed()).isEqualTo(d.retryAfterMillis() == 0);
        }
    }

    @Property
    void waitingTheAdvertisedRetryAfterAlwaysSucceeds(@ForAll @Scale(4) @DoubleRange(min = 1, max = 50) double capacity,
                                                      @ForAll @Scale(4) @DoubleRange(min = 0.1, max = 50) double rate,
                                                      @ForAll @IntRange(min = 1, max = 300) int burst) {
        AtomicLong now = new AtomicLong(0);
        var limiter = new TokenBucketRateLimiter(t -> new Limit(capacity, rate), now::get);
        RateLimiter.Decision last = null;
        for (int i = 0; i < burst; i++) last = limiter.tryAcquire("t");
        if (!last.allowed()) {
            now.addAndGet(last.retryAfterMillis());
            assertThat(limiter.tryAcquire("t").allowed()).isTrue();
        }
    }

    @Property
    void tenantsAreIsolated(@ForAll @IntRange(min = 1, max = 50) int capacity, @ForAll @IntRange(min = 0, max = 200) int hammer) {
        AtomicLong now = new AtomicLong(0);
        var limiter = new TokenBucketRateLimiter(t -> new Limit(capacity, 0.001), now::get);
        for (int i = 0; i < hammer; i++) limiter.tryAcquire("noisy");
        assertThat(limiter.tryAcquire("quiet").allowed()).isTrue();
        assertThat(limiter.tryAcquire("quiet").remaining()).isEqualTo(Math.max(0, capacity - 2));
    }
}
