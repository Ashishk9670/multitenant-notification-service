package com.example.notification.it;

import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/** Real Kafka + Redis + Postgres via Testcontainers (skipped automatically when Docker is unavailable). */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "notification.retry.initial-delay=200ms",
        "notification.retry.max-retries=2",
        "notification.retry.jitter=0",
        "notification.rate-limit.tenants.ratelimited.capacity=3",
        "notification.rate-limit.tenants.ratelimited.refill-per-second=0.01",
        "notification.quota.tenants.quotatenant=2",
        "notification.rate-limit.tenants.quotatenant.capacity=100",
        "notification.rate-limit.tenants.quotatenant.refill-per-second=100"
})
abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    static {
        if (org.testcontainers.DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
            REDIS.start();
            KAFKA.start();
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.redis.host", REDIS::getHost);
        r.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        r.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @LocalServerPort int port;

    @BeforeEach
    void rest() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = port;
    }

    static String uniq(String p) { return p + "-" + java.util.UUID.randomUUID().toString().substring(0, 8); }
}
