package com.example.notification.config;

import com.example.notification.model.Priority;
import java.util.ArrayList;
import java.util.List;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaAdmin;

@Configuration
public class KafkaConfig {

    /** Partitions equal the consumer weight so each priority can use all of its threads. */
    @Bean
    KafkaAdmin.NewTopics notificationTopics(NotificationProperties p) {
        List<NewTopic> topics = new ArrayList<>();
        topics.add(TopicBuilder.name(p.topics().high()).partitions(p.consumers().high()).replicas(1).build());
        topics.add(TopicBuilder.name(p.topics().normal()).partitions(p.consumers().normal()).replicas(1).build());
        topics.add(TopicBuilder.name(p.topics().low()).partitions(Math.max(1, p.consumers().low())).replicas(1).build());
        for (String t : List.of(p.topics().email(), p.topics().sms(), p.topics().push())) {
            topics.add(TopicBuilder.name(t).partitions(3).replicas(1).build());
        }
        for (int i = 1; i <= p.retry().maxRetries(); i++) {
            topics.add(TopicBuilder.name(p.topics().retryPrefix() + "." + i).partitions(1).replicas(1).build());
        }
        topics.add(TopicBuilder.name(p.topics().dlq()).partitions(1).replicas(1).build());
        return new KafkaAdmin.NewTopics(topics.toArray(NewTopic[]::new));
    }

    /** Priority-weighted listener containers: more threads for higher priority topics. */
    @Bean
    ConcurrentKafkaListenerContainerFactory<String, Object> highPriorityFactory(
            ConsumerFactory<String, Object> cf, NotificationProperties p) {
        return factory(cf, p.consumers().high());
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, Object> normalPriorityFactory(
            ConsumerFactory<String, Object> cf, NotificationProperties p) {
        return factory(cf, p.consumers().normal());
    }

    @Bean
    ConcurrentKafkaListenerContainerFactory<String, Object> lowPriorityFactory(
            ConsumerFactory<String, Object> cf, NotificationProperties p) {
        return factory(cf, p.consumers().low());
    }

    private ConcurrentKafkaListenerContainerFactory<String, Object> factory(ConsumerFactory<String, Object> cf, int concurrency) {
        var f = new ConcurrentKafkaListenerContainerFactory<String, Object>();
        f.setConsumerFactory(cf);
        f.setConcurrency(Math.max(1, concurrency));
        return f;
    }

    public static String topicFor(NotificationProperties p, Priority priority) {
        return switch (priority) {
            case HIGH -> p.topics().high();
            case NORMAL -> p.topics().normal();
            case LOW -> p.topics().low();
        };
    }
}
