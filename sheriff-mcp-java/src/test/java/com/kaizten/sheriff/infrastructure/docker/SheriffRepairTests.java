package com.kaizten.sheriff.infrastructure.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import com.kaizten.sheriff.infrastructure.process.FakeProcessRunner;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The one sequence every caller repairs with: what it runs, in which order,
 * when it stops, and what it says when it could not run.
 */
class SheriffRepairTests {

    private static final String TWO = """
            [ {"file":"/data/app/A.java","description":"unsorted","howToSolve":"sort",
               "referenceCode":"SortedImport","type":"ERROR"},
              {"file":"/data/app/A.java","description":"no javadoc","howToSolve":"add",
               "referenceCode":"JavaDocCommentsInMethod","type":"ERROR"} ]
            """;
    private static final String ONE = """
            [ {"file":"/data/app/A.java","description":"no javadoc","howToSolve":"add",
               "referenceCode":"JavaDocCommentsInMethod","type":"ERROR"} ]
            """;
    private static final ProcessOutcome FIXED = ProcessOutcome.completed(1, "fix report", "");

    @TempDir
    private Path repository;

    private SheriffRepair repair(FakeProcessRunner runner) {
        Duration timeout = Duration.ofSeconds(300);
        SheriffDockerAnalyzer analyzer = new SheriffDockerAnalyzer(runner, "kaizten/sheriff:latest", "JAVA", "app",
                repository, timeout, false);
        return new SheriffRepair(new SheriffContainer(runner, "kaizten/sheriff:latest", repository, timeout),
                analyzer, "app", repository);
    }

    private static List<String> steps(FakeProcessRunner runner) {
        return runner.commands().stream()
                .map(command -> command.contains("test") ? "test"
                        : command.contains("--fixers") ? "fix " + command.get(command.indexOf("--fixers") + 1)
                        : "fix")
                .toList();
    }

    @Test
    @DisplayName("every fixer: the default set and every rule found, again while the count drops, measured last")
    void everyFixerRepairsInRoundsWhileTheyHelp() {
        FakeProcessRunner runner = new FakeProcessRunner(List.of(ProcessOutcome.completed(0, TWO, ""), FIXED, FIXED,
                ProcessOutcome.completed(0, ONE, ""), FIXED, FIXED, ProcessOutcome.completed(0, ONE, "")));

        RepairOutcome outcome = repair(runner).everyFixer(List.of());

        assertEquals(List.of("test", "fix", "fix SortedImport,JavaDocCommentsInMethod", "test", "fix",
                "fix JavaDocCommentsInMethod", "test"), steps(runner));
        assertEquals(2, outcome.before().total());
        assertEquals(1, outcome.after().total());
        assertEquals(Optional.empty(), outcome.failure());
        assertFalse(Files.exists(repository.resolve("sheriff_errors.json")), "the state was left behind");
    }

    @Test
    @DisplayName("with a catalog, only the rules it knows a fixer for are reported as handed over")
    void everyFixerReportsTheCodesTheCatalogKnows() {
        FakeProcessRunner runner = new FakeProcessRunner(List.of(ProcessOutcome.completed(0, TWO, ""), FIXED, FIXED,
                ProcessOutcome.completed(0, TWO, "")));
        List<SheriffRule> catalog = List.of(
                new SheriffRule("SortedImport", "unsorted", "", "java", "", "java/sort-imports.py", List.of("JAVA")));

        RepairOutcome outcome = repair(runner).everyFixer(catalog);

        assertEquals(List.of("SortedImport"), outcome.codes());
        assertEquals(4, runner.commands().size(), "a round that lowered nothing is not repaired again");
    }

    @Test
    @DisplayName("rules named: only their fixers, once, and the analysis after")
    void onlyRulesRunsTheirFixersOnce() {
        FakeProcessRunner runner = new FakeProcessRunner(List.of(ProcessOutcome.completed(0, TWO, ""), FIXED,
                ProcessOutcome.completed(0, ONE, "")));

        RepairOutcome outcome = repair(runner).onlyRules("SortedImport");

        assertEquals(List.of("test", "fix SortedImport", "test"), steps(runner));
        assertEquals(1, outcome.after().total());
    }

    @Test
    @DisplayName("a fix Sheriff refused with a reason on stderr and nothing on stdout is a failure, with that reason")
    void aRefusedFixIsAFailure() {
        FakeProcessRunner runner = new FakeProcessRunner(List.of(ProcessOutcome.completed(0, TWO, ""),
                ProcessOutcome.completed(1, "", "Invalid fixer list")));

        RepairOutcome outcome = repair(runner).onlyRules("Nonsense");

        assertEquals(Optional.of("Invalid fixer list"), outcome.failure());
        assertTrue(outcome.after().error());
    }

    @Test
    @DisplayName("an outcome built with nothing in its lists has empty lists, never null ones")
    void anOutcomeNeverHoldsNull() {
        AnalysisResult none = AnalysisResult.of(List.of());

        RepairOutcome outcome = new RepairOutcome(none, none, null, null, null);

        assertEquals(List.of(), outcome.codes());
        assertEquals(List.of(), outcome.fixerFailures());
        assertEquals(Optional.empty(), outcome.failure());
    }
}
