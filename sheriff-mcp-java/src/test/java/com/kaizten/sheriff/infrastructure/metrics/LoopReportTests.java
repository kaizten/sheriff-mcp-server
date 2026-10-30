package com.kaizten.sheriff.infrastructure.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaizten.sheriff.domain.enumerate.StopReason;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.PassMetrics;
import com.kaizten.sheriff.domain.valueobject.RunSummary;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.domain.valueobject.TokenUsage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the report of a whole run of the repair loop.
 */
class LoopReportTests {

    private static final Duration TOOK = Duration.ofMillis(1500);
    private static final TokenUsage USAGE = new TokenUsage(List.of("claude-sonnet-5-5"), 10, 20, 30, 40, 3, 0.25);
    private static final RunSummary SUCCESS = new RunSummary(true, StopReason.SUCCESS, 2, 4, false, List.of(),
            "sheriff-agent-1", true);
    private static final Map<String, Object> CONTEXT = Map.of("configuration", Map.of("backend", "claude_cli",
            "max_errors_per_batch", 20));

    @TempDir
    private Path folder;

    private static SheriffFinding error(String file, String rule) {
        return new SheriffFinding(file, "a description that must not reach the report", "h", rule,
                SheriffFinding.ERROR, Map.of());
    }

    private static AnalysisResult errors(SheriffFinding... findings) {
        return AnalysisResult.of(List.of(findings));
    }

    private JsonNode read(Path file) throws IOException {
        return new ObjectMapper().readTree(Files.readString(file));
    }

    private LoopReport fullRun(Path file) {
        LoopReport report = new LoopReport(file, CONTEXT, () -> AnalysisResult.failure("not asked"));
        report.analyzed(errors(error("A", "R1"), error("B", "R1"), error("B", "R2")));
        report.analyzed(errors(error("A", "R1"), error("B", "R2")));
        report.record(new PassMetrics("sheriff-fixers", PassMetrics.DETERMINISTIC, Map.of("R1", 2, "R2", 1),
                Map.of("R1", 1, "R2", 1), TOOK, null));
        report.given("iteration-1", Set.of("A"), 1);
        report.analyzed(AnalysisResult.failure("Sheriff did not answer"));
        report.analyzed(errors(error("A", "R2"), error("B", "R2")));
        report.record(new PassMetrics("iteration-1", PassMetrics.MODEL, Map.of("R1", 1), Map.of("R2", 1), TOOK,
                USAGE));
        return report;
    }

    @Test
    @DisplayName("a run's report says what it was asked, what it started from and left, and what each pass did")
    void aWholeRunIsWritten() throws IOException {
        Path file = folder.resolve("reports").resolve("run.json");
        fullRun(file).finished(SUCCESS);
        JsonNode report = read(file);
        assertEquals(LoopReport.SCHEMA, report.get("schema").asInt());
        assertEquals("loop", report.get("kind").asText());
        assertTrue(report.get("ok").asBoolean());
        assertEquals("success", report.get("stopped_reason").asText());
        assertEquals("sheriff-agent-1", report.get("working_branch").asText());
        assertEquals(20, report.get("configuration").get("max_errors_per_batch").asInt());
        assertEquals(3, report.get("initial").get("total").asInt());
        assertEquals(2, report.get("initial").get("files").asInt());
        assertEquals(2, report.get("initial").get("by_rule").get("R1").asInt());
        assertEquals(2, report.get("final").get("total").asInt());
        assertEquals(2, report.get("final").get("by_rule").get("R2").asInt());
        assertTrue(report.get("final").get("after_last_pass").asBoolean());
        JsonNode model = report.get("passes").get(1);
        assertEquals("[\"A\"]", model.get("files").toString());
        assertEquals(1, model.get("given").asInt());
        assertEquals(1, model.get("introduced").get("R2").asInt());
        assertTrue(report.get("passes").get(0).get("files").isNull());
        JsonNode totals = report.get("totals");
        assertEquals(2, totals.get("passes").asInt());
        assertEquals(1, totals.get("model_passes").asInt());
        assertEquals(1, totals.get("repaired_by_sheriff").asInt());
        assertEquals(1, totals.get("repaired_by_model").asInt());
        assertEquals(1, totals.get("introduced_by_model").asInt());
        assertEquals(1500, totals.get("model_ms").asLong());
        assertEquals(10, totals.get("input_tokens").asLong());
        assertEquals(0.25, totals.get("cost_usd").asDouble());
        assertEquals("[\"claude-sonnet-5-5\"]", totals.get("models").toString());
        assertFalse(Files.readString(file).contains("must not reach"));
        assertFalse(Files.exists(folder.resolve("reports").resolve("run.json.partial")));
    }

