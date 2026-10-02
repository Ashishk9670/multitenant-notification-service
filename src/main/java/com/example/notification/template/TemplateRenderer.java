package com.example.notification.template;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Renders {{variable}} and {{variable|default}} placeholders. Missing variables with no default
 * raise {@link MissingVariablesException}.
 */
@Component
public class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_.]+)\\s*(?:\\|([^}]*))?}}");

    public String render(String template, Map<String, String> variables) {
        if (template == null) {
            return null;
        }
        Map<String, String> vars = variables == null ? Map.of() : variables;
        Set<String> missing = new LinkedHashSet<>();
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String name = m.group(1);
            String value = vars.get(name);
            if (value == null) {
                value = m.group(2) != null ? m.group(2).trim() : null;
            }
            if (value == null) {
                missing.add(name);
                value = "";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        m.appendTail(sb);
        if (!missing.isEmpty()) {
            throw new MissingVariablesException(missing);
        }
        return sb.toString();
    }

    public static class MissingVariablesException extends RuntimeException {
        private final Set<String> missing;

        public MissingVariablesException(Set<String> missing) {
            super("Missing template variables: " + missing);
            this.missing = missing;
        }

        public Set<String> getMissing() { return missing; }
    }
}
