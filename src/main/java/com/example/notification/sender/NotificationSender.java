package com.example.notification.sender;

import com.example.notification.model.Channel;
import com.example.notification.model.DeliveryEvent;

public interface NotificationSender {
    Channel channel();

    /** Deliver or throw to trigger the retry flow. */
    void send(DeliveryEvent event) throws Exception;
}
