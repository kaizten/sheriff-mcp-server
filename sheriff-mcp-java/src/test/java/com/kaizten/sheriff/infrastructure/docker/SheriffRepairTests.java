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
import java.util.ArrayList;
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
                analyzer, "app");
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
        assertEquals(2, outcome.rounds(), "two rounds of fixers, the second measured by the last analysis");
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
    @DisplayName("a finding withheld is out of the state every fix reads, its rule is not named, and alone it is no work")
    void aWithheldFindingIsLeftToNoFixer() {
        FakeProcessRunner runner = new FakeProcessRunner(List.of(ProcessOutcome.completed(0, TWO, ""), FIXED, FIXED,
                ProcessOutcome.completed(0, ONE, "")));

        RepairOutcome outcome = repair(runner).withholding(finding -> finding.description().equals("no javadoc"))
                .everyFixer(List.of());

        assertEquals(List.of("test", "fix", "fix SortedImport", "test"), steps(runner),
                "the count dropped, but what is left is withheld, so no second round");
        for (int fix : List.of(1, 2)) {
            assertTrue(runner.errorRecords().get(fix).contains("unsorted"), runner.errorRecords().get(fix));
            assertFalse(runner.errorRecords().get(fix).contains("no javadoc"), runner.errorRecords().get(fix));
        }
        assertEquals(2, outcome.before().total(), "what is withheld is still counted, before and after");
        assertEquals(1, outcome.after().total());
    }

    @Test
    @DisplayName("a finding with no rule is repaired by the default set, and no empty rule is named to --fixers")
    void aFindingWithNoRuleNamesNone() {
        String mixed = """
                [ {"file":"/data/app/pom.xml","description":"no description","howToSolve":"",
                   "referenceCode":"","type":"ERROR"},
                  {"file":"/data/app/A.java","description":"unsorted","howToSolve":"sort",
                   "referenceCode":"SortedImport","type":"ERROR"} ]
                """;
        FakeProcessRunner runner = new FakeProcessRunner(List.of(ProcessOutcome.completed(0, mixed, ""), FIXED, FIXED,
                ProcessOutcome.completed(0, mixed, "")));

        repair(runner).everyFixer(List.of());

        assertEquals(List.of("test", "fix", "fix SortedImport", "test"), steps(runner));
    }

    @Test
    @DisplayName("with only withheld findings no fixer runs at all")
    void onlyWithheldFindingsRunNothing() {
        FakeProcessRunner runner = new FakeProcessRunner(List.of(ProcessOutcome.completed(0, ONE, "")));

        RepairOutcome outcome = repair(runner).withholding(finding -> true).everyFixer(List.of());

        assertEquals(List.of("test"), steps(runner));
        assertEquals(0, outcome.rounds());
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

    private static String errors(int count) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < count; index++) {
            json.append(index == 0 ? "" : ",").append("{\"file\":\"/data/app/A.java\",\"description\":\"e")
                    .append(index).append("\",\"howToSolve\":\"h\",\"referenceCode\":\"Rule")
                    .append(index).append("\",\"type\":\"ERROR\"}");
        }
        return json.append("]").toString();
    }

    @Test
    @DisplayName("with no limit the rounds go on while the count drops, past the three there used to be")
    void roundsGoOnWhileTheCountDrops() {
        List<ProcessOutcome> outcomes = new ArrayList<>();
        for (int count = 5; count >= 1; count--) {
            outcomes.addAll(List.of(ProcessOutcome.completed(0, errors(count), ""), FIXED, FIXED));
        }
        outcomes.add(ProcessOutcome.completed(0, errors(1), ""));
        FakeProcessRunner runner = new FakeProcessRunner(outcomes);

        RepairOutcome outcome = repair(runner).everyFixer(List.of());

        assertEquals(5, outcome.rounds());
        assertEquals(5, outcome.before().total());
        assertEquals(1, outcome.after().total());
    }

    @Test
    @DisplayName("a limit stops the rounds there, the last analysis still measuring the last repair")
    void aLimitStopsTheRounds() {
        FakeProcessRunner runner = new FakeProcessRunner(List.of(ProcessOutcome.completed(0, TWO, ""), FIXED, FIXED,
                ProcessOutcome.completed(0, ONE, "")));

        RepairOutcome outcome = repair(runner).withRounds(1).everyFixer(List.of());

        assertEquals(List.of("test", "fix", "fix SortedImport,JavaDocCommentsInMethod", "test"), steps(runner));
        assertEquals(1, outcome.rounds());
        assertEquals(0, new RepairOutcome(outcome.before(), outcome.after(), null, null, null).rounds());
    }
}
