package com.example.notification.it;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

import io.restassured.http.ContentType;
import io.restassured.response.Response;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class NotificationFlowIT extends AbstractIntegrationTest {

    private Response send(String tenant, String idem, String recipient, String priority) {
        var req = as(tenant).contentType(ContentType.JSON);
        if (idem != null) req = req.header("Idempotency-Key", idem);
        return req.body("""
                {"channels":["EMAIL"],"priority":"%s","recipients":{"email":"%s"},"subject":"Hi","body":"Hello {{n|there}}"}"""
                .formatted(priority, recipient)).post("/api/v1/notifications");
    }

    private String statusOf(String tenant, String id) {
        return as(tenant).get("/api/v1/notifications/" + id)
                .then().statusCode(200).extract().path("[0].status");
    }

    @Test
    void deliversAcrossAllPriorities() {
        String tenant = uniq("t");
        for (String p : new String[] {"HIGH", "NORMAL", "LOW"}) {
            String id = send(tenant, null, "ok@example.com", p).then().statusCode(202).extract().path("id");
            await().atMost(Duration.ofSeconds(30)).until(() -> {
                var l = as(tenant).get("/api/v1/notifications/" + id).path("[0].status");
                return "SENT".equals(l);
            });
        }
    }

    @Test
    void fansOutToMultipleChannels() {
        String tenant = uniq("t");
        String id = as(tenant).contentType(ContentType.JSON).body("""
                {"channels":["EMAIL","SMS","PUSH"],"recipients":{"email":"a@b.com","phone":"+1555","deviceToken":"tok"},"body":"x"}""")
                .post("/api/v1/notifications").then().statusCode(202).extract().path("id");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                as(tenant).get("/api/v1/notifications/" + id)
                        .then().body("status", everyItem(equalTo("SENT"))).body("size()", equalTo(3)));
    }

    @Test
    void dedupesOnIdempotencyKey() {
        String tenant = uniq("t");
        String key = uniq("k");
        String first = send(tenant, key, "ok@example.com", "NORMAL").then().statusCode(202).extract().path("id");
        send(tenant, key, "ok@example.com", "NORMAL").then().statusCode(200)
                .body("id", equalTo(first)).body("duplicate", equalTo(true));
        // same key under another tenant is independent
        send(uniq("t"), key, "ok@example.com", "NORMAL").then().statusCode(202);
    }

    @Test
    void rateLimitsPerTenant() {
        for (int i = 0; i < 3; i++) send("ratelimited", null, "ok@example.com", "LOW").then().statusCode(202);
        send("ratelimited", null, "ok@example.com", "LOW").then().statusCode(429).header("Retry-After", notNullValue());
        // other tenants unaffected
        send(uniq("t"), null, "ok@example.com", "LOW").then().statusCode(202);
    }

    @Test
    void enforcesDailyQuota() {
        send("quotatenant", null, "ok@example.com", "NORMAL").then().statusCode(202);
        send("quotatenant", null, "ok@example.com", "NORMAL").then().statusCode(202);
        send("quotatenant", null, "ok@example.com", "NORMAL").then().statusCode(429).body("detail", containsString("quota"));
    }

    @Test
    void flakyRecipientIsRetriedThenSent() {
        String tenant = uniq("t");
        String id = send(tenant, null, "flaky@example.com", "NORMAL").then().statusCode(202).extract().path("id");
        await().atMost(Duration.ofSeconds(40)).until(() -> "SENT".equals(
                as(tenant).get("/api/v1/notifications/" + id).path("[0].status")));
        as(tenant).get("/api/v1/notifications/" + id).then().body("[0].attempts", equalTo(2));
    }

    @Test
    void permanentlyFailingRecipientEndsInDlq() {
        String tenant = uniq("t");
        String id = send(tenant, null, "fail@example.com", "NORMAL").then().statusCode(202).extract().path("id");
        await().atMost(Duration.ofSeconds(40)).untilAsserted(() ->
                as(tenant).get("/api/v1/notifications/" + id).then()
                        .body("[0].status", equalTo("DEAD")).body("[0].attempts", equalTo(3))
                        .body("[0].lastError", containsString("Stub provider")));
    }
}
