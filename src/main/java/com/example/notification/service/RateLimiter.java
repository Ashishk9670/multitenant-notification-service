package com.example.notification.service;

public interface RateLimiter {

    Decision tryAcquire(String tenantId);

    record Decision(boolean allowed, long remaining, long retryAfterMillis) { }
}
