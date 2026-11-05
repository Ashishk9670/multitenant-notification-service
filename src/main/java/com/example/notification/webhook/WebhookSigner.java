package com.example.notification.webhook;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * HMAC-SHA256 payload signing. The signature covers "{timestamp}.{body}" so receivers can reject replays by checking
 * the X-Webhook-Timestamp header, and is sent as "sha256=<hex>" in X-Webhook-Signature.
 */
@Component
public class WebhookSigner {

    public String sign(String secret, long timestampSeconds, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal((timestampSeconds + "." + body).getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(sig);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** Constant-time check, intended for receivers and tests. */
    public boolean verify(String secret, long timestampSeconds, String body, String signature) {
        if (signature == null) return false;
        return MessageDigest.isEqual(sign(secret, timestampSeconds, body).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }
}
