package com.example.notification.it;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

import io.restassured.http.ContentType;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TemplateApiIT extends AbstractIntegrationTest {

    private String create(String tenant, String name) {
        return as(tenant).contentType(ContentType.JSON).body("""
                {"name":"%s","channel":"EMAIL","subject":"Welcome {{name}}","body":"Hi {{name}}, code {{code|0000}}"}""".formatted(name))
                .post("/api/v1/templates").then().statusCode(201).extract().path("id");
    }

    @Test
    void crudLifecycle() {
        String tenant = uniq("t");
        String id = create(tenant, "welcome");
        as(tenant).get("/api/v1/templates").then().body("size()", equalTo(1));
        as(tenant).contentType(ContentType.JSON).body("""
                {"name":"welcome","channel":"EMAIL","subject":"S","body":"Updated {{name}}"}""")
                .put("/api/v1/templates/" + id).then().statusCode(200).body("body", equalTo("Updated {{name}}"));
        as(tenant).delete("/api/v1/templates/" + id).then().statusCode(204);
        as(tenant).get("/api/v1/templates/" + id).then().statusCode(404);
    }

    @Test
    void duplicateNameAndChannelConflicts() {
        String tenant = uniq("t");
        create(tenant, "dup");
        as(tenant).contentType(ContentType.JSON)
                .body("{\"name\":\"dup\",\"channel\":\"EMAIL\",\"body\":\"x\"}")
                .post("/api/v1/templates").then().statusCode(409);
    }

    @Test
    void templatesAreTenantIsolated() {
        String a = uniq("a"), b = uniq("b");
        String id = create(a, "secret");
        as(b).get("/api/v1/templates/" + id).then().statusCode(404);
        as(b).get("/api/v1/templates").then().body("size()", equalTo(0));
    }

    @Test
    void previewRendersAndReports422OnMissingVars() {
        String tenant = uniq("t");
        String id = create(tenant, "p");
        as(tenant).contentType(ContentType.JSON).body("{\"variables\":{\"name\":\"Ann\"}}")
                .post("/api/v1/templates/" + id + "/preview").then().statusCode(200)
                .body("subject", equalTo("Welcome Ann")).body("body", equalTo("Hi Ann, code 0000"));
        as(tenant).contentType(ContentType.JSON).body("{\"variables\":{}}")
                .post("/api/v1/templates/" + id + "/preview").then().statusCode(422);
    }

    @Test
    void sendWithStoredTemplateIsDelivered() {
        String tenant = uniq("t");
        create(tenant, "welcome");
        String nid = as(tenant).contentType(ContentType.JSON).body("""
                {"channels":["EMAIL"],"recipients":{"email":"a@b.com"},"templateName":"welcome","variables":{"name":"Ann"}}""")
                .post("/api/v1/notifications").then().statusCode(202).extract().path("id");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                as(tenant).get("/api/v1/notifications/" + nid).then().body("[0].status", equalTo("SENT")));
        as(tenant).contentType(ContentType.JSON).body("""
                {"channels":["EMAIL"],"recipients":{"email":"a@b.com"},"templateName":"nope"}""")
                .post("/api/v1/notifications").then().statusCode(404);
    }
}
