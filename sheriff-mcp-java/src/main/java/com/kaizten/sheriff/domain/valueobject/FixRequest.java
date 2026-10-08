package com.kaizten.sheriff.domain.valueobject;

import java.util.Set;

/**
 * What a {@link com.kaizten.sheriff.domain.port.CodeFixer} gets asked to do.
 *
 * @param prompt the full instruction, rules and errors included
 * @param label what to call this invocation in the prompt log
 * @param allowedFiles the only files the fixer may touch, or {@code null} for
 *     the unrestricted repair pass
 */
public record FixRequest(String prompt, String label, Set<String> allowedFiles) {

    /**
     * Checks the prompt and its label, and freezes the file scope.
     *
     * <p>A null scope is not a missing value here: it is what the repair pass
     * looks like, and the difference between "these files" and "any file" is
     * the whole point of the type. The prompt and the label are another
     * matter and are required.
     *
     * @throws IllegalArgumentException when the prompt or the label is absent
     */
    public FixRequest {
        if (prompt == null) {
            throw new IllegalArgumentException(ERROR_PROMPT_NOT_DEFINED);
        }
        if (label == null) {
            throw new IllegalArgumentException(ERROR_LABEL_NOT_DEFINED);
        }
        allowedFiles = allowedFiles == null ? null : Set.copyOf(allowedFiles);
    }

    private static final String ERROR_PROMPT_NOT_DEFINED = "Prompt is not defined";
    private static final String ERROR_LABEL_NOT_DEFINED = "Label is not defined";

    /**
     * A request restricted to the files Sheriff flagged.
     *
     * @param prompt the full instruction
     * @param label what to call it in the prompt log
     * @param allowedFiles the files the fixer may touch
     * @return the scoped request
     */
    public static FixRequest scoped(String prompt, String label, Set<String> allowedFiles) {
        return new FixRequest(prompt, label, allowedFiles);
    }

    /**
     * A request with no file restriction, for the repair pass: the break is by
     * definition in a file the analyzer never flagged.
     *
     * @param prompt the full instruction
     * @param label what to call it in the prompt log
     * @return the unrestricted request
     */
    public static FixRequest unrestricted(String prompt, String label) {
        return new FixRequest(prompt, label, null);
    }

    /**
     * Whether this request limits which files may be edited.
     *
     * @return {@code true} unless this is the repair pass
     */
    public boolean isScoped() {
        return allowedFiles != null;
    }
}
