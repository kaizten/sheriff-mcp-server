package com.kaizten.sheriff.domain.valueobject;

import com.kaizten.sheriff.domain.enumerate.StopReason;
import java.util.List;

/**
 * What a finished run of the fix loop leaves behind: the structured version of
 * what the console output already said, for a caller that wants a report
 * instead of scrollback.
 *
 * @param ok whether the run reached zero errors with the tests passing
 * @param stoppedReason why it ended
 * @param iterationsUsed how many iterations it took
 * @param maxIterations the resolved cap, null when it never needed one
 * @param repairPassUsed whether the unrestricted repair pass was spent
 * @param parkedFiles files the loop stopped offering to the fixer because a
 *     pass over them changed nothing; on a stalled run, the list to look at
 * @param workingBranch the branch the run left the repository on, empty when
 *     it worked without git safety; a caller that did not start the run
 *     itself, such as an MCP client, has no other way to know it moved
 * @param testsPassed whether the project's own tests pass on what the run
 *     left behind, {@code null} when they were not run; a run that stopped
 *     short still checks, so a branch that no longer compiles says so
 */
public record RunSummary(
        boolean ok,
        StopReason stoppedReason,
        int iterationsUsed,
        Integer maxIterations,
        boolean repairPassUsed,
        List<String> parkedFiles,
        String workingBranch,
        Boolean testsPassed) {

    /**
     * Checks the values it is given instead of quietly correcting them.
     *
     * <p>A value object holds a checked value, so an absent one is refused
     * where it appears rather than replaced by something harmless-looking.
     *
     * @throws IllegalArgumentException when a required value is not defined
     */
    public RunSummary {
        if (stoppedReason == null) {
            throw new IllegalArgumentException(ERROR_STOPPED_REASON_NOT_DEFINED);
        }
        if (parkedFiles == null) {
            throw new IllegalArgumentException(ERROR_PARKED_FILES_NOT_DEFINED);
        }
        if (workingBranch == null) {
            throw new IllegalArgumentException(ERROR_WORKING_BRANCH_NOT_DEFINED);
        }
        parkedFiles = List.copyOf(parkedFiles);
    }

    /**
     * A summary whose tests were not run.
     *
     * @param ok whether the run reached zero errors with the tests passing
     * @param stoppedReason why it ended
     * @param iterationsUsed how many iterations it took
     * @param maxIterations the resolved cap, null when it never needed one
     * @param repairPassUsed whether the unrestricted repair pass was spent
     * @param parkedFiles files the loop stopped offering to the fixer
     * @param workingBranch the branch the run left the repository on
     */
    public RunSummary(
            boolean ok,
            StopReason stoppedReason,
            int iterationsUsed,
            Integer maxIterations,
            boolean repairPassUsed,
            List<String> parkedFiles,
            String workingBranch) {
        this(ok, stoppedReason, iterationsUsed, maxIterations, repairPassUsed, parkedFiles, workingBranch, null);
    }

    /**
     * A summary of a run that worked without a branch of its own.
     *
     * @param ok whether the run reached zero errors with the tests passing
     * @param stoppedReason why it ended
     * @param iterationsUsed how many iterations it took
     * @param maxIterations the resolved cap, null when it never needed one
     * @param repairPassUsed whether the unrestricted repair pass was spent
     * @param parkedFiles files the loop stopped offering to the fixer
     */
    public RunSummary(
            boolean ok,
            StopReason stoppedReason,
            int iterationsUsed,
            Integer maxIterations,
            boolean repairPassUsed,
            List<String> parkedFiles) {
        this(ok, stoppedReason, iterationsUsed, maxIterations, repairPassUsed, parkedFiles, NO_BRANCH);
    }

    private static final String ERROR_STOPPED_REASON_NOT_DEFINED = "Stopped reason is not defined";
    private static final String ERROR_PARKED_FILES_NOT_DEFINED = "Parked files are not defined";
    private static final String ERROR_WORKING_BRANCH_NOT_DEFINED = "Working branch is not defined";
    private static final String NO_BRANCH = "";
}
