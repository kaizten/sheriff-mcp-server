package com.kaizten.sheriff.infrastructure.docker;

import java.util.List;

/**
 * What the deterministic pass was able to do.
 *
 * <p>It reports what it <em>attempted</em> rather than what it achieved:
 * whether a fixer actually repaired anything is the next analysis's answer,
 * and claiming otherwise would be trusting a tool that has already been caught
 * reporting a stale clean.
 *
 * @param ran whether the pass happened at all
 * @param errorsBefore how many errors there were when it started
 * @param codes the reference codes it handed to Sheriff's fixers
 * @param failure why it could not run, empty when it did
 * @param withoutCatalog whether it ran with no rule catalog, and so could only
 *     apply Sheriff's default set: which rules have a fixer of their own is
 *     read from the catalog
 * @param fixerFailures the fixers Sheriff said it could not apply, each as
 *     {@code CODE: reason}; a rule handed to a fixer that cannot run is not a
 *     rule anything is repairing
 */
public record DeterministicFixReport(
        boolean ran,
        int errorsBefore,
        List<String> codes,
        String failure,
        boolean withoutCatalog,
        List<String> fixerFailures) {

    private static final String COULD_NOT_RUN = "Sheriff's own fixers could not run: %s";
    private static final String NOTHING_FIXABLE =
            "Nothing Sheriff can fix by itself among %d error(s); all of it needs the AI.";
    private static final String APPLIED = "Sheriff's own fixers were given %d rule(s) to repair, free of charge: %s";
    private static final String CODE_SEPARATOR = ", ";
    private static final String DEFAULTS_ONLY =
            "Sheriff's fixers ran for every rule among the %d error(s), but with no rule catalog which of "
            + "them have a fixer is unknown, so the next analysis is what says what was repaired. Extract "
            + "a catalog with --extract-rules for a report of it.";
    private static final String NOT_APPLIED = " Sheriff could not apply %d of them: %s";
    private static final String FAILURE_SEPARATOR = "; ";

    /**
     * Makes the code list unmodifiable.
     *
     * <p>A report is read after the fact, often from another thread's console
     * output, and there is nothing useful anyone could do by changing it.
     */
    public DeterministicFixReport {
        codes = codes == null ? List.of() : List.copyOf(codes);
        failure = failure == null ? "" : failure;
        fixerFailures = fixerFailures == null ? List.of() : List.copyOf(fixerFailures);
    }

    /**
     * A report with no record of fixers Sheriff could not apply.
     *
     * @param ran whether the pass happened at all
     * @param errorsBefore how many errors there were when it started
     * @param codes the reference codes it handed to Sheriff's fixers
     * @param failure why it could not run, empty when it did
     * @param withoutCatalog whether it ran with no rule catalog
     */
    public DeterministicFixReport(
            boolean ran, int errorsBefore, List<String> codes, String failure, boolean withoutCatalog) {
        this(ran, errorsBefore, codes, failure, withoutCatalog, List.of());
    }

    /**
     * A report of a pass that had a catalog to read.
     *
     * @param ran whether the pass happened at all
     * @param errorsBefore how many errors there were when it started
     * @param codes the reference codes it handed to Sheriff's fixers
     * @param failure why it could not run, empty when it did
     */
    public DeterministicFixReport(boolean ran, int errorsBefore, List<String> codes, String failure) {
        this(ran, errorsBefore, codes, failure, false);
    }

    /**
     * A pass that had no catalog, and so ran only Sheriff's default fixers.
     *
     * <p>Not "nothing to do": the default set needs no catalog, and a pass
     * that skipped it too once reported that nothing could be repaired on a
     * component where the plain {@code fix -f} would have repaired several.
     *
     * @param errorsBefore how many errors there were
     * @return that report
     */
    public static DeterministicFixReport defaultsOnly(int errorsBefore) {
        return new DeterministicFixReport(true, errorsBefore, List.of(), "", true);
    }

    /**
     * A pass that handed work to Sheriff's own fixers.
     *
     * @param errorsBefore how many errors there were
     * @param codes what it asked Sheriff to repair
     * @return that report
     */
    public static DeterministicFixReport applied(int errorsBefore, List<String> codes) {
        return new DeterministicFixReport(true, errorsBefore, codes, "");
    }

    /**
     * A pass that handed work to Sheriff's own fixers, some of which Sheriff
     * could not apply.
     *
     * @param errorsBefore how many errors there were
     * @param codes what it asked Sheriff to repair
     * @param fixerFailures the fixers Sheriff could not apply, and why
     * @return that report
     */
    public static DeterministicFixReport applied(int errorsBefore, List<String> codes, List<String> fixerFailures) {
        return new DeterministicFixReport(true, errorsBefore, codes, "", false, fixerFailures);
    }

    /**
     * A pass that found nothing Sheriff could repair by itself.
     *
     * @param errorsBefore how many errors there were
     * @return that report
     */
    public static DeterministicFixReport nothingToDo(int errorsBefore) {
        return new DeterministicFixReport(true, errorsBefore, List.of(), "");
    }

    /**
     * A pass that could not run, which is not the same as one that found
     * nothing to do.
     *
     * @param failure why
     * @return that report
     */
    public static DeterministicFixReport unavailable(String failure) {
        return new DeterministicFixReport(false, 0, List.of(), failure);
    }

    /**
     * The report as the one line a run log shows.
     *
     * @return what the pass attempted, or why it could not run
     */
    public String describe() {
        if (!ran) {
            return String.format(COULD_NOT_RUN, failure);
        }
        if (withoutCatalog) {
            return String.format(DEFAULTS_ONLY, errorsBefore);
        }
        if (!attemptedAnything()) {
            return String.format(NOTHING_FIXABLE, errorsBefore);
        }
        String applied = String.format(APPLIED, codes.size(), String.join(CODE_SEPARATOR, codes));
        if (fixerFailures.isEmpty()) {
            return applied;
        }
        return applied + String.format(NOT_APPLIED, fixerFailures.size(), String.join(FAILURE_SEPARATOR, fixerFailures));
    }

    /**
     * Whether anything was handed to a fixer.
     *
     * @return true when at least one code was
     */
    public boolean attemptedAnything() {
        return !codes.isEmpty() || withoutCatalog;
    }
}
