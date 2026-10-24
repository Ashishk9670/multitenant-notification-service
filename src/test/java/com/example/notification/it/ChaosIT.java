package com.example.notification.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Chaos test: Redis and Kafka sit behind Toxiproxy; their connections are blackholed mid-run while a steady load
 * of notifications is submitted. Expectations:
 * <ul>
 *   <li>while a dependency is cut the API fails closed with 503 (never 202, never a 500);</li>
 *   <li>the process stays alive (liveness UP);</li>
 *   <li>after the cut ends ingestion recovers by itself (no restart);</li>
 *   <li>every notification that was answered 202 is eventually delivered (none lost).</li>
 * </ul>
 * Runs in its own CI job (tag "chaos"): mvn verify -Dit.groups=chaos -Dit.excludedGroups=
 */
@Tag("chaos")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "notification.retry.initial-delay=200ms",
        "notification.retry.max-retries=2",
        "notification.retry.jitter=0",
        "notification.circuit-breaker.enabled=false",
        "notification.security.bootstrap-keys.chaos=ntf_bootstrap_key_for_chaos_tenant",
        "notification.rate-limit.tenants.chaos.capacity=100000",
        "notification.rate-limit.tenants.chaos.refill-per-second=100000",
        "notification.quota.tenants.chaos=1000000",
        // fail fast when a dependency is blackholed
        "spring.data.redis.timeout=1s",
        "spring.data.redis.connect-timeout=1s",
        "spring.kafka.producer.properties.max.block.ms=3000",
        "spring.kafka.producer.properties.request.timeout.ms=2000",
        "spring.kafka.producer.properties.delivery.timeout.ms=5000",
        "spring.kafka.producer.properties.reconnect.backoff.max.ms=1000",
        "spring.kafka.consumer.properties.reconnect.backoff.max.ms=1000",
        "spring.kafka.consumer.properties.session.timeout.ms=10000",
        "spring.kafka.consumer.properties.heartbeat.interval.ms=3000"
})
class ChaosIT {

    static final String API_KEY = "ntf_bootstrap_key_for_chaos_tenant";
    static final ObjectMapper JSON = new ObjectMapper();

