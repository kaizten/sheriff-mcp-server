package com.kaizten.sheriff.infrastructure.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the real process runner, against real processes. Mocking the JDK
 * here would test nothing: the failure modes that matter are what actually
 * happens with pipes, deadlines and missing executables.
 */
class SystemProcessRunnerTests {

    private static final Path HERE = Path.of(".");
    private static final Duration GENEROUS = Duration.ofSeconds(30);
    private final SystemProcessRunner runner = new SystemProcessRunner();

    @Test
    void capturesOutputAndExitCode() {
        ProcessOutcome outcome = runner.run(List.of("sh", "-c", "echo hello"), HERE, Map.of(), GENEROUS);
        assertTrue(outcome.succeeded());
        assertEquals("hello\n", outcome.standardOutput());
    }

    @Test
    @DisplayName("a non-zero exit is a completed run, not an unavailable one")
    void separatesFailureFromUnavailability() {
        ProcessOutcome outcome = runner.run(List.of("sh", "-c", "echo bad >&2; exit 3"), HERE, Map.of(), GENEROUS);
        assertTrue(outcome.ran());
        assertFalse(outcome.succeeded());
        assertEquals(3, outcome.exitCode());
        assertEquals("bad\n", outcome.standardError());
    }

    @Test
    @DisplayName("only a missing program is 'not installed'; anything else that stops it starting says what")
    void tellsAMissingProgramFromOneThatCannotStart() {
        ProcessOutcome missing = runner.run(List.of("no-such-program-anywhere"), HERE, Map.of(), GENEROUS);
        assertTrue(missing.failure().contains("not installed"));
        ProcessOutcome notExecutable = runner.run(List.of(System.getProperty("java.io.tmpdir")), HERE, Map.of(),
                GENEROUS);
        assertFalse(notExecutable.ran());
        assertTrue(notExecutable.failure().startsWith("Could not start"), notExecutable.failure());
    }

    @Test
    @DisplayName("input far beyond the 128 KB a single argument may carry reaches the process intact")
    void feedsLargeInputOnStandardInput() {
        String large = "x".repeat(400_000);
        ProcessOutcome outcome = runner.run(List.of("sh", "-c", "wc -c"), HERE, Map.of(), GENEROUS, large);
        assertEquals("400000", outcome.standardOutput().strip());
    }

    @Test
    @DisplayName("with no input, standard input is closed, so a reader sees the end instead of hanging")
    void closesStandardInputWhenThereIsNone() {
        ProcessOutcome outcome = runner.run(List.of("sh", "-c", "cat; echo done"), HERE, Map.of(), GENEROUS);
        assertEquals("done", outcome.standardOutput().strip());
    }

    @Test
    void passesTheEnvironmentThrough() {
        ProcessOutcome outcome = runner.run(
                List.of("sh", "-c", "echo $SHERIFF_PROBE"), HERE, Map.of("SHERIFF_PROBE", "visible"), GENEROUS);
        assertEquals("visible\n", outcome.standardOutput());
    }

    @Test
    @DisplayName("large output does not deadlock -- the reason both streams are drained on their own threads")
    void survivesOutputLargerThanThePipeBuffer() {
        ProcessOutcome outcome = runner.run(
                List.of("sh", "-c", "yes abcdefghijklmnopqrstuvwxyz | head -c 2000000"), HERE, Map.of(), GENEROUS);
        assertTrue(outcome.succeeded());
        assertEquals(2_000_000, outcome.standardOutput().length());
    }

    @Test
    @DisplayName("output on both streams at once cannot block either of them")
    void drainsBothStreamsConcurrently() {
        ProcessOutcome outcome = runner.run(
                List.of("sh", "-c", "yes out | head -c 200000; yes err | head -c 200000 >&2"),
                HERE, Map.of(), GENEROUS);
        assertTrue(outcome.succeeded());
        assertEquals(200_000, outcome.standardOutput().length());
        assertEquals(200_000, outcome.standardError().length());
    }

    @Test
    void killsACommandThatOverrunsItsDeadline() {
        ProcessOutcome outcome = runner.run(List.of("sleep", "30"), HERE, Map.of(), Duration.ofSeconds(1));
        assertEquals(ProcessStatus.TIMED_OUT, outcome.status());
        assertFalse(outcome.ran());
        assertTrue(outcome.failure().contains("did not respond"));
    }

    @Test
    @DisplayName("a missing executable is 'could not run', which is never 'nothing was wrong'")
    void reportsAMissingExecutable() {
        ProcessOutcome outcome = runner.run(List.of("definitely-not-installed-xyz"), HERE, Map.of(), GENEROUS);
        assertEquals(ProcessStatus.UNAVAILABLE, outcome.status());
        assertFalse(outcome.ran());
        assertTrue(outcome.failure().contains("definitely-not-installed-xyz"));
    }
}
