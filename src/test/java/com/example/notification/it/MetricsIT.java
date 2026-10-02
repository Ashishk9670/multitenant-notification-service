package com.example.notification.it;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

import io.restassured.http.ContentType;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MetricsIT extends AbstractIntegrationTest {

    private String send(String tenant, String recipient) {
        return as(tenant).contentType(ContentType.JSON).body("""
                {"channels":["EMAIL"],"recipients":{"email":"%s"},"body":"x"}""".formatted(recipient))
                .post("/api/v1/notifications").then().statusCode(202).extract().path("id");
    }

    private String metrics() {
        return given().get("/actuator/prometheus").then().statusCode(200).extract().asString();
    }

    @Test
    void perTenantSentCounterIsExposed() {
        String tenant = uniq("m");
        send(tenant, "ok@example.com");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                org.assertj.core.api.Assertions.assertThat(metrics())
                        .contains("notifications_sent_total{application=\"multitenant-notification-service\",channel=\"EMAIL\",tenant=\"" + tenant + "\"} 1.0"));
        org.assertj.core.api.Assertions.assertThat(metrics())
                .contains("notifications_accepted_total{application=\"multitenant-notification-service\",priority=\"NORMAL\",tenant=\"" + tenant + "\"}");
    }

    @Test
    void failedRetriedAndDlqCountersAreExposed() {
        String tenant = uniq("m");
        send(tenant, "fail@example.com");
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() -> {
            String m = metrics();
            org.assertj.core.api.Assertions.assertThat(m)
                    .contains("notifications_dlq_total{application=\"multitenant-notification-service\",channel=\"EMAIL\",tenant=\"" + tenant + "\"} 1.0")
                    .contains("notifications_retried_total{application=\"multitenant-notification-service\",channel=\"EMAIL\",tenant=\"" + tenant + "\"} 2.0")
                    .contains("notifications_failed_total{application=\"multitenant-notification-service\",channel=\"EMAIL\",tenant=\"" + tenant + "\"} 3.0");
        });
    }

    @Test
    void rateLimitRejectionsAreCounted() {
        for (int i = 0; i < 3; i++) as("ratelimited").contentType(ContentType.JSON).body("""
                {"channels":["EMAIL"],"recipients":{"email":"ok@example.com"},"body":"x"}""").post("/api/v1/notifications");
        org.assertj.core.api.Assertions.assertThat(metrics()).contains("notifications_rejected_total");
    }

    @Test
    void healthProbesArePublic() {
        given().get("/actuator/health/liveness").then().statusCode(200).body("status", equalTo("UP"));
        given().get("/actuator/health/readiness").then().statusCode(200).body("status", equalTo("UP"));
    }
}
