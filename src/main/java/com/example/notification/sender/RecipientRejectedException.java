package com.example.notification.sender;

/**
 * A provider refused one specific delivery because of the recipient or the request (invalid address or phone number,
 * unregistered device token, malformed message), not because the provider is unhealthy.
 *
 * <p>The circuit breaker ignores these: if they counted, one tenant sending to bad recipients could open the shared
 * per-channel breaker and make healthy deliveries for every other tenant fail fast. The delivery itself still goes
 * through the normal retry/DLQ flow.
 */
public class RecipientRejectedException extends RuntimeException {
    public RecipientRejectedException(String message) {
        super(message);
    }
}
