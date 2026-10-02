package com.example.notification.api;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() { return status; }

    public static class RateLimited extends ApiException {
        private final long retryAfterMillis;

        public RateLimited(String message, long retryAfterMillis) {
            super(HttpStatus.TOO_MANY_REQUESTS, message);
            this.retryAfterMillis = retryAfterMillis;
        }

        public long getRetryAfterMillis() { return retryAfterMillis; }
    }
}
