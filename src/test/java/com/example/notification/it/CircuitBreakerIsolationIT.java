package com.example.notification.it;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

/**
 * Real Kafka/Redis/Postgres with the circuit breaker ENABLED (the other ITs disable it). One tenant sending only to
 * invalid recipients must not make the shared per-channel breaker reject healthy tenants' deliveries.
 */
@TestPropertySource(properties = "notification.circuit-breaker.enabled=true")
class CircuitBreakerIsolationIT extends AbstractIntegrationTest {

    private Response send(String tenant, String recipient) {
        return as(tenant).contentType(ContentType.JSON).body("""
                {"channels":["EMAIL"],"priority":"NORMAL","recipients":{"email":"%s"},"subject":"Hi","body":"Hello"}"""
                .formatted(recipient)).post("/api/v1/notifications");
    }

    @Test
    void aNoisyTenantsBadRecipientsDoNotMakeAnotherTenantsDeliveriesFail() {
        String noisy = uniq("noisy"), quiet = uniq("quiet");
        // Well beyond the breaker's minimum-calls (5) and failure-rate (50%) thresholds, counting every retry attempt
        for (int i = 0; i < 12; i++) {
            send(noisy, "fail-" + i + "@example.com").then().statusCode(202);
        }
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                as(noisy).get("/api/v1/deliveries?limit=50")
                        .then().statusCode(200).body("size()", equalTo(12)).body("status", everyItem(equalTo("DEAD"))));

        // Same channel, different tenant: must still be delivered, not rejected by an open breaker
        String id = send(quiet, "ann@example.com").then().statusCode(202).extract().path("id");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                as(quiet).get("/api/v1/notifications/" + id).then().statusCode(200).body("status", everyItem(equalTo("SENT"))));
    }
}
