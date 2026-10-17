package com.example.notification;

import com.example.notification.config.NotificationProperties;
import com.example.notification.sender.CircuitBreakerProperties;
import com.example.notification.webhook.WebhookProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({NotificationProperties.class, CircuitBreakerProperties.class, WebhookProperties.class})
public class NotificationApplication {
    public static void main(String[] args) {
        SpringApplication.run(NotificationApplication.class, args);
    }
}
