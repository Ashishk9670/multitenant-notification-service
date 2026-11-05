package com.example.notification.webhook;

import java.net.URI;
import java.util.Map;

/** Minimal HTTP POST abstraction so the dispatcher can be tested without a network. */
public interface WebhookTransport {
    /** @return the HTTP status code; throws on I/O failure or timeout. */
    int post(URI uri, Map<String, String> headers, String body) throws Exception;
}
