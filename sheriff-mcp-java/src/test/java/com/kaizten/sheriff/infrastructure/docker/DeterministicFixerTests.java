package com.kaizten.sheriff.infrastructure.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.port.RuleCatalog;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import com.kaizten.sheriff.infrastructure.process.FakeProcessRunner;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the pass that lets Sheriff repair what it can before any model is
 * asked for anything.
 *
 * <p>Most of these pin a sequence that is not obvious and is documented
 * nowhere: {@code fix} reads the findings file an analysis leaves behind, one
 * reference code per invocation, and the state must be deleted afterwards or
 * the next analysis reports the component clean when it is not.
 */
class DeterministicFixerTests {

    private static final String OUTPUT = """
            Execution results:
            \tErrors: 2
            [ {"file":"/data/app/A.java","description":"unsorted","howToSolve":"sort",
               "referenceCode":"SortedImport","type":"ERROR"},
              {"file":"/data/app/A.java","description":"no javadoc","howToSolve":"add",
               "referenceCode":"JavaDocCommentsInMethod","type":"ERROR"} ]
            """;

    private static final RuleCatalog CATALOG = () -> List.of(
            new SheriffRule("SortedImport", "unsorted", "", "java", "", "java/sort-imports.py", List.of("JAVA")),
            new SheriffRule("JavaDocCommentsInMethod", "no javadoc", "", "java", "", "", List.of("JAVA")));

    @TempDir
    private Path repository;

    private DeterministicFixer fixer(FakeProcessRunner runner, RuleCatalog catalog) {
        SheriffDockerAnalyzer stateKeeping = new SheriffDockerAnalyzer(
                runner, "kaizten/sheriff:latest", "JAVA", "app", repository, Duration.ofSeconds(300), false);
        return new DeterministicFixer(runner, catalog, stateKeeping, "kaizten/sheriff:latest",
                "app", repository, Duration.ofSeconds(300));
    }

    @Test
    @DisplayName("every fixable code goes to Sheriff in one --fixers list, one container instead of one per code")
    void asksForEveryCodeInOneCall() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, OUTPUT, ""));
        DeterministicFixReport report = fixer(runner, CATALOG).run();
        assertTrue(report.ran());
        assertEquals(List.of("SortedImport"), report.codes());
        List<List<String>> named = runner.commands().stream().filter(command -> command.contains("--fixers")).toList();
        assertEquals(1, named.size(), "a second round whose analysis did not drop repairs nothing");
        assertEquals("SortedImport,JavaDocCommentsInMethod", named.get(0).get(named.get(0).indexOf("--fixers") + 1),
                "every rule found, as sheriff_fix names them: a rule with no fixer in the list is ignored");
    }

    @Test
    @DisplayName("a plain fix -f runs first, because it applies some fixers the codes do not")
    void runsThePlainFixFirst() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, OUTPUT, ""));
        fixer(runner, CATALOG).run();
        List<String> plainFix = runner.commands().get(1);
        assertTrue(plainFix.contains("fix"));
        assertTrue(plainFix.contains("-f"));
        assertFalse(plainFix.contains("--fixers"));
    }

    @Test
    @DisplayName("the state is deleted afterwards, or the next analysis reports a false clean")
    void cleansUpSheriffsStateWhenItIsDone() throws IOException {
        for (String name : SheriffDockerAnalyzer.STATE_FILES) {
            Files.writeString(repository.resolve(name), "{}");
        }
        fixer(FakeProcessRunner.always(ProcessOutcome.completed(0, OUTPUT, "")), CATALOG).run();
        for (String name : SheriffDockerAnalyzer.STATE_FILES) {
            assertFalse(Files.exists(repository.resolve(name)), name + " was left behind");
        }
    }

    @Test
    @DisplayName("nothing fixable means no Docker run wasted on it")
    void doesNothingWhenNoFindingHasAFixer() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, OUTPUT, ""));
        RuleCatalog noFixers = () -> List.of(
                new SheriffRule("SomethingElse", "d", "", "java", "", "", List.of(), ""));
        DeterministicFixReport report = fixer(runner, noFixers).run();
        assertTrue(report.ran());
        assertFalse(report.attemptedAnything());
        assertEquals(1, runner.commands().size(), "only the analysis should have run");
    }

    @Test
    void reportsWhenSheriffCannotRunAtAll() {
        DeterministicFixReport report =
                fixer(FakeProcessRunner.always(ProcessOutcome.unavailable("no docker")), CATALOG).run();
        assertFalse(report.ran());
        assertTrue(report.failure().contains("docker"));
    }

    @Test
    @DisplayName("with no catalog the default fixers still run, and the report says only those did")
    void withoutACatalogTheDefaultsStillRun() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, OUTPUT, ""));
        DeterministicFixReport report = fixer(runner, List::of).run();
        assertTrue(report.ran());
        assertTrue(report.withoutCatalog());
        assertTrue(runner.commands().stream().anyMatch(command -> command.contains("fix")),
                "the plain fix -f needs no catalog, and skipping it repaired nothing that could be repaired");
        assertTrue(report.describe().contains("--extract-rules"));
    }

    @Test
    @DisplayName("with several profiles, each is analyzed and repaired in turn, since fix reads the last test's state")
    void repairsProfileByProfile() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, OUTPUT, ""));
        SheriffDockerAnalyzer stateKeeping = new SheriffDockerAnalyzer(runner, "kaizten/sheriff:latest",
                "JAVA,JAVA_HEXAGONAL", "app", repository, Duration.ofSeconds(300), false);
        DeterministicFixReport report = new DeterministicFixer(runner, CATALOG, stateKeeping,
                "kaizten/sheriff:latest", "app", repository, Duration.ofSeconds(300)).run();
        List<String> steps = runner.commands().stream()
                .map(command -> command.contains("test") ? "test " + command.get(command.indexOf("--test") + 1)
                        : command.contains("--fixers") ? "fix codes" : "fix")
                .toList();
        assertEquals(List.of("test JAVA", "fix", "fix codes", "test JAVA_HEXAGONAL", "fix", "fix codes",
                "test JAVA", "test JAVA_HEXAGONAL"), steps, "a second round analyzes, and repairs only what dropped");
        assertEquals(2, report.errorsBefore());
        assertEquals(List.of("SortedImport"), report.codes());
    }
}
