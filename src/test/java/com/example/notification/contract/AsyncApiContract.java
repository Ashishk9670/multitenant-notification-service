package com.example.notification.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SchemaValidatorsConfig;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.File;
import java.io.IOException;
import java.util.Set;

/** Loads asyncapi.yaml from the repo root and validates JSON payloads against its component schemas. */
final class AsyncApiContract {

    private final ObjectNode root;
    private final JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
    private final SchemaValidatorsConfig config = new SchemaValidatorsConfig();

    AsyncApiContract() {
        try {
            root = (ObjectNode) new ObjectMapper(new YAMLFactory()).readTree(new File("asyncapi.yaml"));
        } catch (IOException e) {
            throw new IllegalStateException("cannot read asyncapi.yaml", e);
        }
        config.setFormatAssertionsEnabled(true);
    }

    JsonNode root() {
        return root;
    }

    /** @param pointer JSON pointer into the AsyncAPI document, e.g. /components/schemas/DeliveryEvent */
    Set<ValidationMessage> validate(String pointer, JsonNode instance) {
        // The document itself is the schema resource so that "#/components/schemas/X" refs resolve.
        ObjectNode wrapper = root.deepCopy();
        wrapper.put("$ref", "#" + pointer);
        JsonSchema schema = factory.getSchema(wrapper, config);
        return schema.validate(instance);
    }
}
