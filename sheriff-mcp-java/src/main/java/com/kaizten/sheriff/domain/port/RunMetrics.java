package com.kaizten.sheriff.domain.port;

import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.PassMetrics;
import com.kaizten.sheriff.domain.valueobject.RunSummary;
import java.util.Set;

/**
 * Where the fix loop leaves what each of its passes did, measured, so runs
 * can be compared: which rules Sheriff's fixers repair and which the model
 * does, how long each takes and what it costs.
 *
 * <p>Only {@link #record} must be implemented: the rest tell a whole run's
 * report what the passes alone do not, and default to nothing.
 */
@FunctionalInterface
public interface RunMetrics {

    /**
     * Keeps one pass, once the analysis after it has measured it.
     *
     * @param pass what the pass did
     */
    void record(PassMetrics pass);

    /**
     * Sees one analysis of the run, in the order they were made: the first
     * is what the run started from, the last what it left.
     *
     * @param analysis what Sheriff found, or why it could not say
     */
    default void analyzed(AnalysisResult analysis) {
    }

    /**
     * Sees what a pass of the model's is given, before it runs.
     *
     * @param label the pass, as its prompt log names it
     * @param files the files it may change
     * @param errors how many errors it is asked to fix
     */
    default void given(String label, Set<String> files, int errors) {
    }

    /**
     * Sees how the run ended, after its last pass.
     *
     * @param summary what the run did and why it stopped
     */
    default void finished(RunSummary summary) {
    }
}
