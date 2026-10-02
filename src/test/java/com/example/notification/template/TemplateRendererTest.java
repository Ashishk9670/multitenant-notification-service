package com.example.notification.template;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateRendererTest {

    private final TemplateRenderer r = new TemplateRenderer();

    @Test
    void substitutesVariables() {
        assertThat(r.render("Hi {{name}}, order {{ order.id }} shipped", Map.of("name", "Ann", "order.id", "42")))
                .isEqualTo("Hi Ann, order 42 shipped");
    }

    @Test
    void usesDefaultsWhenMissing() {
        assertThat(r.render("Hello {{name|friend}}!", Map.of())).isEqualTo("Hello friend!");
        assertThat(r.render("Hello {{name|friend}}!", Map.of("name", "Bo"))).isEqualTo("Hello Bo!");
    }

    @Test
    void missingVariablesAreReported() {
        assertThatThrownBy(() -> r.render("{{a}} {{b}} {{a}}", Map.of()))
                .isInstanceOfSatisfying(TemplateRenderer.MissingVariablesException.class,
                        e -> assertThat(e.getMissing()).containsExactly("a", "b"));
    }

    @Test
    void nullTemplateAndNullVariablesAreHandled() {
        assertThat(r.render(null, Map.of())).isNull();
        assertThat(r.render("plain", null)).isEqualTo("plain");
    }

    @Test
    void specialCharactersInValuesAreLiteral() {
        assertThat(r.render("Cost: {{p}}", Map.of("p", "$5 \\ {{x}}"))).isEqualTo("Cost: $5 \\ {{x}}");
    }
}
