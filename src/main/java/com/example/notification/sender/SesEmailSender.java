package com.example.notification.sender;

import com.example.notification.model.Channel;
import com.example.notification.model.DeliveryEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Email via the AWS SES v2 REST API (SendEmail), signed with SigV4. Enabled with
 * notification.provider.email.type=ses. Point notification.provider.email.ses.endpoint at a sandbox/emulator to
 * avoid AWS entirely.
 */
@Component
@ConditionalOnProperty(name = "notification.provider.email.type", havingValue = "ses")
public class SesEmailSender implements NotificationSender {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final ProviderHttp http;
    private final Clock clock;
    private final String region;
    private final String accessKey;
    private final String secretKey;
    private final String from;
    private final URI endpoint;

    @Autowired
    public SesEmailSender(ProviderProperties props, ProviderHttp http) {
        this(props, http, Clock.systemUTC());
    }

    public SesEmailSender(ProviderProperties props, ProviderHttp http, Clock clock) {
        ProviderProperties.Ses s = props.email().ses();
        this.region = TwilioSmsSender.require(s.region(), "notification.provider.email.ses.region");
        this.accessKey = TwilioSmsSender.require(s.accessKeyId(), "notification.provider.email.ses.access-key-id");
        this.secretKey = TwilioSmsSender.require(s.secretAccessKey(), "notification.provider.email.ses.secret-access-key");
        this.from = TwilioSmsSender.require(s.from(), "notification.provider.email.ses.from");
        String base = s.endpoint() == null || s.endpoint().isBlank()
                ? "https://email." + region + ".amazonaws.com" : s.endpoint().replaceAll("/+$", "");
        this.endpoint = URI.create(base + "/v2/email/outbound-emails");
        this.http = http;
        this.clock = clock;
    }

    @Override
    public Channel channel() { return Channel.EMAIL; }

    @Override
    public void send(DeliveryEvent e) {
        String body;
        try {
            body = JSON.writeValueAsString(Map.of(
                    "FromEmailAddress", from,
                    "Destination", Map.of("ToAddresses", List.of(e.recipient())),
                    "Content", Map.of("Simple", Map.of(
                            "Subject", Map.of("Data", e.subject() == null ? "" : e.subject(), "Charset", "UTF-8"),
                            "Body", Map.of("Text", Map.of("Data", e.body(), "Charset", "UTF-8"))))));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialise SES payload", ex);
        }
        var now = clock.instant();
        String amzDate = AMZ_DATE.format(now);
        String date = DATE.format(now);
        String host = endpoint.getAuthority();
        String contentType = "application/json";
        http.post("ses", endpoint, Map.of(
                "Content-Type", contentType,
                "X-Amz-Date", amzDate,
                "Authorization", authorization(host, contentType, amzDate, date, body)), body);
    }

    /** SigV4 with signed headers content-type;host;x-amz-date. Package-private for tests. */
    String authorization(String host, String contentType, String amzDate, String date, String body) {
        String signedHeaders = "content-type;host;x-amz-date";
        String canonical = "POST\n" + endpoint.getRawPath() + "\n\n"
                + "content-type:" + contentType + "\nhost:" + host + "\nx-amz-date:" + amzDate + "\n\n"
                + signedHeaders + "\n" + sha256(body);
        String scope = date + "/" + region + "/ses/aws4_request";
        String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256(canonical);
        byte[] k = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        k = hmac(k, region);
        k = hmac(k, "ses");
        k = hmac(k, "aws4_request");
        String signature = HexFormat.of().formatHex(hmac(k, toSign));
        return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
