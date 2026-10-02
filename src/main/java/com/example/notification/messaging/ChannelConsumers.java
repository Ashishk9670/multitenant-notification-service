package com.example.notification.messaging;

import com.example.notification.config.NotificationProperties;
import com.example.notification.model.Channel;
import com.example.notification.model.DeliveryEvent;
import com.example.notification.sender.NotificationSender;
import com.example.notification.service.Backoff;
import com.example.notification.service.DeliveryLogService;
import com.example.notification.service.DeliveryStatus;
import com.example.notification.service.NotificationMetrics;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

/** Email / SMS / push consumers, the retry-topic consumer and DLQ publishing. */
@Component
public class ChannelConsumers {

    private static final Logger log = LoggerFactory.getLogger(ChannelConsumers.class);

    private final Map<Channel, NotificationSender> senders = new EnumMap<>(Channel.class);
    private final KafkaTemplate<String, Object> kafka;
    private final NotificationProperties props;
    private final Backoff backoff;
    private final DeliveryLogService deliveryLog;
    private final NotificationMetrics metrics;

    public ChannelConsumers(List<NotificationSender> senderList, KafkaTemplate<String, Object> kafka,
                            NotificationProperties props, Backoff backoff, DeliveryLogService deliveryLog,
                            NotificationMetrics metrics) {
        senderList.forEach(s -> senders.put(s.channel(), s));
        this.kafka = kafka;
        this.props = props;
        this.backoff = backoff;
        this.deliveryLog = deliveryLog;
        this.metrics = metrics;
    }

    @KafkaListener(topics = "${notification.topics.email}", groupId = "channel-email", concurrency = "3")
    public void email(DeliveryEvent e) { deliver(e); }

    @KafkaListener(topics = "${notification.topics.sms}", groupId = "channel-sms", concurrency = "3")
    public void sms(DeliveryEvent e) { deliver(e); }

    @KafkaListener(topics = "${notification.topics.push}", groupId = "channel-push", concurrency = "3")
    public void push(DeliveryEvent e) { deliver(e); }

    /** Retry topics hold events until their x-not-before header is due, then re-dispatch to the channel topic. */
    @KafkaListener(topicPattern = "${notification.topics.retry-pattern}", groupId = "retry")
    public void retry(DeliveryEvent e, @Header(name = Headers.NOT_BEFORE, required = false) byte[] notBefore)
            throws InterruptedException {
        if (notBefore != null && notBefore.length == Long.BYTES) {
            long wait = ByteBuffer.wrap(notBefore).getLong() - System.currentTimeMillis();
            if (wait > 0) {
                Thread.sleep(wait);
            }
        }
        kafka.send(channelTopic(e.channel()), e.tenantId() + ":" + e.id(), e);
    }

    void deliver(DeliveryEvent e) {
        try {
            senders.get(e.channel()).send(e);
            deliveryLog.record(e, DeliveryStatus.SENT, null);
            metrics.sent(e.tenantId(), e.channel());
        } catch (Exception ex) {
            handleFailure(e, ex);
        }
    }

    void handleFailure(DeliveryEvent e, Exception ex) {
        int next = e.attempt() + 1;
        String err = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        DeliveryEvent failed = e.withAttempt(next, err);
        metrics.failed(e.tenantId(), e.channel());
        if (next > props.retry().maxRetries()) {
            log.warn("Delivery {} {} exhausted retries, sending to DLQ: {}", e.id(), e.channel(), err);
            deliveryLog.record(failed, DeliveryStatus.DEAD, err);
            metrics.deadLettered(e.tenantId(), e.channel());
            kafka.send(props.topics().dlq(), e.tenantId() + ":" + e.id(), failed);
            return;
        }
        Duration delay = backoff.delayFor(next);
        log.info("Delivery {} {} failed (attempt {}), retry in {}", e.id(), e.channel(), next, delay);
        deliveryLog.record(failed, DeliveryStatus.RETRYING, err);
        metrics.retried(e.tenantId(), e.channel());
        ProducerRecord<String, Object> rec = new ProducerRecord<>(props.topics().retryPrefix() + "." + next,
                e.tenantId() + ":" + e.id(), failed);
        rec.headers().add(Headers.NOT_BEFORE, ByteBuffer.allocate(Long.BYTES)
                .putLong(System.currentTimeMillis() + delay.toMillis()).array());
        kafka.send(rec);
    }

    String channelTopic(Channel ch) {
        return switch (ch) {
            case EMAIL -> props.topics().email();
            case SMS -> props.topics().sms();
            case PUSH -> props.topics().push();
        };
    }
}
