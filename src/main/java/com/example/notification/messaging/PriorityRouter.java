package com.example.notification.messaging;

import com.example.notification.config.NotificationProperties;
import com.example.notification.model.*;
import com.example.notification.service.DeliveryLogService;
import com.example.notification.service.DeliveryStatus;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Consumes the priority topics (thread counts weighted HIGH > NORMAL > LOW via container factories)
 * and fans out one {@link DeliveryEvent} per channel to the channel topics.
 */
@Component
public class PriorityRouter {

    private static final Logger log = LoggerFactory.getLogger(PriorityRouter.class);

    private final KafkaTemplate<String, Object> kafka;
    private final NotificationProperties props;
    private final DeliveryLogService deliveryLog;

    public PriorityRouter(KafkaTemplate<String, Object> kafka, NotificationProperties props, DeliveryLogService deliveryLog) {
        this.kafka = kafka;
        this.props = props;
        this.deliveryLog = deliveryLog;
    }

    @KafkaListener(topics = "${notification.topics.high}", groupId = "router-high", containerFactory = "highPriorityFactory")
    public void high(NotificationEvent e) { fanOut(e); }

    @KafkaListener(topics = "${notification.topics.normal}", groupId = "router-normal", containerFactory = "normalPriorityFactory")
    public void normal(NotificationEvent e) { fanOut(e); }

    @KafkaListener(topics = "${notification.topics.low}", groupId = "router-low", containerFactory = "lowPriorityFactory")
    public void low(NotificationEvent e) { fanOut(e); }

    void fanOut(NotificationEvent e) {
        for (Map.Entry<Channel, Content> en : e.contents().entrySet()) {
            Channel ch = en.getKey();
            DeliveryEvent d = new DeliveryEvent(e.id(), e.tenantId(), ch, e.priority(), e.recipients().forChannel(ch),
                    en.getValue().subject(), en.getValue().body(), 0, e.createdAt(), null);
            deliveryLog.record(d, DeliveryStatus.ACCEPTED, null);
            kafka.send(channelTopic(ch), e.tenantId() + ":" + e.id(), d);
        }
        log.debug("Fanned out notification {} to {}", e.id(), e.contents().keySet());
    }

    String channelTopic(Channel ch) {
        return switch (ch) {
            case EMAIL -> props.topics().email();
            case SMS -> props.topics().sms();
            case PUSH -> props.topics().push();
        };
    }
}
