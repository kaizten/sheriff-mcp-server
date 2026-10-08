package com.kaizten.sheriff.infrastructure.mcp.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON Schemas the tools declare their arguments with. Every argument of
 * every tool is an optional string, so this is all the schema there is.
 */
final class Schemas {

    private static final String TYPE_FIELD = "type";
    private static final String OBJECT_TYPE = "object";
    private static final String STRING_TYPE = "string";
    private static final String PROPERTIES_FIELD = "properties";
    private static final String REQUIRED_FIELD = "required";
    private static final String ADDITIONAL_PROPERTIES_FIELD = "additionalProperties";
    private static final String DESCRIPTION_FIELD = "description";
    private static final String ERROR_UTILITY_CLASS = "This is a utility class and cannot be instantiated.";

    /**
     * Refuses to be instantiated: every member here is static.
     */
    private Schemas() {
        throw new UnsupportedOperationException(ERROR_UTILITY_CLASS);
    }

    /**
     * A JSON Schema object with the given string properties, none required --
     * every argument of every tool here has a configured or computed default.
     *
     * @param properties each property's name and schema
     * @return the schema
     */
    static Map<String, Object> objectSchema(Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(TYPE_FIELD, OBJECT_TYPE);
        schema.put(PROPERTIES_FIELD, new LinkedHashMap<>(properties));
        schema.put(REQUIRED_FIELD, List.of());
        schema.put(ADDITIONAL_PROPERTIES_FIELD, false);
        return schema;
    }

    /**
     * A JSON Schema string property with a description.
     *
     * @param description what the property is for
     * @return the schema
     */
    static Map<String, Object> stringProperty(String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(TYPE_FIELD, STRING_TYPE);
        schema.put(DESCRIPTION_FIELD, description);
        return schema;
    }
}
