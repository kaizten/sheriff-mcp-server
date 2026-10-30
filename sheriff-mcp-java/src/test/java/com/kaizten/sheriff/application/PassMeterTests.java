package com.kaizten.sheriff.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.kaizten.sheriff.domain.enumerate.StopReason;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.PassMetrics;
import com.kaizten.sheriff.domain.valueobject.RunSummary;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.domain.valueobject.TokenUsage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for what holds a pass until the analysis after it measures it.
 */
class PassMeterTests {

    private static final Duration TOOK = Duration.ofSeconds(2);
    private static final TokenUsage USAGE = new TokenUsage(List.of(), 1, 2, 3, 4, 1, null);

    private final List<PassMetrics> kept = new ArrayList<>();
    private final PassMeter meter = new PassMeter(kept::add);

    private static SheriffFinding error(String file, String rule) {
        return new SheriffFinding(file, "d", "h", rule, SheriffFinding.ERROR, Map.of());
    }

    private static AnalysisResult errors(SheriffFinding... findings) {
        return AnalysisResult.of(List.of(findings));
    }

    @Test
    @DisplayName("a model's pass counts only its files, before and after")
    void aModelPassCountsItsFiles() {
        meter.modelPassed("iteration-1", errors(error("A", "R1"), error("B", "R1")), Set.of("A"), TOOK, USAGE);
        meter.measuredBy(errors(error("B", "R1"), error("B", "R2")));
        assertEquals(1, kept.size());
        assertEquals(Map.of("R1", 1), kept.get(0).errorsBefore());
        assertEquals(Map.of(), kept.get(0).errorsAfter());
        assertEquals(TOOK, kept.get(0).elapsed());
        assertEquals(USAGE, kept.get(0).usage());
    }

    @Test
    @DisplayName("Sheriff's fixers count the whole component, and use no model")
    void sheriffsFixersCountEverything() {
        meter.deterministicPassed("sheriff-fixers", errors(error("A", "R1"), error("B", "R1")), TOOK);
        meter.measuredBy(errors(error("B", "R2")));
        assertEquals(PassMetrics.DETERMINISTIC, kept.get(0).kind());
        assertEquals(Map.of("R1", 2), kept.get(0).errorsBefore());
        assertEquals(Map.of("R2", 1), kept.get(0).errorsAfter());
        assertNull(kept.get(0).usage());
    }

    @Test
    @DisplayName("the repair of a test starts from no errors and is measured on the whole component")
    void theTestRepairStartsFromNothing() {
        meter.repairPassed("repair", TOOK, USAGE);
        meter.measuredBy(errors(error("C", "R3")));
        assertEquals(Map.of(), kept.get(0).errorsBefore());
        assertEquals(Map.of("R3", 1), kept.get(0).errorsAfter());
    }

    @Test
    @DisplayName("an analysis that failed measures nothing, and one with no pass held keeps nothing")
    void aFailedAnalysisMeasuresNothing() {
        meter.measuredBy(errors(error("A", "R1")));
        assertEquals(List.of(), kept);
        meter.repairPassed("repair", TOOK, null);
        meter.measuredBy(AnalysisResult.failure("docker is not answering"));
        assertNull(kept.get(0).errorsAfter());
        meter.measuredBy(errors());
        assertEquals(1, kept.size());
    }

    @Test
    @DisplayName("a pass no analysis followed is kept unmeasured, once, also when another pass replaces it")
    void aPassNoAnalysisFollowedIsKeptOnce() {
        meter.unmeasured();
        assertEquals(List.of(), kept);
        meter.repairPassed("first", TOOK, null);
        meter.repairPassed("second", TOOK, null);
        meter.unmeasured();
        meter.unmeasured();
        assertEquals(List.of("first", "second"), kept.stream().map(PassMetrics::label).toList());
        assertNull(kept.get(1).errorsAfter());
    }

    @Test
    @DisplayName("counting by rule: every file, or only some, and nothing for no findings")
    void countsByRule() {
        List<SheriffFinding> findings = List.of(error("A", "R1"), error("A", "R1"), error("B", "R2"));
        assertEquals(Map.of("R1", 2, "R2", 1), PassMeter.countByRule(findings, null));
        assertEquals(Map.of("R2", 1), PassMeter.countByRule(findings, Set.of("B")));
        assertEquals(Map.of(), PassMeter.countByRule(List.of(), Set.of("A")));
    }

    @Test
    @DisplayName("the run's metrics see every analysis, what a pass is given, and the end")
    void theRunsMetricsSeeTheWholeRun() {
        RecordingMetrics run = new RecordingMetrics();
        PassMeter measured = new PassMeter(run);
        measured.deterministicPassed("sheriff-fixers", errors(error("A", "R1"), error("B", "R1")), TOOK);
        measured.measuredBy(errors(error("A", "R1")));
        measured.given("iteration-1", Set.of("A"), 1);
        measured.modelPassed("iteration-1", errors(error("A", "R1")), Set.of("A"), TOOK, USAGE);
        measured.finished(new RunSummary(false, StopReason.FIXER_FAILED, 1, 3, false, List.of(), "", null));
        assertEquals(List.of("analysis 2", "analysis 1", "pass sheriff-fixers", "given iteration-1 [A] 1",
                "pass iteration-1", "finished fixer_failed"), run.events());
        assertNull(run.passes().get(1).errorsAfter());
    }
}
