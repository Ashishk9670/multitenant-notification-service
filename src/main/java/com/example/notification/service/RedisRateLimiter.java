package com.example.notification.service;

import com.example.notification.config.NotificationProperties;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Distributed per-tenant token bucket executed atomically in Redis via Lua. */
@Component
public class RedisRateLimiter implements RateLimiter {

    private static final String LUA = """
            local cap = tonumber(ARGV[1])
            local rate = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
            local tokens = tonumber(data[1])
            local ts = tonumber(data[2])
            if tokens == nil then tokens = cap; ts = now end
            local elapsed = math.max(0, now - ts) / 1000.0
            tokens = math.min(cap, tokens + elapsed * rate)
            local allowed = 0
            local retry = 0
            if tokens >= 1 then
              tokens = tokens - 1
              allowed = 1
            else
              retry = math.ceil((1 - tokens) / rate * 1000)
            end
            redis.call('HSET', KEYS[1], 'tokens', tostring(tokens), 'ts', tostring(now))
            redis.call('PEXPIRE', KEYS[1], math.ceil(cap / rate * 1000) + 1000)
            return {allowed, math.floor(tokens), retry}
            """;

    private final StringRedisTemplate redis;
    private final NotificationProperties props;
    @SuppressWarnings("rawtypes")
    private final DefaultRedisScript<List> script = new DefaultRedisScript<>(LUA, List.class);

    public RedisRateLimiter(StringRedisTemplate redis, NotificationProperties props) {
        this.redis = redis;
        this.props = props;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Decision tryAcquire(String tenantId) {
        var limit = props.rateLimit().forTenant(tenantId);
        List<Long> r = (List<Long>) redis.execute(script, List.of("rl:" + tenantId),
                String.valueOf(limit.capacity()), String.valueOf(limit.refillPerSecond()),
                String.valueOf(System.currentTimeMillis()));
        return new Decision(r.get(0) == 1L, r.get(1), r.get(2));
    }
}
