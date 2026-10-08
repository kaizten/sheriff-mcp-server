package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link SheriffRunner} against a {@link ProcessRunner} test double, so the
 * checks that catch what Sheriff's exit code and stdout never say by
 * themselves -- a run that produced no report, a rejected profile -- are
 * verified without Docker.
 */
final class SheriffRunnerTest {

    private static final String IMAGE = "kaizten/sheriff:latest";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @TempDir
    Path repository;

    private SheriffRunner runner(ProcessOutcome... outcomes) {
        return new SheriffRunner(new QueuedProcessRunner(outcomes), IMAGE, repository, TIMEOUT);
    }

    private SheriffRunner runnerWithoutReport(ProcessOutcome... outcomes) {
        return new SheriffRunner(new QueuedProcessRunner(false, outcomes), IMAGE, repository, TIMEOUT);
    }

    @Test
    void parsesFindingsFromAHeaderPlusJsonArray() {
        String stdout = "conversor-temperatura has 1 error(s) under JAVA\n"
                + "[{\"file\":\"/data/Converter.java\",\"description\":\"bad name\","
                + "\"howToSolve\":\"rename it\",\"referenceCode\":\"RULE_A\",\"type\":\"ERROR\"}]";
        SheriffRunner runner = runner(ProcessOutcome.completed(0, stdout, ""));

        AnalysisResult result = runner.test("conversor-temperatura", "JAVA");

        assertEquals(1, result.total());
        assertEquals("Converter.java", result.errors().get(0).file());
    }

    @Test
    void emptyOutputWithNoFreshStateFileIsUnavailable() {
        SheriffRunner runner = runnerWithoutReport(ProcessOutcome.completed(0, "", ""));

        SheriffUnavailableException exception = assertThrows(SheriffUnavailableException.class,
                () -> runner.test("conversor-temperatura", "JAVA"));
        assertTrue(exception.getMessage().contains("did not record the run"));
    }

    @Test
    void emptyOutputWithAStateFileThatDidNotRecordTheRunIsUnavailable() throws IOException {
        Files.writeString(repository.resolve("sheriff_errors.json"),
                "{\"file:/data\": {\"some-other-component\": {\"JAVA\": []}}}");
        SheriffRunner runner = runnerWithoutReport(ProcessOutcome.completed(0, "", ""));

        SheriffUnavailableException exception = assertThrows(SheriffUnavailableException.class,
                () -> runner.test("conversor-temperatura", "JAVA"));

        assertTrue(exception.getMessage().contains("did not record the run"));
    }

    @Test
    void emptyOutputWithTheRunRecordedIsAPass() throws IOException {
        Files.writeString(repository.resolve("sheriff_errors.json"),
                "{\"file:/data\": {\"conversor-temperatura\": {\"JAVA\": []}}}");
        SheriffRunner runner = runner(ProcessOutcome.completed(0, "", ""));

        AnalysisResult result = runner.test("conversor-temperatura", "JAVA");

        assertEquals(0, result.total());
    }

    @Test
    void aRejectedProfileIsReportedInSheriffsOwnWordsRatherThanAsAParseFailure() {
        String stdout = "Usage: Sheriff [options]";
        String stderr = "ERROR. Test 'BOGUS' not valid\n" + stdout;
        SheriffRunner runner = runner(ProcessOutcome.completed(0, stdout, stderr));

        SheriffUnavailableException exception = assertThrows(SheriffUnavailableException.class,
                () -> runner.test("conversor-temperatura", "BOGUS"));

        assertEquals("ERROR. Test 'BOGUS' not valid", exception.getMessage());
    }

    @Test
    void aProcessThatNeverRanIsUnavailable() {
        SheriffRunner runner = runner(ProcessOutcome.unavailable("Docker is not installed or not on the PATH."));

        assertThrows(SheriffUnavailableException.class, () -> runner.test("conversor-temperatura", "JAVA"));
    }

    @Test
    void fixRunsTestThenFixThenTestAgainAndReportsBothCounts() {
        String before = "[{\"file\":\"/data/A.java\",\"description\":\"d\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"RULE_A\",\"type\":\"ERROR\"}]";
        SheriffRunner runner = runner(
                ProcessOutcome.completed(0, before, ""),
                ProcessOutcome.completed(1, "fix report", ""),
                ProcessOutcome.completed(0, "", ""));

        FixRun run = runner.fix("conversor-temperatura", "JAVA", "RULE_A");

        assertEquals(1, run.before().total());
        assertEquals(0, run.after().total());
    }

    @Test
    @DisplayName("with several profiles, sheriff_fix tests and repairs each in turn, then tests them all again")
    void fixGoesProfileByProfile() {
        String base = "[{\"file\":\"/data/A.java\",\"description\":\"d\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"RULE_A\",\"type\":\"ERROR\"}]";
        String layers = "[{\"file\":\"/data/A.java\",\"description\":\"e\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"RULE_B\",\"type\":\"ERROR\"}]";
        SheriffRunner runner = runner(
                ProcessOutcome.completed(0, base, ""),
                ProcessOutcome.completed(1, "fix report", ""),
                ProcessOutcome.completed(1, "fix report", ""),
                ProcessOutcome.completed(0, layers, ""),
                ProcessOutcome.completed(1, "fix report", ""),
                ProcessOutcome.completed(1, "fix report", ""),
                ProcessOutcome.completed(0, "", ""),
                ProcessOutcome.completed(0, "", ""));

        FixRun run = runner.fix("conversor-temperatura", "JAVA,JAVA_HEXAGONAL", "");

        assertEquals(2, run.before().total());
        assertEquals(0, run.after().total());
    }

