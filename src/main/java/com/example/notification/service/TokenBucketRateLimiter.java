package com.example.notification.service;

import com.example.notification.config.NotificationProperties;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.LongSupplier;

/** In-memory token bucket per tenant. Same algorithm as the Redis Lua script; used as reference/fallback. */
public class TokenBucketRateLimiter implements RateLimiter {

    private static final class Bucket {
        double tokens;
        long lastRefillMillis;
    }

    private final Function<String, NotificationProperties.Limit> limits;
    private final LongSupplier clockMillis;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public TokenBucketRateLimiter(Function<String, NotificationProperties.Limit> limits, LongSupplier clockMillis) {
        this.limits = limits;
        this.clockMillis = clockMillis;
    }

    @Override
    public Decision tryAcquire(String tenantId) {
        NotificationProperties.Limit limit = limits.apply(tenantId);
        Bucket b = buckets.computeIfAbsent(tenantId, k -> {
            Bucket nb = new Bucket();
            nb.tokens = limit.capacity();
            nb.lastRefillMillis = clockMillis.getAsLong();
            return nb;
        });
        synchronized (b) {
            long now = clockMillis.getAsLong();
            double elapsedSec = Math.max(0, now - b.lastRefillMillis) / 1000.0;
            b.tokens = Math.min(limit.capacity(), b.tokens + elapsedSec * limit.refillPerSecond());
            b.lastRefillMillis = now;
            if (b.tokens >= 1.0) {
                b.tokens -= 1.0;
                return new Decision(true, (long) Math.floor(b.tokens), 0);
            }
            long retry = (long) Math.ceil((1.0 - b.tokens) / limit.refillPerSecond() * 1000.0);
            return new Decision(false, 0, retry);
        }
    }
}
