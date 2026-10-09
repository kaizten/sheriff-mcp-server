package com.kaizten.sheriff.infrastructure.process;

/**
 * What running an external command produced.
 *
 * <p>"The command failed" and "the command could not be run" are different
 * answers and are kept apart deliberately: Sheriff exits 0 whether it found
 * three hundred issues or none, so a non-zero exit means the invocation itself
 * broke, and a command that never started means nothing can be concluded at
 * all. Collapsing those into a boolean is how a missing Docker turns into
 * "your code is clean".
 *
 * @param status whether the command ran, never started, or was killed
 * @param exitCode its exit code, zero when it never ran
 * @param standardOutput everything it wrote to stdout
 * @param standardError everything it wrote to stderr
 * @param failure why it never ran or was killed, empty when it ran
 */
public record ProcessOutcome(
        ProcessStatus status, int exitCode, String standardOutput, String standardError, String failure) {

    /**
     * Normalizes the nulls a caller or a stream reader can produce.
     *
     * <p>Every text field is non-null from here on, so that reading the output
     * of a command that produced none is an empty string rather than a crash.
     */
    public ProcessOutcome {
        standardOutput = standardOutput == null ? "" : standardOutput;
        standardError = standardError == null ? "" : standardError;
        failure = failure == null ? "" : failure;
    }

    /**
     * A command that ran to completion, whatever its exit code.
     *
     * @param exitCode the code it returned
     * @param standardOutput what it wrote to stdout
     * @param standardError what it wrote to stderr
     * @return the outcome
     */
    public static ProcessOutcome completed(int exitCode, String standardOutput, String standardError) {
        return new ProcessOutcome(ProcessStatus.COMPLETED, exitCode, standardOutput, standardError, "");
    }

    /**
     * A command that could not be started at all, typically because the
     * executable is not on the PATH.
     *
     * @param failure why it could not start
     * @return the outcome
     */
    public static ProcessOutcome unavailable(String failure) {
        return new ProcessOutcome(ProcessStatus.UNAVAILABLE, 0, "", "", failure);
    }

    /**
     * A command that was killed for taking too long.
     *
     * @param failure how long it was given before being killed
     * @return the outcome
     */
    public static ProcessOutcome timedOut(String failure) {
        return new ProcessOutcome(ProcessStatus.TIMED_OUT, 0, "", "", failure);
    }

    /**
     * Whether the command ran and reported success.
     *
     * @return {@code true} only when it both ran and exited zero
     */
    public boolean succeeded() {
        return status == ProcessStatus.COMPLETED && exitCode == SUCCESS_EXIT_CODE;
    }

    /**
     * Whether the command ran at all, whatever it then reported.
     *
     * @return {@code true} when there is an exit code worth reading
     */
    public boolean ran() {
        return status == ProcessStatus.COMPLETED;
    }

    /**
     * The exit code every well-behaved command uses for success.
     *
     * <p>Only ever compared against a command that actually ran; Sheriff's own
     * zero means "it ran", not "it found nothing".
     */
    private static final int SUCCESS_EXIT_CODE = 0;
}
