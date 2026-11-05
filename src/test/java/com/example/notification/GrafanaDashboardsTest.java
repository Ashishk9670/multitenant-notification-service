package com.example.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Guards the provisioned dashboards: valid JSON, unique uids, and the per-tenant one really is tenant-scoped. */
class GrafanaDashboardsTest {

    static final Path DIR = Path.of("observability/grafana/dashboards");
    static final ObjectMapper JSON = new ObjectMapper();

    private static List<Path> files() throws IOException {
        try (Stream<Path> s = Files.list(DIR)) {
            return s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
    }

    @Test
    void allDashboardsAreValidWithUniqueUids() throws IOException {
        Set<String> uids = new HashSet<>();
        for (Path p : files()) {
            JsonNode d = JSON.readTree(Files.readString(p));
            assertThat(d.path("uid").asText()).as(p.toString()).isNotBlank();
            assertThat(uids.add(d.get("uid").asText())).as("duplicate uid in " + p).isTrue();
            assertThat(d.path("panels").size()).as(p.toString()).isGreaterThan(0);
            Set<Integer> ids = new HashSet<>();
            for (JsonNode panel : d.get("panels")) {
                assertThat(ids.add(panel.get("id").asInt())).as("duplicate panel id in " + p).isTrue();
            }
        }
        assertThat(uids).contains("notification-overview", "notification-tenant");
    }

    @Test
    void tenantDashboardIsSingleTenantScoped() throws IOException {
        JsonNode d = JSON.readTree(Files.readString(DIR.resolve("notification-tenant.json")));
        JsonNode var = d.at("/templating/list/0");
        assertThat(var.get("name").asText()).isEqualTo("tenant");
        assertThat(var.get("includeAll").asBoolean()).isFalse();
        assertThat(var.get("multi").asBoolean()).isFalse();
        for (JsonNode panel : d.get("panels")) {
            for (JsonNode t : panel.get("targets")) {
                String expr = t.get("expr").asText();
                if (expr.contains("notifications_")) {
                    assertThat(expr).as(panel.get("title").asText()).contains("tenant=\"$tenant\"");
                }
            }
        }
    }

    @Test
    void dashboardsAreMountedByTheMonitoringProfile() throws IOException {
        String compose = Files.readString(Path.of("docker-compose.yml"));
        assertThat(compose).contains("./observability/grafana/dashboards:/var/lib/grafana/dashboards:ro");
        assertThat(compose).contains("profiles: [\"observability\", \"monitoring\"]");
        assertThat(Files.readString(Path.of("observability/grafana/provisioning/dashboards/dashboards.yml")))
                .contains("path: /var/lib/grafana/dashboards");
    }
}
