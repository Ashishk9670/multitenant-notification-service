package com.example.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Every Prometheus alert must have severity, summary and a runbook that exists under docs/runbooks. */
class AlertRunbooksTest {

    @Test
    void everyAlertHasARunbookFile() throws Exception {
        JsonNode doc = new ObjectMapper(new YAMLFactory()).readTree(new File("monitoring/alerts/notification-slo.yml"));
        List<String> alerts = new ArrayList<>();
        for (JsonNode group : doc.get("groups")) {
            for (JsonNode rule : group.get("rules")) {
                if (!rule.has("alert")) continue;
                String name = rule.get("alert").asText();
                alerts.add(name);
                assertThat(rule.at("/labels/severity").asText()).as(name + " severity").isIn("critical", "warning");
                assertThat(rule.at("/annotations/summary").asText()).as(name + " summary").isNotBlank();
                String url = rule.at("/annotations/runbook_url").asText();
                assertThat(url).as(name + " runbook_url").endsWith("/docs/runbooks/" + name + ".md");
                assertThat(Files.exists(Path.of("docs/runbooks/" + name + ".md"))).as(name + " runbook file").isTrue();
            }
        }
        assertThat(alerts).hasSizeGreaterThanOrEqualTo(6);
    }
}
