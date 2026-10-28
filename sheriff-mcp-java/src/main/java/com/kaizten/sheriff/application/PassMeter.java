package com.kaizten.sheriff.application;

import com.kaizten.sheriff.domain.port.RunMetrics;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.PassMetrics;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.domain.valueobject.TokenUsage;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Measures the passes of one run: a pass is held until the analysis after it
 * says what it left, then handed to the run's metrics.
 *
 * <p>The loop analyzes at the start of every iteration anyway, so measuring a
 * pass costs no analysis of its own. A pass no analysis followed (the run cut
 * after it, or Sheriff not answering) is kept all the same, with nothing
 * measured after it, as what it cost was spent.
 */
public final class PassMeter {

    private static final Set<String> WHOLE_COMPONENT = null;
    private static final int ONE_ERROR = 1;

    private final RunMetrics metrics;
    private String label;
    private String kind;
    private Map<String, Integer> before;
    private Set<String> scope;
    private Duration elapsed;
    private TokenUsage usage;
    private boolean pending;

    /**
     * Starts measuring one run.
     *
     * @param metrics where each measured pass goes
     */
    public PassMeter(RunMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * Holds a pass of Sheriff's own fixers, measured on the whole component.
     *
     * @param label the pass's name
     * @param analyzedBefore what the fixers found before they ran
     * @param took how long they took
     */
    public void deterministicPassed(String label, AnalysisResult analyzedBefore, Duration took) {
        hold(label, PassMetrics.DETERMINISTIC, countByRule(analyzedBefore.errors(), WHOLE_COMPONENT),
                WHOLE_COMPONENT, took, null);
    }

    /**
     * Holds a pass of the model's, measured on the files it was given.
     *
     * @param label the pass's name
     * @param analyzedBefore the analysis its batch came from
     * @param files the files of its batch, and the names they were renamed to
     * @param took how long the model took
     * @param cost what it cost, {@code null} when its backend did not say
     */
    public void modelPassed(String label, AnalysisResult analyzedBefore, Set<String> files, Duration took,
            TokenUsage cost) {
        hold(label, PassMetrics.MODEL, countByRule(analyzedBefore.errors(), files), Set.copyOf(files), took, cost);
    }

    /**
     * Holds a pass of the model's over the whole component, which had no
     * errors before it: the repair of a failing test.
     *
     * @param label the pass's name
     * @param took how long the model took
     * @param cost what it cost, {@code null} when its backend did not say
     */
    public void repairPassed(String label, Duration took, TokenUsage cost) {
        hold(label, PassMetrics.MODEL, Map.of(), WHOLE_COMPONENT, took, cost);
    }

    /**
     * Measures the pass held with the analysis after it, and keeps it.
     *
     * @param after the analysis that followed the pass; one that failed
     *     measures nothing
     */
    public void measuredBy(AnalysisResult after) {
        if (!pending) {
            return;
        }
        keep(after.error() ? null : countByRule(after.errors(), scope));
    }

    /**
     * Keeps the pass held, if any, with nothing measured after it.
     */
    public void unmeasured() {
        if (pending) {
            keep(null);
        }
    }

    /**
     * How many errors each rule has among some findings.
     *
     * @param findings the findings to count
     * @param files the files to count them in, {@code null} for all of them
     * @return each rule with its count, in name order
     */
    static Map<String, Integer> countByRule(List<SheriffFinding> findings, Set<String> files) {
        Map<String, Integer> counts = new TreeMap<>();
        for (SheriffFinding finding : findings) {
            if (files == WHOLE_COMPONENT || files.contains(finding.file())) {
                counts.merge(finding.referenceCode(), ONE_ERROR, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * Holds a pass until an analysis measures it, keeping first one held
     * before, which no analysis will measure now.
     *
     * @param name the pass's name
     * @param passKind which kind of pass it is
     * @param counted the errors in its scope before it, rule by rule
     * @param files its scope, {@code null} for the whole component
     * @param took how long it took
     * @param cost what it cost, or {@code null}
     */
    private void hold(String name, String passKind, Map<String, Integer> counted, Set<String> files, Duration took,
            TokenUsage cost) {
        unmeasured();
        label = name;
        kind = passKind;
        before = counted;
        scope = files;
        elapsed = took;
        usage = cost;
        pending = true;
    }

    /**
     * Hands the pass held to the run's metrics.
     *
     * @param after the errors in its scope after it, {@code null} when not
     *     measured
     */
    private void keep(Map<String, Integer> after) {
        pending = false;
        metrics.record(new PassMetrics(label, kind, before, after, elapsed, usage));
    }
}
