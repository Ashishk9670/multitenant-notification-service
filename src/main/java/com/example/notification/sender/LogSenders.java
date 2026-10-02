package com.example.notification.sender;

import com.example.notification.model.Channel;
import com.example.notification.model.DeliveryEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Stub providers that only log. A recipient containing "fail" always fails; "flaky" fails the first 2 attempts. */
public final class LogSenders {
    private LogSenders() { }

    abstract static class Base implements NotificationSender {
        private final Logger log = LoggerFactory.getLogger(getClass());

        @Override
        public void send(DeliveryEvent e) {
            String r = e.recipient();
            if (r.contains("fail") || (r.contains("flaky") && e.attempt() < 2)) {
                throw new IllegalStateException("Stub provider rejected delivery to " + channel());
            }
            log.info("[{}] tenant={} id={} to={} subject={} body={}", channel(), e.tenantId(), e.id(), r, e.subject(), e.body());
        }
    }

    @Component
    public static class Email extends Base {
        public Channel channel() { return Channel.EMAIL; }
    }

    @Component
    public static class Sms extends Base {
        public Channel channel() { return Channel.SMS; }
    }

    @Component
    public static class Push extends Base {
        public Channel channel() { return Channel.PUSH; }
    }
}
