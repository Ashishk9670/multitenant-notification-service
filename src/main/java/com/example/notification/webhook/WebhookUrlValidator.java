package com.example.notification.webhook;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * SSRF guard for tenant-supplied webhook URLs: https only, no credentials in the URL, and every address the host
 * resolves to must be publicly routable (loopback, private, link-local incl. cloud metadata, CGNAT, documentation,
 * multicast and IPv6 unique-local ranges are rejected). Used at registration and again just before each delivery.
 */
@Component
public class WebhookUrlValidator {

    /** Resolves a host name or IP literal to all of its addresses. */
    @FunctionalInterface
    public interface Resolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public static class InvalidWebhookUrlException extends RuntimeException {
        public InvalidWebhookUrlException(String message) {
            super(message);
        }
    }

    private static final int MAX_URL_LENGTH = 2048;

    private final Resolver resolver;

    @Autowired
    public WebhookUrlValidator() {
        this(InetAddress::getAllByName);
    }

    public WebhookUrlValidator(Resolver resolver) {
        this.resolver = resolver;
    }

    /** @return the parsed URI when acceptable; throws InvalidWebhookUrlException otherwise. */
    public URI validate(String url) {
        if (url == null || url.isBlank()) throw new InvalidWebhookUrlException("Webhook URL is required");
        if (url.length() > MAX_URL_LENGTH) throw new InvalidWebhookUrlException("Webhook URL is too long");
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (URISyntaxException e) {
            throw new InvalidWebhookUrlException("Webhook URL is malformed");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new InvalidWebhookUrlException("Webhook URL must use https");
        }
        if (uri.getRawUserInfo() != null) {
            throw new InvalidWebhookUrlException("Webhook URL must not contain credentials");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new InvalidWebhookUrlException("Webhook URL must have a host");
        if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
        String lower = host.toLowerCase(Locale.ROOT);
        if (lower.equals("localhost") || lower.endsWith(".localhost") || lower.endsWith(".local")
                || lower.endsWith(".internal")) {
            throw new InvalidWebhookUrlException("Webhook host is not allowed");
        }
        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException e) {
            throw new InvalidWebhookUrlException("Webhook host cannot be resolved");
        }
        if (addresses == null || addresses.length == 0) {
            throw new InvalidWebhookUrlException("Webhook host cannot be resolved");
        }
        for (InetAddress a : addresses) {
            if (isBlocked(a)) throw new InvalidWebhookUrlException("Webhook host resolves to a non-public address");
        }
        return uri;
    }

    static boolean isBlocked(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int b0 = b[0] & 0xff;
            int b1 = b[1] & 0xff;
            int b2 = b[2] & 0xff;
            return b0 == 0                                              // 0.0.0.0/8
                    || (b0 == 100 && (b1 & 0xc0) == 64)                 // 100.64.0.0/10 CGNAT
                    || (b0 == 192 && b1 == 0 && b2 == 0)                // 192.0.0.0/24
                    || (b0 == 192 && b1 == 0 && b2 == 2)                // 192.0.2.0/24
                    || (b0 == 198 && (b1 & 0xfe) == 18)                 // 198.18.0.0/15
                    || (b0 == 198 && b1 == 51 && b2 == 100)             // 198.51.100.0/24
                    || (b0 == 203 && b1 == 0 && b2 == 113)              // 203.0.113.0/24
                    || b0 >= 240;                                       // reserved + broadcast
        }
        if (a instanceof Inet6Address) {
            int b0 = b[0] & 0xff;
            boolean uniqueLocal = (b0 & 0xfe) == 0xfc;                  // fc00::/7
            boolean documentation = b0 == 0x20 && b[1] == 0x01 && b[2] == 0x0d && (b[3] & 0xff) == 0xb8;
            boolean nat64 = b0 == 0x00 && b[1] == 0x64 && (b[2] & 0xff) == 0xff && (b[3] & 0xff) == 0x9b;
            return uniqueLocal || documentation || nat64;
        }
        return false;
    }
}
