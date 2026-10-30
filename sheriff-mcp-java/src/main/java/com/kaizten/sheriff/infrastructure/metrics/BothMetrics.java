package com.kaizten.sheriff.infrastructure.metrics;

import com.kaizten.sheriff.domain.port.RunMetrics;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.PassMetrics;
import com.kaizten.sheriff.domain.valueobject.RunSummary;
import java.util.Set;

/**
 * Two places a run's metrics go at once: the line per pass every run
 * writes, and a whole run's report when one is asked for.
 *
 * @param first where everything goes first
 * @param second where it goes after
 */
public record BothMetrics(RunMetrics first, RunMetrics second) implements RunMetrics {

    /**
     * Keeps one pass in both.
     *
     * @param pass what the pass did
     */
    @Override
    public void record(PassMetrics pass) {
        first.record(pass);
        second.record(pass);
    }

    /**
     * Tells both of one analysis.
     *
     * @param analysis what Sheriff found, or why it could not say
     */
    @Override
    public void analyzed(AnalysisResult analysis) {
        first.analyzed(analysis);
        second.analyzed(analysis);
    }

    /**
     * Tells both what a pass was given.
     *
     * @param label the pass
     * @param files the files it may change
     * @param errors how many errors it is asked to fix
     */
    @Override
    public void given(String label, Set<String> files, int errors) {
        first.given(label, files, errors);
        second.given(label, files, errors);
    }

    /**
     * Tells both how the run ended.
     *
     * @param summary what the run did and why it stopped
     */
    @Override
    public void finished(RunSummary summary) {
        first.finished(summary);
        second.finished(summary);
    }
}
