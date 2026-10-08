package com.kaizten.sheriff.infrastructure.mcp.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a tool does to its surroundings, as MCP's tool annotations say it: a
 * title for people, and hints a client uses to decide what to approve without
 * asking. They are hints, not guarantees, and a client that predates them
 * ignores the field.
 *
 * <p>The protocol's defaults assume the worst (it writes, destroys, and
 * reaches the outside world), so every hint is written out rather than left
 * to them. Destructive and idempotent are meaningful only for a tool that
 * writes, and are left out of a read-only one's.
 *
 * @param title the name a client shows people instead of the tool's own
 * @param readOnly whether it changes nothing it was pointed at
 * @param destructive whether what it writes can overwrite work, rather than
 *     only add to it
 * @param idempotent whether calling it again with the same arguments does
 *     nothing more
 * @param openWorld whether it reaches beyond this machine, such as a model
 */
public record ToolAnnotations(
        String title, boolean readOnly, boolean destructive, boolean idempotent, boolean openWorld) {

    private static final String TITLE_FIELD = "title";
    private static final String READ_ONLY_FIELD = "readOnlyHint";
    private static final String DESTRUCTIVE_FIELD = "destructiveHint";
    private static final String IDEMPOTENT_FIELD = "idempotentHint";
    private static final String OPEN_WORLD_FIELD = "openWorldHint";

    /**
     * A tool that only reads and runs on this machine.
     *
     * @param title the name a client shows people
     * @return its annotations
     */
    static ToolAnnotations readingOnly(String title) {
        return new ToolAnnotations(title, true, false, true, false);
    }

    /**
     * A tool that edits files in place, on whatever branch is checked out:
     * what it writes can overwrite work that was not committed.
     *
     * @param title the name a client shows people
     * @return its annotations
     */
    static ToolAnnotations rewritingInPlace(String title) {
        return new ToolAnnotations(title, false, true, false, false);
    }

    /**
     * A tool that asks a model for the edits and commits them on a branch of
     * its own, having refused to start over uncommitted work: it adds to the
     * history rather than overwriting it, and it reaches whatever the model
     * runs on.
     *
     * @param title the name a client shows people
     * @return its annotations
     */
    static ToolAnnotations committingThroughAModel(String title) {
        return new ToolAnnotations(title, false, false, false, true);
    }

    /**
     * The annotations as {@code tools/list} carries them.
     *
     * @return the title and every hint that applies, in a stable order
     */
    Map<String, Object> asMap() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(TITLE_FIELD, title);
        body.put(READ_ONLY_FIELD, readOnly);
        if (!readOnly) {
            body.put(DESTRUCTIVE_FIELD, destructive);
            body.put(IDEMPOTENT_FIELD, idempotent);
        }
        body.put(OPEN_WORLD_FIELD, openWorld);
        return body;
    }
}