    static final Network NET = Network.newNetwork();
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withNetwork(NET).withNetworkAliases("redis").withExposedPorts(6379);
    static final ToxiproxyContainer TOXI = new ToxiproxyContainer(DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.5.0"))
            .withNetwork(NET);
    static GenericContainer<?> kafka;
    static ToxiproxyContainer.ContainerProxy redisProxy;
    static ToxiproxyContainer.ContainerProxy kafkaProxy;

    static {
        if (org.testcontainers.DockerClientFactory.instance().isDockerAvailable()) {
            POSTGRES.start();
            REDIS.start();
            TOXI.start();
            redisProxy = TOXI.getProxy("redis", 6379);
            kafkaProxy = TOXI.getProxy("kafka", 9092); // upstream is resolved lazily, by connection
            // Kafka must advertise the *proxy* address so that clients keep going through Toxiproxy after bootstrap.
            String advertised = "PLAINTEXT://" + kafkaProxy.getContainerIpAddress() + ":" + kafkaProxy.getProxyPort();
            kafka = new GenericContainer<>(DockerImageName.parse("apache/kafka:3.8.0"))
                    .withNetwork(NET).withNetworkAliases("kafka")
                    .withEnv("KAFKA_NODE_ID", "1")
                    .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
                    .withEnv("KAFKA_LISTENERS", "PLAINTEXT://:9092,CONTROLLER://:9093")
                    .withEnv("KAFKA_ADVERTISED_LISTENERS", advertised)
                    .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT")
                    .withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "PLAINTEXT")
                    .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
                    .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
                    .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
                    .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
                    .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
                    .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
                    .waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1).withStartupTimeout(Duration.ofMinutes(2)));
            kafka.start();
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        r.add("spring.datasource.username", POSTGRES::getUsername);
        r.add("spring.datasource.password", POSTGRES::getPassword);
        r.add("spring.data.redis.host", () -> redisProxy.getContainerIpAddress());
        r.add("spring.data.redis.port", () -> redisProxy.getProxyPort());
        r.add("spring.kafka.bootstrap-servers", () -> kafkaProxy.getContainerIpAddress() + ":" + kafkaProxy.getProxyPort());
    }

    @AfterAll
    static void cleanup() {
        // never leave a toxic behind for other classes
        try { redisProxy.setConnectionCut(false); } catch (Exception ignored) { }
        try { kafkaProxy.setConnectionCut(false); } catch (Exception ignored) { }
    }

    @LocalServerPort int port;

    record Result(long startMillis, int status, String id) { }

    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    final List<Result> results = Collections.synchronizedList(new ArrayList<>());
    final AtomicBoolean running = new AtomicBoolean(true);

    private HttpRequest.Builder req(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(30)).header("X-API-Key", API_KEY);
    }

    private Result submit() {
        long start = System.currentTimeMillis();
        try {
            String body = "{\"channels\":[\"EMAIL\"],\"recipients\":{\"email\":\"ok@example.com\"},\"subject\":\"Hi\",\"body\":\"chaos\"}";
            HttpResponse<String> resp = http.send(req("/api/v1/notifications")
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
            String id = resp.statusCode() == 202 ? JSON.readTree(resp.body()).get("id").asText() : null;
            return new Result(start, resp.statusCode(), id);
        } catch (Exception e) {
            return new Result(start, -1, null); // client-side failure counts as unexpected
        }
    }

    private int liveness() throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health/liveness"))
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private List<Result> startedBetween(long fromMillis, long toMillis) {
        synchronized (results) {
            return results.stream().filter(r -> r.startMillis() >= fromMillis && r.startMillis() <= toMillis).toList();
        }
    }

    @Test
    void degradesGracefullyWhenRedisThenKafkaAreCutAndRecovers() throws Exception {
        Thread load = new Thread(() -> {
            while (running.get()) {
                results.add(submit());
                sleep(100);
            }
        }, "chaos-load");
        load.start();
        try {
            // 1. healthy baseline
            sleep(5_000);
            assertThat(startedBetween(0, Long.MAX_VALUE)).isNotEmpty().allMatch(r -> r.status() == 202);

            // 2. cut Redis
            long redisCut = System.currentTimeMillis();
            redisProxy.setConnectionCut(true);
            sleep(6_000);
            assertThat(liveness()).as("liveness while Redis is cut").isEqualTo(200);
            sleep(1_000);
            long redisRestore = System.currentTimeMillis();
            redisProxy.setConnectionCut(false);
            List<Result> duringRedisCut = startedBetween(redisCut + 1_500, redisRestore - 2_000);
            assertThat(duringRedisCut).as("requests during Redis cut").isNotEmpty()
                    .allSatisfy(r -> assertThat(r.status()).as("status of request started at +%dms", r.startMillis() - redisCut).isEqualTo(503));

            // 3. Redis recovery without restart
            awaitAccepted(Duration.ofSeconds(60));
            sleep(3_000);

            // 4. cut Kafka
            long kafkaCut = System.currentTimeMillis();
            kafkaProxy.setConnectionCut(true);
            sleep(14_000);
            assertThat(liveness()).as("liveness while Kafka is cut").isEqualTo(200);
            long kafkaRestore = System.currentTimeMillis();
            kafkaProxy.setConnectionCut(false);
            List<Result> duringKafkaCut = startedBetween(kafkaCut + 1_000, kafkaRestore - 7_000);
            assertThat(duringKafkaCut).as("requests during Kafka cut").isNotEmpty()
                    .allSatisfy(r -> assertThat(r.status()).as("status of request started at +%dms", r.startMillis() - kafkaCut).isEqualTo(503));

            // 5. Kafka recovery without restart
            awaitAccepted(Duration.ofSeconds(90));
            sleep(3_000);
        } finally {
            running.set(false);
            load.join(60_000);
        }

        // Only 202 and 503 are legitimate answers anywhere in the run (no 500s, no client-side failures).
        assertThat(results).extracting(Result::status).containsOnly(202, 503);

        // 6. no accepted notification is lost
        List<String> accepted = results.stream().map(Result::id).filter(java.util.Objects::nonNull).toList();
        assertThat(accepted).isNotEmpty();
        await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofSeconds(2)).untilAsserted(() -> {
            List<String> notSent = accepted.stream().filter(id -> !"SENT".equals(statusOf(id))).toList();
            assertThat(notSent).as("accepted but not yet SENT (of %d accepted)", accepted.size()).isEmpty();
        });
    }

    private void awaitAccepted(Duration atMost) {
        await().atMost(atMost).pollInterval(Duration.ofMillis(500)).until(() -> {
            Result r = submit();
            results.add(r);
            return r.status() == 202;
        });
    }

    private String statusOf(String id) {
        try {
            HttpResponse<String> r = http.send(req("/api/v1/notifications/" + id).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200) return "HTTP_" + r.statusCode();
            JsonNode arr = JSON.readTree(r.body());
            return arr.isEmpty() ? "NONE" : arr.get(0).get("status").asText();
        } catch (Exception e) {
            return "ERR";
        }
    }
}
