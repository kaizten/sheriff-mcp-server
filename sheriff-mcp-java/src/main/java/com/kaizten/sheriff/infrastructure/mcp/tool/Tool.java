package com.kaizten.sheriff.infrastructure.mcp.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One tool this server exposes: its name, what a client reads before calling
 * it, the shape of its arguments, and what actually answers a call.
 *
 * @param name the tool's name, as a client sees it and calls it by
 * @param description what the tool does, shown to the model deciding whether
 *     to call it
 * @param inputSchema the JSON Schema its arguments must satisfy
 * @param annotations its title, and what it does to its surroundings
 * @param handler what runs when this tool is called
 */
public record Tool(String name, String description, Map<String, Object> inputSchema, ToolAnnotations annotations,
        ToolHandler handler) {

    private static final String NAME_FIELD = "name";
    private static final String DESCRIPTION_FIELD = "description";
    private static final String INPUT_SCHEMA_FIELD = "inputSchema";
    private static final String ANNOTATIONS_FIELD = "annotations";

    /**
     * This tool's definition, the shape {@code tools/list} answers with.
     *
     * @return the name, description, input schema and annotations, without
     *     the handler
     */
    public Map<String, Object> definition() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(NAME_FIELD, name);
        body.put(DESCRIPTION_FIELD, description);
        body.put(INPUT_SCHEMA_FIELD, inputSchema);
        body.put(ANNOTATIONS_FIELD, annotations.asMap());
        return body;
    }
}
