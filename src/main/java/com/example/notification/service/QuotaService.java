package com.example.notification.service;

import com.example.notification.config.NotificationProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Per-tenant daily quota counted in Redis (UTC day). */
@Service
public class QuotaService {

    private final StringRedisTemplate redis;
    private final NotificationProperties props;
    private final Clock clock = Clock.systemUTC();

    public QuotaService(StringRedisTemplate redis, NotificationProperties props) {
        this.redis = redis;
        this.props = props;
    }

    private String key(String tenantId) {
        return "quota:" + tenantId + ":" + LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    /** @return true when the unit was consumed, false when the quota is exhausted. */
    public boolean tryConsume(String tenantId) {
        String k = key(tenantId);
        Long used = redis.opsForValue().increment(k);
        if (used != null && used == 1L) {
            redis.expire(k, Duration.ofHours(48));
        }
        if (used != null && used > props.quota().forTenant(tenantId)) {
            redis.opsForValue().decrement(k);
            return false;
        }
        return true;
    }

    public void refund(String tenantId) {
        redis.opsForValue().decrement(key(tenantId));
    }

    public long used(String tenantId) {
        String v = redis.opsForValue().get(key(tenantId));
        return v == null ? 0 : Long.parseLong(v);
    }
}
