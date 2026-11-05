package com.example.notification.sender;

import com.example.notification.model.Channel;
import com.example.notification.model.DeliveryEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Push via Firebase Cloud Messaging HTTP v1. Enabled with notification.provider.push.type=fcm. */
@Component
@ConditionalOnProperty(name = "notification.provider.push.type", havingValue = "fcm")
public class FcmPushSender implements NotificationSender {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ProviderHttp http;
    private final URI endpoint;
    private final String bearer;

    public FcmPushSender(ProviderProperties props, ProviderHttp http) {
        ProviderProperties.Fcm f = props.push().fcm();
        String project = TwilioSmsSender.require(f.projectId(), "notification.provider.push.fcm.project-id");
        this.bearer = "Bearer " + TwilioSmsSender.require(f.accessToken(), "notification.provider.push.fcm.access-token");
        this.endpoint = URI.create(f.baseUrl().replaceAll("/+$", "") + "/v1/projects/"
                + URLEncoder.encode(project, StandardCharsets.UTF_8) + "/messages:send");
        this.http = http;
    }

    @Override
    public Channel channel() { return Channel.PUSH; }

    @Override
    public void send(DeliveryEvent e) {
        Map<String, Object> notification = new LinkedHashMap<>();
        if (e.subject() != null && !e.subject().isBlank()) notification.put("title", e.subject());
        notification.put("body", e.body());
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("token", e.recipient());
        message.put("notification", notification);
        String json;
        try {
            json = JSON.writeValueAsString(Map.of("message", message));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialise FCM payload", ex);
        }
        http.post("fcm", endpoint, Map.of("Authorization", bearer, "Content-Type", "application/json"), json);
    }
}
