package com.kaizten.sheriff.infrastructure.mcp.tool;

import java.util.Map;

/**
 * What runs when a tool is called. A failure is signalled by throwing rather
 * than by an error return, since the dispatcher that calls this is the one
 * place that decides how a failure becomes text.
 */
@FunctionalInterface
public interface ToolHandler {

    /**
     * Runs the tool.
     *
     * @param arguments the call's arguments, never {@code null}
     * @return the text to answer with
     */
    String handle(Map<String, Object> arguments);
}
