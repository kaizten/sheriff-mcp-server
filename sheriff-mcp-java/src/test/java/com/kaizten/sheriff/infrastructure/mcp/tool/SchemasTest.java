package com.kaizten.sheriff.infrastructure.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The schemas the tools declare their arguments with.
 */
final class SchemasTest {

    @Test
    @DisplayName("an object schema keeps its properties in the order given, and requires none")
    void anObjectSchemaKeepsTheOrderGiven() {
        Map<String, Object> schema = Schemas.objectSchema(List.of(
                Map.entry("zeta", Schemas.stringProperty("last letter")),
                Map.entry("alpha", Schemas.stringProperty("first letter")),
                Map.entry("mu", Schemas.stringProperty("middle letter"))));

        assertEquals(List.of("zeta", "alpha", "mu"), List.copyOf(((Map<?, ?>) schema.get("properties")).keySet()));
        assertEquals(List.of(), schema.get("required"));
        assertEquals(false, schema.get("additionalProperties"));
    }

    @Test
    @DisplayName("a flag is a boolean, with its description")
    void aFlagIsABoolean() {
        assertEquals(Map.of("type", "boolean", "description", "run the tests"),
                Schemas.booleanProperty("run the tests"));
    }

    @Test
    @DisplayName("a count is a whole number of at least one, with its description")
    void aCountIsAPositiveWholeNumber() {
        assertEquals(Map.of("type", "integer", "description", "passes", "minimum", 1),
                Schemas.positiveIntegerProperty("passes"));
    }
}
