package com.kaizten.sheriff.infrastructure.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for runs of the loop compared from their reports.
 */
class ReportComparisonTests {

    @TempDir
    private Path folder;

    private static SheriffFinding error(String file, String rule) {
        return new SheriffFinding(file, "d", "h", rule, SheriffFinding.ERROR, Map.of());
    }

    /**
     * Writes one run's report: four errors to start with, Sheriff's fixers
     * repairing one, and a model's pass repairing {@code repaired} of the
     * rest at a cost.
     */
    private Path run(String name, String backend, int maxErrors, int repaired, long tokens, boolean ok) {
        Map<String, Object> knobs = new HashMap<>();
        knobs.put("backend", backend);
        knobs.put("model", null);
        knobs.put("batch_by", "file");
        knobs.put("max_files_per_batch", 5);
        knobs.put("max_errors_per_batch", maxErrors);
        Path file = folder.resolve(name);
        LoopReport report = new LoopReport(file, Map.of("configuration", knobs, "project",
                Map.of("component", "app", "commit", "0123456789abcdef")), () -> AnalysisResult.failure("no"));
        report.analyzed(AnalysisResult.of(List.of(error("A", "R1"), error("A", "R1"), error("B", "R2"),
                error("B", "R3"))));
        report.record(new PassMetrics("sheriff-fixers", PassMetrics.DETERMINISTIC, Map.of("R1", 2, "R2", 1, "R3",
                1), Map.of("R1", 2, "R2", 1), Duration.ofSeconds(1), null));
        report.given("iteration-1", Set.of("A"), 2);
        report.record(new PassMetrics("iteration-1", PassMetrics.MODEL, Map.of("R1", 2),
                Map.of("R1", 2 - repaired), Duration.ofSeconds(10),
                new TokenUsage(List.of("claude-sonnet-5-5"), tokens, 0, 0, 0, 1, 0.5)));
        report.analyzed(AnalysisResult.of(repaired == 2 ? List.of(error("B", "R2"))
                : List.of(error("A", "R1"), error("B", "R2"))));
        report.finished(new RunSummary(ok, ok ? StopReason.SUCCESS : StopReason.ITERATIONS_EXHAUSTED, 2, 3, false,
                List.of(), "", ok));
        return file;
    }

    @Test
    @DisplayName("runs of one configuration are one row, their figures the mean, their cost per repair the totals'")
    void runsOfAConfigurationAreAveraged() throws IOException {
        run("a.json", "claude_cli", 0, 2, 1000, true);
        run("b.json", "claude_cli", 0, 1, 2000, false);
        run("c.json", "codex_cli", 20, 2, 500, true);
        Files.writeString(folder.resolve("other.json"), "{\"kind\": \"session\", \"schema\": 1}");
        Files.writeString(folder.resolve("broken.json"), "not json");
        ReportComparison comparison = ReportComparison.of(List.of(folder));
        assertEquals(3, comparison.runs());
        String page = comparison.markdown(folder.toString());
        assertTrue(page.startsWith("2 file(s) passed over"), page);
        assertTrue(page.contains("3 run(s) in 2 configuration(s)"), page);
        assertTrue(page.contains("| app@0123456 | claude_cli | claude-sonnet-5-5 | file:5 | 2 | 1 | 1 | 4.0 | 1.5 | "
                + "1.0 | 1.5 | 0.0 | 1.0 | 10.0 | 1500 | 0.5000 | 1000 | 6.7 |"), page);
        assertTrue(page.contains("| codex_cli | claude-sonnet-5-5 | file:5:20 | 1 | 1 | 1 |"), page);
        assertTrue(page.contains("| app@0123456 | claude_cli | claude-sonnet-5-5 | file:5 | R1 | 2.0 | 0.0 | 1.5 | "
                + "0.0 | 0.5 |"), page);
        assertTrue(page.contains("| b.json | false | iterations_exhausted |"), page);
    }

    @Test
    @DisplayName("the tables go to CSV files too, a cell with a comma quoted")
    void theTablesGoToCsv() throws IOException {
        run("a.json", "claude_cli", 0, 2, 1000, true);
        Path csv = folder.resolve("csv");
        ReportComparison.of(List.of(folder.resolve("a.json"))).writeCsv(csv);
        for (String table : List.of("configurations.csv", "runs.csv", "rules.csv", "passes.csv")) {
            assertTrue(Files.readString(csv.resolve(table)).startsWith("project,backend,model,strategy,"), table);
        }
        List<String> passes = Files.readAllLines(csv.resolve("passes.csv"));
        assertEquals(3, passes.size());
        assertTrue(passes.get(2).endsWith("iteration-1,model,10000,1,2,2,0,1000"), passes.get(2));
    }

    @Test
    @DisplayName("runs of a project with no tests leave the tests empty, not failed")
    void noTestsIsNotFailedTests() throws IOException {
        Path file = folder.resolve("untested.json");
        LoopReport report = new LoopReport(file, Map.of(), () -> AnalysisResult.failure("no"));
        report.finished(new RunSummary(true, StopReason.SUCCESS, 1, 3, false, List.of(), "", null));
        String page = ReportComparison.of(List.of(file)).markdown(folder.toString());
        assertTrue(page.contains("| 1 | 1 |  | "), page);
        assertTrue(page.contains("| untested.json | true | success |  | "), page);
    }

    @Test
    @DisplayName("nothing to compare says so")
    void nothingToCompareSaysSo() throws IOException {
        ReportComparison comparison = ReportComparison.of(List.of(folder));
        assertEquals(0, comparison.runs());
        assertTrue(comparison.markdown("here").contains("No loop report found in here."));
    }

    @Test
    @DisplayName("the model is the one asked for, or else the ones that answered, or else the backend's own")
    void theModelOfARun() {
        ObjectMapper json = new ObjectMapper();
        assertEquals(List.of("app", "local", "qwen2.5-coder", "rule:0"), ReportComparison.key(json.valueToTree(
                Map.of("project", Map.of("component", "app"), "configuration", Map.of("backend", "local", "model",
                        "qwen2.5-coder", "batch_by", "rule", "max_files_per_batch", 0, "max_errors_per_batch", 0)))));
        assertEquals("(its default)", ReportComparison.key(json.valueToTree(Map.of("configuration",
                Map.of("backend", "codex_cli")))).get(2));
        assertFalse(ReportComparison.key(json.valueToTree(Map.of("project", Map.of("component", "x", "commit",
                "abc")))).get(0).contains("@"), "a commit too short to be one is left out");
    }
}
