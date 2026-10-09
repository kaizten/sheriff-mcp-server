package com.kaizten.sheriff.infrastructure.mcp.tool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The JSON Schemas the tools declare their arguments with. Every argument of
 * every tool is optional: a string, a flag or a whole number.
 *
 * <p>The properties keep the order they are given in. They were built from
 * {@code Map.of}, whose order changes from one JVM to the next, so the same
 * server listed {@code profile} before {@code component} in one session and
 * after it in the next.
 */
final class Schemas {

    private static final String TYPE_FIELD = "type";
    private static final String OBJECT_TYPE = "object";
    private static final String STRING_TYPE = "string";
    private static final String BOOLEAN_TYPE = "boolean";
    private static final String INTEGER_TYPE = "integer";
    private static final String MINIMUM_FIELD = "minimum";
    private static final int SMALLEST_WHOLE_NUMBER = 1;
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
     * A JSON Schema object with the given properties, in the order given,
     * none required -- every argument of every tool here has a configured or
     * computed default.
     *
     * @param properties each property's name and schema, in order
     * @return the schema
     */
    static Map<String, Object> objectSchema(List<Map.Entry<String, Map<String, Object>>> properties) {
        Map<String, Object> ordered = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> property : properties) {
            ordered.put(property.getKey(), property.getValue());
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(TYPE_FIELD, OBJECT_TYPE);
        schema.put(PROPERTIES_FIELD, ordered);
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
        return typed(STRING_TYPE, description);
    }

    /**
     * A JSON Schema boolean property with a description.
     *
     * @param description what the property is for
     * @return the schema
     */
    static Map<String, Object> booleanProperty(String description) {
        return typed(BOOLEAN_TYPE, description);
    }

    /**
     * A JSON Schema property for a whole number of at least one.
     *
     * @param description what the property is for
     * @return the schema
     */
    static Map<String, Object> positiveIntegerProperty(String description) {
        Map<String, Object> schema = typed(INTEGER_TYPE, description);
        schema.put(MINIMUM_FIELD, SMALLEST_WHOLE_NUMBER);
        return schema;
    }

    /**
     * A property of one JSON type, with a description.
     *
     * @param type the JSON Schema type
     * @param description what the property is for
     * @return the schema
     */
    private static Map<String, Object> typed(String type, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put(TYPE_FIELD, type);
        schema.put(DESCRIPTION_FIELD, description);
        return schema;
    }
}
