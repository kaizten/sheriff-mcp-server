package com.kaizten.sheriff.maven;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.infrastructure.docker.DeterministicFixReport;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugin.logging.SystemStreamLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the fix goal does with a repair report. The repairs themselves are
 * Sheriff's and are tested in the agent; what is new here is that a repair
 * that never ran is not reported as a repair that found nothing to do, and
 * that a repair which left errors behind fails the goal.
 */
class FixMojoTests {

    /** A module directory that looks like it has code. */
    private static File moduleWithSources(Path root) throws Exception {
        Path module = root.resolve("componente");
        Files.createDirectories(module.resolve("src"));
        return module.toFile();
    }

    /** A FixMojo answering with a canned report, and a clean module after it. */
    private static FixMojo mojoAnswering(File module, DeterministicFixReport canned) {
        return mojoAnswering(module, canned, AnalysisResult.of(List.of()));
    }

    /** A FixMojo answering with a canned report and a canned analysis after it. */
    private static FixMojo mojoAnswering(File module, DeterministicFixReport canned, AnalysisResult after) {
        FixMojo mojo = new FixMojo() {
            @Override
            protected void ensureImage() {
            }

            @Override
            protected DeterministicFixReport repair() {
                return canned;
            }

            @Override
            protected AnalysisResult analyze() {
                return after;
            }
        };
        mojo.setLog(new SystemStreamLog());
        mojo.basedir = module;
        mojo.profile = "JAVA";
        mojo.image = "kaizten/sheriff:latest";
        mojo.timeoutSeconds = 300;
        return mojo;
    }

    @Test
    @DisplayName("repairs that were applied are reported and the goal succeeds")
    void appliedRepairsSucceed(@TempDir Path root) throws Exception {
        FixMojo mojo = mojoAnswering(moduleWithSources(root),
                DeterministicFixReport.applied(12, List.of("SortedImport", "EmptyLinesInMethod")));
        assertDoesNotThrow(mojo::execute);
    }

    @Test
    @DisplayName("nothing to repair is a success, not a failure")
    void nothingToRepairSucceeds(@TempDir Path root) throws Exception {
        FixMojo mojo = mojoAnswering(moduleWithSources(root), DeterministicFixReport.nothingToDo(7));
        assertDoesNotThrow(mojo::execute);
    }

    @Test
    @DisplayName("a repair that could not run at all fails, rather than looking like nothing needed doing")
    void unavailableFails(@TempDir Path root) throws Exception {
        FixMojo mojo = mojoAnswering(moduleWithSources(root), DeterministicFixReport.unavailable("no docker"));
        MojoExecutionException thrown = assertThrows(MojoExecutionException.class, mojo::execute);
        assertTrue(thrown.getMessage().contains("no docker"), thrown.getMessage());
    }

    @Test
    @DisplayName("sheriff.skip stops it before anything is rewritten")
    void skipFlagStopsIt(@TempDir Path root) throws Exception {
        FixMojo mojo = mojoAnswering(moduleWithSources(root), DeterministicFixReport.unavailable("no docker"));
        mojo.skip = true;
        assertDoesNotThrow(mojo::execute);
    }

    @Test
    @DisplayName("an aggregator module has nothing to rewrite")
    void aggregatorIsSkipped(@TempDir Path root) throws Exception {
        Path module = root.resolve("agregador");
        Files.createDirectories(module);
        FixMojo mojo = mojoAnswering(module.toFile(), DeterministicFixReport.unavailable("no docker"));
        assertDoesNotThrow(mojo::execute);
    }

    private static AnalysisResult oneErrorLeft() {
        return AnalysisResult.of(List.of(new SheriffFinding(
                "componente/src/A.java", "Method 'a' has no JavaDoc", "Add one.", "JavaDoc", "ERROR", Map.of())));
    }

    @Test
    @DisplayName("errors the fixers could not repair fail the goal, and are named")
    void remainingErrorsFail(@TempDir Path root) throws Exception {
        FixMojo mojo = mojoAnswering(moduleWithSources(root),
                DeterministicFixReport.applied(3, List.of("SortedImport")), oneErrorLeft());
        MojoFailureException thrown = assertThrows(MojoFailureException.class, mojo::execute);
        assertTrue(thrown.getMessage().contains("1 error(s)"), thrown.getMessage());
    }

    @Test
    @DisplayName("sheriff.failOnRemaining=false reports what is left without failing")
    void remainingErrorsCanBeTolerated(@TempDir Path root) throws Exception {
        FixMojo mojo = mojoAnswering(moduleWithSources(root), DeterministicFixReport.nothingToDo(1), oneErrorLeft());
        mojo.failOnRemaining = false;
        assertDoesNotThrow(mojo::execute);
    }

    @Test
    @DisplayName("an analysis after the repair that could not run is not a clean module")
    void unavailableAfterFails(@TempDir Path root) throws Exception {
        FixMojo mojo = mojoAnswering(moduleWithSources(root), DeterministicFixReport.nothingToDo(0),
                AnalysisResult.failure("docker went away"));
        MojoExecutionException thrown = assertThrows(MojoExecutionException.class, mojo::execute);
        assertTrue(thrown.getMessage().contains("docker went away"), thrown.getMessage());
    }
}
