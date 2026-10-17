package com.example.notification.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WebhookSignerTest {

    WebhookSigner signer = new WebhookSigner();

    @Test
    void matchesKnownHmacSha256Vector() {
        // HMAC-SHA256(key="key", msg="1700000000.{}") computed independently
        javax.crypto.Mac mac;
        try {
            mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec("key".getBytes(), "HmacSHA256"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        String expected = "sha256=" + java.util.HexFormat.of().formatHex(mac.doFinal("1700000000.{}".getBytes()));
        assertThat(signer.sign("key", 1700000000L, "{}")).isEqualTo(expected).startsWith("sha256=").hasSize(7 + 64);
    }

    @Test
    void verifyAcceptsGenuineAndRejectsTampering() {
        String sig = signer.sign("s3cret", 1700000000L, "{\"a\":1}");
        assertThat(signer.verify("s3cret", 1700000000L, "{\"a\":1}", sig)).isTrue();
        assertThat(signer.verify("other", 1700000000L, "{\"a\":1}", sig)).isFalse();
        assertThat(signer.verify("s3cret", 1700000001L, "{\"a\":1}", sig)).isFalse();
        assertThat(signer.verify("s3cret", 1700000000L, "{\"a\":2}", sig)).isFalse();
        assertThat(signer.verify("s3cret", 1700000000L, "{\"a\":1}", null)).isFalse();
    }
}
