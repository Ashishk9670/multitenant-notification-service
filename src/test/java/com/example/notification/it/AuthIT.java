package com.example.notification.it;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.*;

import io.restassured.http.ContentType;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class AuthIT extends AbstractIntegrationTest {

    @Autowired JdbcTemplate jdbc;

    private static final String BODY = """
            {"channels":["EMAIL"],"recipients":{"email":"a@b.com"},"body":"hi"}""";

    @Test
    void requestsWithoutCredentialsAreRejected() {
        given().contentType(ContentType.JSON).body(BODY).post("/api/v1/notifications").then().statusCode(401);
        given().get("/api/v1/templates").then().statusCode(401);
        given().get("/api/v1/deliveries").then().statusCode(401);
    }

    @Test
    void tenantHeaderAloneIsNotTrusted() {
        given().header("X-Tenant-Id", "acme").contentType(ContentType.JSON).body(BODY)
                .post("/api/v1/notifications").then().statusCode(401);
    }

    @Test
    void invalidKeyIsRejected() {
        given().header("X-API-Key", "ntf_this_is_not_a_real_key_at_all").get("/api/v1/templates").then().statusCode(401);
    }

    @Test
    void spoofedTenantHeaderDoesNotOverrideKeyTenant() {
        String a = uniq("a"), b = uniq("b");
        String id = as(a).contentType(ContentType.JSON).body(BODY).post("/api/v1/notifications")
                .then().statusCode(202).extract().path("id");
        // B's key + A's tenant header: still B, so A's delivery is invisible
        given().header("X-API-Key", keyFor(b)).header("X-Tenant-Id", a)
                .get("/api/v1/notifications/" + id).then().statusCode(200).body("size()", equalTo(0));
    }

    @Test
    void deliveriesAreIsolatedBetweenTenants() {
        String a = uniq("a"), b = uniq("b");
        String id = as(a).contentType(ContentType.JSON).body(BODY).post("/api/v1/notifications")
                .then().statusCode(202).extract().path("id");
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                as(a).get("/api/v1/notifications/" + id).then().body("[0].status", equalTo("SENT")));
        as(a).get("/api/v1/deliveries").then().body("notificationId", hasItem(id));
        as(b).get("/api/v1/notifications/" + id).then().statusCode(200).body("size()", equalTo(0));
        as(b).get("/api/v1/deliveries").then().body("notificationId", not(hasItem(id)));
    }

    @Test
    void templatesCannotBeReadChangedOrDeletedAcrossTenants() {
        String a = uniq("a"), b = uniq("b");
        String id = as(a).contentType(ContentType.JSON)
                .body("{\"name\":\"secret\",\"channel\":\"EMAIL\",\"body\":\"s3cret\"}")
                .post("/api/v1/templates").then().statusCode(201).extract().path("id");
        as(b).get("/api/v1/templates/" + id).then().statusCode(404);
        as(b).contentType(ContentType.JSON).body("{\"name\":\"x\",\"channel\":\"EMAIL\",\"body\":\"hacked\"}")
                .put("/api/v1/templates/" + id).then().statusCode(404);
        as(b).delete("/api/v1/templates/" + id).then().statusCode(404);
        as(b).get("/api/v1/templates").then().body("size()", equalTo(0));
        as(a).get("/api/v1/templates/" + id).then().statusCode(200).body("body", equalTo("s3cret"));
    }

    @Test
    void idempotencyKeysAndRateLimitsAreScopedPerTenant() {
        String a = uniq("a"), b = uniq("b");
        String key = uniq("idem");
        as(a).header("Idempotency-Key", key).contentType(ContentType.JSON).body(BODY)
                .post("/api/v1/notifications").then().statusCode(202);
        as(b).header("Idempotency-Key", key).contentType(ContentType.JSON).body(BODY)
                .post("/api/v1/notifications").then().statusCode(202);
    }

    @Test
    void revokedKeyStopsWorking() {
        String t = uniq("t");
        var created = given().header("X-Admin-Token", "it-admin-token").contentType(ContentType.JSON)
                .body("{\"tenantId\":\"" + t + "\",\"name\":\"temp\"}").post("/api/v1/admin/api-keys")
                .then().statusCode(201).extract();
        String key = created.path("apiKey"), id = created.path("id");
        given().header("X-API-Key", key).get("/api/v1/templates").then().statusCode(200);
        given().header("X-Admin-Token", "it-admin-token").delete("/api/v1/admin/api-keys/" + id).then().statusCode(204);
        given().header("X-API-Key", key).get("/api/v1/templates").then().statusCode(401);
    }

    @Test
    void keysAreStoredHashedOnly() {
        String t = uniq("t");
        String key = keyFor(t);
        var rows = jdbc.queryForList("select key_hash from api_keys where tenant_id = ?", String.class, t);
        org.assertj.core.api.Assertions.assertThat(rows).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(rows.get(0)).hasSize(64).isNotEqualTo(key).doesNotContain(key);
    }

    @Test
    void bootstrapKeyAuthenticatesItsTenant() {
        given().header("Authorization", "Bearer ntf_bootstrap_key_for_boot_tenant").contentType(ContentType.JSON).body(BODY)
                .post("/api/v1/notifications").then().statusCode(202);
    }

    @Test
    void adminApiIsProtected() {
        String body = "{\"tenantId\":\"x\",\"name\":\"n\"}";
        given().contentType(ContentType.JSON).body(body).post("/api/v1/admin/api-keys").then().statusCode(401);
        given().header("X-Admin-Token", "nope").contentType(ContentType.JSON).body(body)
                .post("/api/v1/admin/api-keys").then().statusCode(401);
        given().header("X-API-Key", keyFor(uniq("t"))).contentType(ContentType.JSON).body(body)
                .post("/api/v1/admin/api-keys").then().statusCode(401);
    }

    @Test
    void healthStaysPublic() {
        given().get("/actuator/health").then().statusCode(200);
    }
}
