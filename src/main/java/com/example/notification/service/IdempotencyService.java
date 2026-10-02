package com.example.notification.service;

import com.example.notification.config.NotificationProperties;
import java.util.Optional;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class IdempotencyService {

    private final StringRedisTemplate redis;
    private final NotificationProperties props;

    public IdempotencyService(StringRedisTemplate redis, NotificationProperties props) {
        this.redis = redis;
        this.props = props;
    }

    private String key(String tenantId, String idemKey) {
        return "idem:" + tenantId + ":" + idemKey;
    }

    /** @return empty if the key was newly reserved; otherwise the id of the previously accepted notification. */
    public Optional<String> reserve(String tenantId, String idemKey, String notificationId) {
        Boolean ok = redis.opsForValue().setIfAbsent(key(tenantId, idemKey), notificationId, props.idempotencyTtl());
        if (Boolean.TRUE.equals(ok)) {
            return Optional.empty();
        }
        return Optional.ofNullable(redis.opsForValue().get(key(tenantId, idemKey)));
    }

    public void release(String tenantId, String idemKey) {
        redis.delete(key(tenantId, idemKey));
    }
}
