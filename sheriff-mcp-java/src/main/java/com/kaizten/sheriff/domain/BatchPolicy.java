package com.kaizten.sheriff.domain;

import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

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

    private static final int ONE_PASS = 1;

    private static final String RULE_IN_FILE = "%s in %s";

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
     * The cap when none was set, sized to the passes the grouping needs for
     * these errors.
     *
     * <p>By file that is a pass per batch of files, as
     * {@link #estimateMaxIterations(int, int)} counts it. By rule it is a pass
     * per batch of each rule's files, and one per rule with no limit on the
     * files.
     *
     * @param errors every error the analyzer reported
     * @param grouping what the errors of one pass have in common
     * @param maxFilesPerBatch how many files one pass covers
     * @return the cap to use
     */
    public static int estimateMaxIterations(List<SheriffFinding> errors, BatchGrouping grouping, int maxFilesPerBatch) {
        if (grouping == BatchGrouping.FILE) {
            return estimateMaxIterations(filesOf(errors).size(), maxFilesPerBatch);
        }
        if (errors.isEmpty()) {
            return FALLBACK_MAX_ITERATIONS;
        }
        int passesNeeded = NONE;
        for (Set<String> files : filesByRule(errors).values()) {
            passesNeeded += maxFilesPerBatch <= NONE ? ONE_PASS
                    : (files.size() + maxFilesPerBatch - 1) / maxFilesPerBatch;
        }
        return passesNeeded + ITERATION_MARGIN;
    }

    /**
     * The automatic cap after a pass that lowered the errors, as
     * {@link #extendedMaxIterations(int, int, int, int, int)} grows it, with
     * the passes still needed counted by the grouping.
     *
     * @param cap the cap in force
     * @param passesRun the passes already run
     * @param errors the errors still there
     * @param grouping what the errors of one pass have in common
     * @param maxFilesPerBatch how many files one pass covers
     * @param firstEstimate the cap the first analysis gave
     * @return the cap to use from now on
     */
    public static int extendedMaxIterations(int cap, int passesRun, List<SheriffFinding> errors,
            BatchGrouping grouping, int maxFilesPerBatch, int firstEstimate) {
        if (grouping == BatchGrouping.FILE) {
            return extendedMaxIterations(cap, passesRun, filesOf(errors).size(), maxFilesPerBatch, firstEstimate);
        }
        if (errors.isEmpty()) {
            return cap;
        }
        int needed = passesRun + estimateMaxIterations(errors, grouping, maxFilesPerBatch);
        return Math.max(cap, Math.min(needed, firstEstimate * CEILING_FACTOR));
    }

    /**
     * The errors of the next pass, grouped as asked, leaving out what an
     * earlier pass could not improve.
     *
     * <p>By file it is {@link #selectBatch(List, int, Set)}. By rule it is the
     * rule with the most errors not parked, the first by name among equals,
     * in at most {@code maxFiles} of its files; what is parked is a rule in a
     * file, so a file whose one rule a model could not fix is still offered
     * for its others.
     *
     * @param errors every error the analyzer reported
     * @param grouping what the errors of one pass have in common
     * @param maxFiles the cap on files, zero or less for none
     * @param parked what earlier passes could not improve, as
     *     {@link #parkingKey(SheriffFinding, BatchGrouping)} names it
     * @return the errors that make up this pass
     */
    public static List<SheriffFinding> selectBatch(
            List<SheriffFinding> errors, BatchGrouping grouping, int maxFiles, Set<String> parked) {
        if (grouping == BatchGrouping.FILE) {
            return selectBatch(errors, maxFiles, parked);
        }
        List<SheriffFinding> candidates = errors.stream()
                .filter(finding -> !parked.contains(parkingKey(finding, grouping))).toList();
        Map<String, Long> perRule = candidates.stream()
                .collect(Collectors.groupingBy(SheriffFinding::referenceCode, TreeMap::new, Collectors.counting()));
        return perRule.entrySet().stream().max(Map.Entry.<String, Long>comparingByValue()
                        .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder())))
                .map(chosen -> selectBatch(candidates.stream()
                        .filter(finding -> finding.referenceCode().equals(chosen.getKey())).toList(), maxFiles, Set.of()))
                .orElse(List.of());
    }

    /**
     * What a pass that improved nothing sets aside of an error: its file, or
     * its rule in its file.
     *
     * @param finding the error
     * @param grouping what the errors of one pass have in common
     * @return the name it is parked under
     */
    public static String parkingKey(SheriffFinding finding, BatchGrouping grouping) {
        return grouping == BatchGrouping.FILE ? finding.file()
                : String.format(RULE_IN_FILE, finding.referenceCode(), finding.file());
    }

    /**
     * The distinct files of some errors, as
     * {@code AnalysisResult.filesWithErrors} counts them: an error with no
     * file has none.
     *
     * @param errors the errors
     * @return their files, in the order they come
     */
    private static Set<String> filesOf(List<SheriffFinding> errors) {
        Set<String> files = new LinkedHashSet<>();
        for (SheriffFinding finding : errors) {
            if (!finding.file().isEmpty()) {
                files.add(finding.file());
            }
        }
        return files;
    }

    /**
     * The files each rule has errors in.
     *
     * @param errors the errors
     * @return each rule's files
     */
    private static Map<String, Set<String>> filesByRule(List<SheriffFinding> errors) {
        Map<String, Set<String>> files = new TreeMap<>();
        for (SheriffFinding finding : errors) {
            files.computeIfAbsent(finding.referenceCode(), rule -> new LinkedHashSet<>()).add(finding.file());
        }
        return files;
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
