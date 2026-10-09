package com.kaizten.sheriff.infrastructure.mcp.protocol;

/**
 * What running one tool answers with: the text a model reads, and whether it
 * counts as a failure.
 *
 * <p>A failure is carried here rather than thrown past the protocol layer as
 * a JSON-RPC error, because a JSON-RPC error means the call itself was
 * malformed and a model can do nothing with it. "That component does not
 * exist" is information the model can act on, so it belongs in the text.
 *
 * @param text what the tool answers with
 * @param isError whether this is a failure
 */
public record ToolOutcome(String text, boolean isError) {

    private static final String ERROR_TEXT_NOT_DEFINED = "Text is not defined";

    /**
     * Checks the values it is given instead of quietly correcting them.
     *
     * @throws IllegalArgumentException when the text is not defined
     */
    public ToolOutcome {
        if (text == null) {
            throw new IllegalArgumentException(ERROR_TEXT_NOT_DEFINED);
        }
    }

    /**
     * A tool call that answered normally.
     *
     * @param text what it answered
     * @return that outcome
     */
    public static ToolOutcome ok(String text) {
        return new ToolOutcome(text, false);
    }

    /**
     * A tool call that failed.
     *
     * @param text why it failed
     * @return that outcome
     */
    public static ToolOutcome failure(String text) {
        return new ToolOutcome(text, true);
    }
}
