package com.kaizten.sheriff.application;

import com.kaizten.sheriff.domain.port.RunMetrics;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.PassMetrics;
import com.kaizten.sheriff.domain.valueobject.RunSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * A run's metrics that only write down what they are told, in order, one
 * line an event, for a test to read back.
 */
final class RecordingMetrics implements RunMetrics {

    private final List<String> events = new ArrayList<>();
    private final List<PassMetrics> passes = new ArrayList<>();
    private RunSummary summary;

    @Override
    public void record(PassMetrics pass) {
        passes.add(pass);
        events.add("pass " + pass.label());
    }

    @Override
    public void analyzed(AnalysisResult analysis) {
        events.add(analysis.error() ? "analysis failed" : "analysis " + analysis.total());
    }

    @Override
    public void given(String label, Set<String> files, int errors) {
        events.add("given " + label + " " + new TreeSet<>(files) + " " + errors);
    }

    @Override
    public void finished(RunSummary ended) {
        summary = ended;
        events.add("finished " + ended.stoppedReason().wireName());
    }

    List<String> events() {
        return events;
    }

    List<PassMetrics> passes() {
        return passes;
    }

    RunSummary summary() {
        return summary;
    }
}