    @Test
    @DisplayName("a run that stopped before its last pass was measured is analyzed once more")
    void anUnmeasuredLastPassIsAnalyzedAgain() throws IOException {
        Path file = folder.resolve("run.json");
        AtomicInteger looks = new AtomicInteger();
        LoopReport report = new LoopReport(file, CONTEXT, () -> {
            looks.incrementAndGet();
            return errors(error("A", "R3"));
        });
        report.analyzed(errors(error("A", "R1")));
        report.record(new PassMetrics("iteration-1", PassMetrics.MODEL, Map.of("R1", 1), null, TOOK, null));
        report.finished(new RunSummary(false, StopReason.OUT_OF_SCOPE, 1, 3, false, List.of(), "b", null));
        assertEquals(1, looks.get());
        JsonNode left = read(file).get("final");
        assertEquals(1, left.get("by_rule").get("R3").asInt());
        assertTrue(left.get("after_last_pass").asBoolean());
    }

    @Test
    @DisplayName("when that last look fails too, the last analysis is kept and said to come before the last pass")
    void aFailedLastLookKeepsTheLastAnalysis() throws IOException {
        Path file = folder.resolve("run.json");
        LoopReport report = new LoopReport(file, CONTEXT, () -> AnalysisResult.failure("down"));
        report.analyzed(errors(error("A", "R1")));
        report.record(new PassMetrics("iteration-1", PassMetrics.MODEL, Map.of("R1", 1), null, TOOK, null));
        report.finished(new RunSummary(false, StopReason.FIXER_FAILED, 1, 3, false, List.of(), "", null));
        JsonNode left = read(file).get("final");
        assertEquals(1, left.get("total").asInt());
        assertFalse(left.get("after_last_pass").asBoolean());
    }

    @Test
    @DisplayName("a measured run never analyzes again, and one with no analysis at all says so")
    void nothingMoreIsAnalyzedWhenNotNeeded() throws IOException {
        Path file = folder.resolve("run.json");
        AtomicInteger looks = new AtomicInteger();
        new LoopReport(file, CONTEXT, () -> {
            looks.incrementAndGet();
            return errors();
        }).finished(new RunSummary(false, StopReason.INFRA_ERROR, 1, null, false, List.of(), "", null));
        assertEquals(0, looks.get());
        JsonNode report = read(file);
        assertTrue(report.get("initial").isNull());
        assertTrue(report.get("final").isNull());
        assertTrue(report.get("totals").get("cost_usd").isNull());
    }

    @Test
    @DisplayName("a report that cannot be written does not stop the run")
    void anUnwritableReportIsOnlySaid() throws IOException {
        Path blocker = folder.resolve("a-file");
        Files.writeString(blocker, "not a folder");
        fullRun(blocker.resolve("run.json")).finished(SUCCESS);
        assertFalse(Files.exists(blocker.resolve("run.json")));
    }

    @Test
    @DisplayName("what a pass introduced is what each rule gained, measured")
    void introducedIsWhatEachRuleGained() {
        assertEquals(Map.of("R2", 2), LoopReport.introduced(new PassMetrics("p", PassMetrics.MODEL,
                Map.of("R1", 3, "R2", 1), Map.of("R1", 1, "R2", 3), TOOK, null)));
        assertEquals(Map.of(), LoopReport.introduced(new PassMetrics("p", PassMetrics.MODEL, Map.of("R1", 3),
                null, TOOK, null)));
    }
}