    @Test
    @DisplayName("a repair with no rule named runs the fixer of every rule found, and again while the count drops")
    void aRepairWithNoRuleRunsEveryFixerUntilItStopsHelping() {
        String two = "[{\"file\":\"/data/A.java\",\"description\":\"d\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"EmptyLinesInMethod\",\"type\":\"ERROR\"},"
                + "{\"file\":\"/data/A.java\",\"description\":\"e\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"JavaLineCommentsChecker\",\"type\":\"ERROR\"}]";
        String one = "[{\"file\":\"/data/A.java\",\"description\":\"e\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"JavaLineCommentsChecker\",\"type\":\"ERROR\"}]";
        QueuedProcessRunner processes = new QueuedProcessRunner(
                ProcessOutcome.completed(0, two, ""), ProcessOutcome.completed(1, "fix report", ""),
                ProcessOutcome.completed(1, "fix report", ""), ProcessOutcome.completed(0, one, ""),
                ProcessOutcome.completed(1, "fix report", ""), ProcessOutcome.completed(1, "fix report", ""),
                ProcessOutcome.completed(0, one, ""));
        FixRun run = new SheriffRunner(processes, IMAGE, repository, TIMEOUT).fix("conversor-temperatura", "JAVA", "");

        assertEquals(2, run.before().total());
        assertEquals(1, run.after().total());
        List<String> named = processes.commands.get(2);
        assertEquals("EmptyLinesInMethod,JavaLineCommentsChecker", named.get(named.indexOf("--fixers") + 1));
        assertEquals(7, processes.commands.size(),
                "each round's analysis measures the one before and feeds its own repair; the third repaired nothing");
    }

    @Test
    @DisplayName("a clean component is analyzed once and given to no fixer")
    void aCleanComponentRunsNoFixer() {
        QueuedProcessRunner processes = new QueuedProcessRunner(ProcessOutcome.completed(0, "", ""));

        FixRun run = new SheriffRunner(processes, IMAGE, repository, TIMEOUT).fix("conversor-temperatura", "JAVA", "");

        assertEquals(0, run.after().total());
        assertEquals(1, processes.commands.size(), processes.commands.toString());
    }

    @Test
    @DisplayName("a fix that never ran is a repair that could not run, not one that found nothing to do")
    void aFixThatNeverRanIsUnavailable() {
        String before = "[{\"file\":\"/data/A.java\",\"description\":\"d\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"RULE_A\",\"type\":\"ERROR\"}]";
        SheriffRunner runner = runner(ProcessOutcome.completed(0, before, ""),
                ProcessOutcome.unavailable("Cannot connect to the Docker daemon"));

        SheriffUnavailableException exception = assertThrows(SheriffUnavailableException.class,
                () -> runner.fix("conversor-temperatura", "JAVA", ""));

        assertTrue(exception.getMessage().contains("Docker daemon"), exception.getMessage());
        assertFalse(Files.exists(repository.resolve("sheriff_errors.json")), "the state was left behind");
    }

    @Test
    void aFixerSheriffCouldNotApplyIsReportedWithItsReason() {
        String before = "[{\"file\":\"/data/A.java\",\"description\":\"d\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"BracesForStatements\",\"type\":\"ERROR\"}]";
        String fixOutput = "Could not apply fixer for error 'BracesForStatements' in file '/data/A.java'\n"
                + "Error: Fixer script does not exist: 'braces-for-statements.jar'\n";
        SheriffRunner runner = runner(
                ProcessOutcome.completed(0, before, ""),
                ProcessOutcome.completed(1, fixOutput, ""),
                ProcessOutcome.completed(0, before, ""));

        FixRun run = runner.fix("conversor-temperatura", "JAVA", "BracesForStatements");

        assertEquals(java.util.List.of("BracesForStatements: Fixer script does not exist: 'braces-for-statements.jar'"),
                run.fixerFailures());
    }

    /**
     * A {@link ProcessRunner} that answers with one canned outcome per call,
     * in the order given -- there is no subprocess here, only what the
     * command line would have produced.
     */
    private static final class QueuedProcessRunner implements ProcessRunner {

        private final Deque<ProcessOutcome> outcomes;
        private final boolean recordsReport;
        private final List<List<String>> commands = new ArrayList<>();

        QueuedProcessRunner(ProcessOutcome... outcomes) {
            this(true, outcomes);
        }

        QueuedProcessRunner(boolean recordsReport, ProcessOutcome... outcomes) {
            this.outcomes = new ArrayDeque<>(List.of(outcomes));
            this.recordsReport = recordsReport;
        }

        @Override
        public ProcessOutcome run(List<String> command, Path workingDirectory, Map<String, String> environment,
                Duration timeout) {
            commands.add(command);
            ProcessOutcome outcome = outcomes.poll();
            int test = command.indexOf("test");
            int profile = command.indexOf("--test");
            int component = command.indexOf("--component");
            if (recordsReport && outcome != null && outcome.succeeded() && test >= 0 && profile >= 0
                    && component >= 0) {
                try {
                    Files.writeString(workingDirectory.resolve("sheriff_errors.json"),
                            "{\"file:/data\":{\"" + command.get(component + 1) + "\":{\""
                                    + command.get(profile + 1) + "\":"
                                    + (outcome.standardOutput().indexOf('[') < 0 ? "[]"
                                            : outcome.standardOutput().substring(outcome.standardOutput().indexOf('[')))
                                    + "}}}");
                } catch (IOException ignored) {
                    // Model Sheriff's side effect only when the test mount can be written.
                }
            }
            return outcome;
        }
    }
}
