package com.kaizten.sheriff.domain.valueobject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What a {@link com.kaizten.sheriff.domain.port.CodeAnalyzer} returns.
 *
 * <p>An analysis that could not run is not the same as an analysis that found
 * nothing, which is why {@code error} exists as its own field rather than
 * being inferred from an empty finding list. Sheriff itself exits 0 whether it
 * found 300 issues or none, so this distinction has to be carried explicitly
 * all the way through.
 *
 * <p>{@code trackedFiles} is what Sheriff says it looked at, with the hash of
 * each file as it read it. It is empty when nothing reported it, which is
 * not evidence that nothing was analyzed.
 *
 * @param findings everything Sheriff reported, errors and warnings alike
 * @param error whether the analysis failed to run at all
 * @param message why it failed, when it did
 * @param trackedFiles every file Sheriff analyzed, with its hash
 */
public record AnalysisResult(
        List<SheriffFinding> findings, boolean error, String message, List<TrackedFile> trackedFiles) {

    /**
     * Checks the values it is given instead of quietly correcting them.
     *
     * <p>A value object holds a checked value, so an absent one is refused
     * where it appears rather than replaced by something harmless-looking.
     *
     * @throws IllegalArgumentException when a required value is not defined
     */
    public AnalysisResult {
        if (findings == null) {
            throw new IllegalArgumentException(ERROR_FINDINGS_NOT_DEFINED);
        }
        if (message == null) {
            throw new IllegalArgumentException(ERROR_MESSAGE_NOT_DEFINED);
        }
        if (trackedFiles == null) {
            throw new IllegalArgumentException(ERROR_TRACKED_FILES_NOT_DEFINED);
        }
        findings = List.copyOf(findings);
        trackedFiles = List.copyOf(trackedFiles);
    }

    /**
     * A result with no record of which files were analyzed.
     *
     * @param findings everything Sheriff reported
     * @param error whether the analysis failed to run at all
     * @param message why it failed, when it did
     */
    public AnalysisResult(List<SheriffFinding> findings, boolean error, String message) {
        this(findings, error, message, List.of());
    }

    private static final String ERROR_FINDINGS_NOT_DEFINED = "Findings are not defined";
    private static final String ERROR_MESSAGE_NOT_DEFINED = "Message is not defined";
    private static final String ERROR_TRACKED_FILES_NOT_DEFINED = "Tracked files are not defined";

    /**
     * A successful analysis.
     *
     * @param findings what Sheriff reported
     * @return a result carrying those findings
     */
    public static AnalysisResult of(List<SheriffFinding> findings) {
        return new AnalysisResult(findings, false, "");
    }

    /**
     * A successful analysis that also says which files it looked at.
     *
     * @param findings what Sheriff reported
     * @param trackedFiles every file it analyzed, with its hash
     * @return a result carrying both
     */
    public static AnalysisResult of(List<SheriffFinding> findings, List<TrackedFile> trackedFiles) {
        return new AnalysisResult(findings, false, "", trackedFiles);
    }

    /**
     * One result from several analyses of the same component, one per
     * profile, since Sheriff runs a single profile at a time.
     *
     * <p>A finding two profiles both report is kept once, and so is a file
     * both tracked. A failure in any of them is the result: half an
     * analysis reported as a whole one would be a silent pass for the rest.
     *
     * @param results the analyses, in the order they ran
     * @return their union, or the first failure among them
     */
    public static AnalysisResult merged(List<AnalysisResult> results) {
        Map<List<String>, SheriffFinding> findings = new LinkedHashMap<>();
        Map<String, TrackedFile> tracked = new LinkedHashMap<>();
        for (AnalysisResult result : results) {
            if (result.error()) {
                return result;
            }
            for (SheriffFinding finding : result.findings()) {
                findings.putIfAbsent(
                        List.of(finding.file(), finding.description(), finding.referenceCode(), finding.type()),
                        finding);
            }
            for (TrackedFile file : result.trackedFiles()) {
                tracked.putIfAbsent(file.path(), file);
            }
        }
        return of(new ArrayList<>(findings.values()), new ArrayList<>(tracked.values()));
    }

    /**
     * An analysis that could not run.
     *
     * @param message why it could not run
     * @return a result carrying that failure
     */
    public static AnalysisResult failure(String message) {
        return new AnalysisResult(List.of(), true, message);
    }

    /**
     * The findings that have to be fixed.
     *
     * @return every finding Sheriff typed as an error
     */
    public List<SheriffFinding> errors() {
        return findings.stream().filter(finding -> !finding.isWarning()).toList();
    }

    /**
     * The findings that are only reported.
     *
     * @return every finding Sheriff typed as a warning
     */
    public List<SheriffFinding> warnings() {
        return findings.stream().filter(SheriffFinding::isWarning).toList();
    }

    /**
     * How many errors there are, which is what the loop stops on.
     *
     * @return the number of errors, ignoring warnings
     */
    public int total() {
        return errors().size();
    }

    /**
     * The distinct files that still have errors.
     *
     * <p>The set is unmodifiable: a value object hands out a view of what it
     * holds, never a handle a caller could edit behind its back.
     *
     * @return those file paths, in the order Sheriff reported them
     */
    public Set<String> filesWithErrors() {
        Set<String> files = new LinkedHashSet<>();
        for (SheriffFinding finding : errors()) {
            if (!finding.file().isEmpty()) {
                files.add(finding.file());
            }
        }
        return Collections.unmodifiableSet(files);
    }

    /**
     * The files whose content differs between an earlier analysis and this
     * one: changed, added or gone.
     *
     * <p>Only meaningful when both analyses recorded their files; with either
     * record missing there is nothing to compare, and the answer is empty
     * rather than a guess.
     *
     * @param earlier the analysis to compare against
     * @return those paths, this analysis's order first, then the ones gone
     */
    public List<String> changedSince(AnalysisResult earlier) {
        if (trackedFiles.isEmpty() || earlier.trackedFiles().isEmpty()) {
            return List.of();
        }
        Map<String, String> before = new LinkedHashMap<>();
        for (TrackedFile file : earlier.trackedFiles()) {
            before.put(file.path(), file.hash());
        }
        List<String> changed = new ArrayList<>();
        for (TrackedFile file : trackedFiles) {
            String previous = before.remove(file.path());
            if (!Objects.equals(previous, file.hash())) {
                changed.add(file.path());
            }
        }
        changed.addAll(before.keySet());
        return Collections.unmodifiableList(changed);
    }
}
