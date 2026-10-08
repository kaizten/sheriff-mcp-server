package com.kaizten.sheriff.domain.valueobject;

/**
 * What a {@link com.kaizten.sheriff.domain.port.CodeFixer} reports back.
 *
 * <p>{@code ok} means the fixer ran, not that the code is now correct: whether
 * anything actually improved is the analyzer's answer, on the next iteration.
 *
 * @param ok whether the invocation itself succeeded
 * @param output whatever the fixer said, for the log and for error messages
 */
public record FixResult(boolean ok, String output) {

    private static final String ERROR_OUTPUT_NOT_DEFINED = "Output is not defined";

    /**
     * Checks the value it is given instead of quietly correcting it.
     *
     * <p>This used to turn a null output into the empty string, which is the
     * kind of helpfulness that hides a bug: a fixer that returned nothing and
     * one whose output was lost on the way here would look identical
     * afterwards. A value object holds a checked value, so an absent one is
     * refused where it appears.
     *
     * @throws IllegalArgumentException when the output is not defined
     */
    public FixResult {
        if (output == null) {
            throw new IllegalArgumentException(ERROR_OUTPUT_NOT_DEFINED);
        }
    }

    /**
     * A fixer invocation that ran.
     *
     * @param output what the fixer said
     * @return the successful result
     */
    public static FixResult succeeded(String output) {
        return new FixResult(true, output);
    }

    /**
     * A fixer invocation that did not run.
     *
     * @param output why it did not run
     * @return the failed result
     */
    public static FixResult failed(String output) {
        return new FixResult(false, output);
    }
}
