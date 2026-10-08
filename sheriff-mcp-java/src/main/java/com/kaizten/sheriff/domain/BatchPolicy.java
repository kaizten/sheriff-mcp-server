package com.kaizten.sheriff.domain;

import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * How much work one pass takes on, and how many passes a run is allowed.
 *
 * <p>Pure domain policy, not orchestration: deciding which errors belong in a
 * batch is a rule about findings, and it holds whether the fixer behind it is
 * the Claude CLI, an API or nothing at all. It sits in the domain for that
 * reason, and because a class living in a {@code service} package is expected
 * to implement a use case, which a pair of pure functions neither does nor
 * should.
 */
public final class BatchPolicy {

    /**
     * The cap to use when the number of files gives no better answer.
     */
    public static final int FALLBACK_MAX_ITERATIONS = 8;

    /**
     * How many times the first estimate an automatic cap may grow to.
     */
    public static final int CEILING_FACTOR = 3;

    private static final int ITERATION_MARGIN = 2;

    private static final int NONE = 0;

    /**
     * Never called: this class is a pair of pure functions and holds no state,
     * so an instance of it would mean nothing.
     */
    private BatchPolicy() {
        throw new UnsupportedOperationException(UTILITY_CLASS_MESSAGE);
    }

    private static final String UTILITY_CLASS_MESSAGE = "This is a utility class and cannot be instantiated.";

    /**
     * The default iteration cap when none was set, sized to how many distinct
     * files currently have errors rather than to a fixed guess.
     *
     * <p>A heuristic, not a guarantee: a file that needs more than one attempt
     * to come out clean can still exceed it, which is what the exhausted-
     * iterations stop is still for. Without batching there is no "files per
     * pass" to divide by, so this falls back to the old fixed default instead
     * of guessing.
     *
     * @param filesWithErrors how many files the first analysis flagged
     * @param maxFilesPerBatch how many files one pass covers
     * @return the cap to use
     */
    public static int estimateMaxIterations(int filesWithErrors, int maxFilesPerBatch) {
        if (maxFilesPerBatch <= NONE || filesWithErrors <= NONE) {
            return FALLBACK_MAX_ITERATIONS;
        }
        int passesNeeded = (filesWithErrors + maxFilesPerBatch - 1) / maxFilesPerBatch;
        return passesNeeded + ITERATION_MARGIN;
    }

    /**
     * The automatic cap after a pass that lowered the errors: the passes
     * already run, plus what the files still with errors need, as the first
     * estimate counts it. Never below the cap there was, and never above
     * {@link #CEILING_FACTOR} times the first estimate.
     *
     * <p>The first estimate gives each batch of files one pass and the whole
     * run two to spare. A project with errors in hundreds of files, many of
     * them needing a second pass, runs out of those two with work left, which
     * is what this is for. A pass that lowered nothing does not come here:
     * its files are parked, and with nothing left to try the run stops. The
     * ceiling keeps a run that lowers the errors very slowly from costing
     * without end.
     *
     * @param cap the cap in force
     * @param passesRun the passes already run
     * @param filesWithErrors how many files still have errors
     * @param maxFilesPerBatch how many files one pass covers
     * @param firstEstimate the cap the first analysis gave
     * @return the cap to use from now on
     */
    public static int extendedMaxIterations(
            int cap, int passesRun, int filesWithErrors, int maxFilesPerBatch, int firstEstimate) {
        if (maxFilesPerBatch <= NONE || filesWithErrors <= NONE) {
            return cap;
        }
        int needed = passesRun + estimateMaxIterations(filesWithErrors, maxFilesPerBatch);
        return Math.max(cap, Math.min(needed, firstEstimate * CEILING_FACTOR));
    }

    /**
     * Bounds a pass to at most {@code maxFiles} distinct files, so a single
     * fixer call never has to touch dozens at once.
     *
     * <p>A file's errors are always kept together, never split across passes,
     * and files beyond the cap are simply left for a later iteration. Files in
     * {@code skip} are left out entirely: a previous pass already failed to
     * improve them, so retrying them forever would block every other file
     * queued behind them.
     *
     * @param errors every error the analyzer reported
     * @param maxFiles the cap, zero or less to disable batching
     * @param skip the parked files
     * @return the errors that make up this pass
     */
    public static List<SheriffFinding> selectBatch(List<SheriffFinding> errors, int maxFiles, Set<String> skip) {
        List<SheriffFinding> candidates = errors.stream().filter(f -> !skip.contains(f.file())).toList();
        if (maxFiles <= NONE) {
            return candidates;
        }
        Set<String> selectedFiles = new LinkedHashSet<>();
        List<SheriffFinding> batch = new ArrayList<>();
        for (SheriffFinding finding : candidates) {
            if (!selectedFiles.contains(finding.file())) {
                if (selectedFiles.size() >= maxFiles) {
                    continue;
                }
                selectedFiles.add(finding.file());
            }
            batch.add(finding);
        }
        return batch;
    }
}
