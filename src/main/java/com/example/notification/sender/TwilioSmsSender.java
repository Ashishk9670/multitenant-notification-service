package com.example.notification.sender;

import com.example.notification.model.Channel;
import com.example.notification.model.DeliveryEvent;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** SMS via the Twilio Messages REST API. Enabled with notification.provider.sms.type=twilio. */
@Component
@ConditionalOnProperty(name = "notification.provider.sms.type", havingValue = "twilio")
public class TwilioSmsSender implements NotificationSender {

    private final ProviderHttp http;
    private final URI endpoint;
    private final String from;
    private final String authHeader;

    public TwilioSmsSender(ProviderProperties props, ProviderHttp http) {
        ProviderProperties.Twilio t = props.sms().twilio();
        String sid = require(t.accountSid(), "notification.provider.sms.twilio.account-sid");
        String token = require(t.authToken(), "notification.provider.sms.twilio.auth-token");
        this.from = require(t.from(), "notification.provider.sms.twilio.from");
        String base = t.baseUrl().replaceAll("/+$", "");
        this.endpoint = URI.create(base + "/2010-04-01/Accounts/" + URLEncoder.encode(sid, StandardCharsets.UTF_8)
                + "/Messages.json");
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString((sid + ":" + token).getBytes(StandardCharsets.UTF_8));
        this.http = http;
    }

    @Override
    public Channel channel() { return Channel.SMS; }

    @Override
    public void send(DeliveryEvent e) {
        String form = "To=" + enc(e.recipient()) + "&From=" + enc(from) + "&Body=" + enc(e.body());
        http.post("twilio", endpoint, Map.of(
                "Authorization", authHeader,
                "Content-Type", "application/x-www-form-urlencoded"), form);
    }

    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    static String require(String v, String name) {
        if (v == null || v.isBlank()) throw new IllegalStateException("Missing required property " + name);
        return v;
    }
}
