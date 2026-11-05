package com.example.notification.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.notification.model.Channel;
import com.example.notification.model.DeliveryEvent;
import com.example.notification.model.Priority;
import com.example.notification.service.Backoff;
import com.example.notification.service.DeliveryStatus;
import com.example.notification.webhook.Webhook;
import com.example.notification.webhook.WebhookDispatcher;
import com.example.notification.webhook.WebhookService;
import com.example.notification.webhook.WebhookSigner;
import com.example.notification.webhook.WebhookTransport;
import com.example.notification.webhook.WebhookUrlValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Consumer-driven contract: src/test/resources/contracts/webhook-consumer-contract.json states what a tenant's
 * receiver relies on (headers, signature scheme, body schema). The provider (WebhookDispatcher) is exercised and
 * the captured request is verified by an independent receiver-side implementation of the signature check.
 */
class WebhookConsumerContractTest {

    static final String SECRET = "whsec_contract_test";
    static final ObjectMapper JSON = new ObjectMapper();

    record Call(URI uri, Map<String, String> headers, String body) { }

    final List<Call> calls = new ArrayList<>();
    JsonNode contract;
    WebhookDispatcher dispatcher;

    @BeforeEach
    void setUp() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/contracts/webhook-consumer-contract.json")) {
            contract = JSON.readTree(in).get("request");
        }
        WebhookService service = mock(WebhookService.class);
        when(service.find("acme")).thenReturn(Optional.of(new Webhook("acme", "https://hooks.example.com/r", SECRET, Instant.now())));
        WebhookTransport transport = (uri, headers, body) -> {
            calls.add(new Call(uri, headers, body));
            return 204;
        };
        WebhookUrlValidator validator = new WebhookUrlValidator(h -> new InetAddress[] {InetAddress.getByName("93.184.216.34")});
        Backoff backoff = new Backoff(Duration.ofSeconds(1), 2.0, Duration.ofMinutes(1), 0, () -> 0.5);
        dispatcher = new WebhookDispatcher(service, validator, new WebhookSigner(), transport, backoff, 3, true,
                new SimpleMeterRegistry(), (task, delay) -> task.run());
    }

    private static DeliveryEvent event(Channel ch, int attempt) {
        return new DeliveryEvent("5d1b8c0e-1a2b-4c3d-8e9f-001122334455", "acme", ch, Priority.NORMAL, "a@b.com", "s", "b",
                attempt, Instant.now(), null);
    }

    /** Receiver-side verification, deliberately not using WebhookSigner. */
    private boolean receiverAccepts(Call c) throws Exception {
        long ts = Long.parseLong(c.headers().get("X-Webhook-Timestamp"));
        long skew = contract.at("/signature/maxClockSkewSeconds").asLong();
        if (Math.abs(Instant.now().getEpochSecond() - ts) > skew) return false;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String expected = "sha256=" + HexFormat.of().formatHex(mac.doFinal((ts + "." + c.body()).getBytes(StandardCharsets.UTF_8)));
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                c.headers().get("X-Webhook-Signature").getBytes(StandardCharsets.UTF_8));
    }

    private void assertHonoursContract(Call c) throws Exception {
        assertThat(c.headers().get("Content-Type")).isEqualTo(contract.get("contentType").asText());
        contract.get("headers").fields().forEachRemaining(h ->
                assertThat(c.headers()).as("header %s", h.getKey()).containsKey(h.getKey())
                        .extractingByKey(h.getKey(), org.assertj.core.api.InstanceOfAssertFactories.STRING)
                        .matches(h.getValue().asText()));

        JsonSchemaFactory f = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        SchemaValidatorsConfig cfg = new SchemaValidatorsConfig();
        cfg.setFormatAssertionsEnabled(true);
        JsonSchema schema = f.getSchema(contract.get("bodySchema"), cfg);
        assertThat(schema.validate(JSON.readTree(c.body()))).as("body %s", c.body()).isEmpty();

        assertThat(receiverAccepts(c)).as("receiver-side signature check").isTrue();
    }

    @Test
    void sentReceiptHonoursContract() throws Exception {
        dispatcher.publish(event(Channel.EMAIL, 0), DeliveryStatus.SENT, null);
        assertThat(calls).hasSize(1);
        assertHonoursContract(calls.get(0));
        JsonNode body = JSON.readTree(calls.get(0).body());
        assertThat(body.get("status").asText()).isEqualTo("SENT");
        assertThat(body.get("error").isNull()).isTrue();
        assertThat(calls.get(0).headers().get("X-Webhook-Id")).isEqualTo("5d1b8c0e-1a2b-4c3d-8e9f-001122334455:EMAIL");
    }

    @Test
    void deadReceiptWithHostileErrorTextStaysValidJsonAndSigned() throws Exception {
        String error = "IllegalStateException: \"quoted\" \\ back\nnew\tline \u0001 ctrl é中";
        dispatcher.publish(event(Channel.SMS, 3), DeliveryStatus.DEAD, error);
        assertThat(calls).hasSize(1);
        assertHonoursContract(calls.get(0));
        JsonNode body = JSON.readTree(calls.get(0).body());
        assertThat(body.get("status").asText()).isEqualTo("DEAD");
        assertThat(body.get("attempts").asInt()).isEqualTo(3);
        assertThat(body.get("error").asText()).isEqualTo(error);
    }

    @Test
    void nonTerminalStatusesProduceNoReceipt() {
        dispatcher.publish(event(Channel.PUSH, 1), DeliveryStatus.RETRYING, "x");
        assertThat(calls).isEmpty();
    }

    @Test
    void tamperedBodyOrStaleTimestampIsRejectedByReceiver() throws Exception {
        dispatcher.publish(event(Channel.EMAIL, 0), DeliveryStatus.SENT, null);
        Call c = calls.get(0);
        assertThat(receiverAccepts(new Call(c.uri(), c.headers(), c.body().replace("SENT", "DEAD")))).isFalse();
        var stale = new java.util.HashMap<>(c.headers());
        stale.put("X-Webhook-Timestamp", Long.toString(Instant.now().getEpochSecond() - 3600));
        assertThat(receiverAccepts(new Call(c.uri(), stale, c.body()))).isFalse();
    }
}
