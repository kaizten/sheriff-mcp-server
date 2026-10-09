package com.kaizten.sheriff.domain.valueobject;

/**
 * What a {@link com.kaizten.sheriff.domain.port.TestRunner} reports back:
 * the gate that catches what a green Sheriff run cannot see.
 *
 * <p>A project with no tests to run has not failed any, so it is {@code ok},
 * but it has not passed any either, and {@code ran} is what keeps the two
 * apart: a component with no build tool used to be reported as one whose
 * tests passed.
 *
 * @param ok whether the target project's own tests passed, or there were none
 *     to fail
 * @param output the test output, which the repair pass needs in full, or why
 *     nothing was run
 * @param ran whether any tests were run at all
 */
public record VerificationResult(boolean ok, String output, boolean ran) {

    /**
     * Checks the values it is given instead of quietly correcting them.
     *
     * <p>A value object holds a checked value, so an absent one is refused
     * where it appears rather than replaced by something harmless-looking.
     *
     * @throws IllegalArgumentException when a required value is not defined
     */
    public VerificationResult {
        if (output == null) {
            throw new IllegalArgumentException(ERROR_OUTPUT_NOT_DEFINED);
        }
    }

    private static final String ERROR_OUTPUT_NOT_DEFINED = "Output is not defined";

    /**
     * The result of tests that were run.
     *
     * @param ok whether they passed
     * @param output what they printed
     */
    public VerificationResult(boolean ok, String output) {
        this(ok, output, true);
    }

    /**
     * A passing test run.
     *
     * @param output the test output
     * @return the passing result
     */
    public static VerificationResult passed(String output) {
        return new VerificationResult(true, output);
    }

    /**
     * A failing test run.
     *
     * @param output the test output, carried to the repair pass verbatim
     * @return the failing result
     */
    public static VerificationResult failed(String output) {
        return new VerificationResult(false, output);
    }

    /**
     * No tests were run, because there were none to run.
     *
     * @param reason why, as it is shown instead of test output
     * @return a result that stops nothing and claims nothing
     */
    public static VerificationResult notRun(String reason) {
        return new VerificationResult(true, reason, false);
    }
}
