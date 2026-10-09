package com.kaizten.sheriff.application;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The bookkeeping of one run of the fix loop: how many iterations have gone
 * by, how the error count has moved, and which files stopped being worth
 * offering to the fixer.
 *
 * <p>Its own class, outside the service package, for two reasons that arrived
 * from Sheriff rather than from taste. Nested types are rejected outright by
 * the hexagonal profile, and every class inside a {@code service} package is
 * expected to implement a use case — which this does not and should not. It
 * exposes behaviour rather than fields so that the loop reads as decisions
 * ("has this pass made progress?") instead of as list manipulation.
 */
public final class RunState {

    private static final String LABEL_SEPARATOR = "/";
    private static final String NO_BRANCH = "";

    private final List<Integer> errorHistory = new ArrayList<>();
    private final Set<String> parkedFiles = new TreeSet<>();
    private Set<String> lastBatchFiles = Set.of();
    private Integer maxIterations;
    private int firstEstimate;
    private int iteration = 1;
    private boolean repairPassUsed;
    private String workingBranch = NO_BRANCH;

    /**
     * Starts the bookkeeping for one run.
     *
     * @param maxIterations the configured cap, or {@code null} to size it from
     *     the first analysis
     */
    public RunState(Integer maxIterations) {
        this.maxIterations = maxIterations;
    }

    /**
     * The cap in force, which may not be known yet.
     *
     * @return the cap, or {@code null} while it is still to be sized
     */
    public Integer maxIterations() {
        return maxIterations;
    }

    /**
     * Sets the cap: once the first analysis has said how much work there is,
     * and again when a pass that lowered the errors shows more is needed. The
     * first one is remembered, as the most the cap may grow to depends on it.
     *
     * @param cap the number of iterations this run may use
     */
    public void maxIterations(int cap) {
        if (maxIterations == null) {
            firstEstimate = cap;
        }
        this.maxIterations = cap;
    }

    /**
     * The cap the first analysis gave, when it was sized automatically.
     *
     * @return that cap, or zero when it was configured or not sized yet
     */
    public int firstEstimate() {
        return firstEstimate;
    }

    /**
     * Which iteration is running.
     *
     * @return the one-based iteration number
     */
    public int iteration() {
        return iteration;
    }

    /**
     * Moves on to the next iteration.
     */
    public void nextIteration() {
        iteration++;
    }

    /**
     * Records that the run ended by exhausting its cap, so the report says how
     * many iterations were actually spent rather than one more than that.
     */
    public void iterationsExhausted() {
        iteration = maxIterations;
    }

    /**
     * How this iteration is announced on the console.
     *
     * @return {@code "3/8"} when the cap is known, {@code "3"} while it is not
     */
    public String label() {
        return maxIterations == null ? String.valueOf(iteration) : iteration + LABEL_SEPARATOR + maxIterations;
    }

    /**
     * Whether any pass has reported an error count yet.
     *
     * @return {@code true} once there is something to compare against
     */
    public boolean hasHistory() {
        return !errorHistory.isEmpty();
    }

    /**
     * Notes the error count this pass started from.
     *
     * @param total the number of errors the analyzer reported
     */
    public void recordTotal(int total) {
        errorHistory.add(total);
    }

    /**
     * The error count of the previous pass.
     *
     * @return that count, which is what "did this pass make progress?" is
     *     measured against
     */
    public int lastTotal() {
        return errorHistory.get(errorHistory.size() - 1);
    }

    /**
     * The files the last pass was allowed to touch.
     *
     * @return those paths, empty before the first pass
     */
    public Set<String> lastBatchFiles() {
        return lastBatchFiles;
    }

    /**
     * Remembers the scope of the pass about to run.
     *
     * @param files the files that pass may touch
     */
    public void lastBatchFiles(Set<String> files) {
        this.lastBatchFiles = new LinkedHashSet<>(files);
    }

    /**
     * Stops offering a set of files to the fixer, because a pass over them
     * changed nothing.
     *
     * @param files the files to park
     */
    public void park(Set<String> files) {
        parkedFiles.addAll(files);
    }

    /**
     * Whether a file has already been given up on.
     *
     * @param file the path to check
     * @return {@code true} when it is parked
     */
    public boolean isParked(String file) {
        return parkedFiles.contains(file);
    }

    /**
     * Whether anything has been parked at all.
     *
     * @return {@code true} when no file has been given up on
     */
    public boolean nothingParked() {
        return parkedFiles.isEmpty();
    }

    /**
     * The parked files, for the report and the console.
     *
     * @return those paths in a stable order, unmodifiable
     */
    public Set<String> parkedFiles() {
        return Collections.unmodifiableSet(parkedFiles);
    }

    /**
     * The parked files as the report's list.
     *
     * @return a sorted copy
     */
    public List<String> parkedFilesAsList() {
        return new ArrayList<>(parkedFiles);
    }

    /**
     * Whether the one unrestricted repair pass has been spent.
     *
     * @return {@code true} once it has
     */
    public boolean repairPassUsed() {
        return repairPassUsed;
    }

    /**
     * Records that the repair pass is being spent now.
     */
    public void markRepairPassUsed() {
        repairPassUsed = true;
    }

    /**
     * The branch the run is working on.
     *
     * @return its name, or the empty string when git safety is off
     */
    public String workingBranch() {
        return workingBranch;
    }

    /**
     * Records the branch the run created for itself.
     *
     * @param branch its name
     */
    public void workingBranch(String branch) {
        this.workingBranch = branch;
    }
}
