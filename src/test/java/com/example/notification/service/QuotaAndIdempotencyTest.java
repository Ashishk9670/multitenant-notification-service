package com.example.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.example.notification.config.NotificationProperties;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class QuotaAndIdempotencyTest {

    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> ops = mock(ValueOperations.class);
    NotificationProperties props = new NotificationProperties(null, null, null, null,
            new NotificationProperties.Quota(2, Map.of("big", 100L)), Duration.ofHours(1));

    @BeforeEach
    void setUp() {
        when(redis.opsForValue()).thenReturn(ops);
    }

    @Test
    void quotaAllowsUpToLimit() {
        when(ops.increment(anyString())).thenReturn(1L, 2L, 3L);
        QuotaService q = new QuotaService(redis, props);
        assertThat(q.tryConsume("t")).isTrue();
        assertThat(q.tryConsume("t")).isTrue();
        assertThat(q.tryConsume("t")).isFalse();
        verify(ops).decrement(anyString());
        verify(redis, times(1)).expire(anyString(), any(Duration.class));
    }

    @Test
    void quotaUsesPerTenantOverride() {
        when(ops.increment(anyString())).thenReturn(50L);
        assertThat(new QuotaService(redis, props).tryConsume("big")).isTrue();
    }

    @Test
    void quotaUsedReadsCounter() {
        when(ops.get(anyString())).thenReturn("7", (String) null);
        QuotaService q = new QuotaService(redis, props);
        assertThat(q.used("t")).isEqualTo(7);
        assertThat(q.used("t")).isZero();
    }

    @Test
    void idempotencyReservesNewKey() {
        when(ops.setIfAbsent(eq("idem:t:k"), eq("n1"), any(Duration.class))).thenReturn(true);
        assertThat(new IdempotencyService(redis, props).reserve("t", "k", "n1")).isEmpty();
    }

    @Test
    void idempotencyReturnsExistingId() {
        when(ops.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(ops.get("idem:t:k")).thenReturn("old");
        assertThat(new IdempotencyService(redis, props).reserve("t", "k", "n2")).isEqualTo(Optional.of("old"));
    }

    @Test
    void idempotencyReleaseDeletesKey() {
        new IdempotencyService(redis, props).release("t", "k");
        verify(redis).delete("idem:t:k");
    }

    private static String eq(String s) { return org.mockito.ArgumentMatchers.eq(s); }
}
