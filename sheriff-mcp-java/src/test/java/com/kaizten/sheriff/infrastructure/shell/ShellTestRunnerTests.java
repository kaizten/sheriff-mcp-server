package com.kaizten.sheriff.infrastructure.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.valueobject.VerificationResult;
import com.kaizten.sheriff.infrastructure.process.FakeProcessRunner;
import com.kaizten.sheriff.infrastructure.process.Platform;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for the verification gate's shell adapter. */
class ShellTestRunnerTests {

    private static final String COMMAND = "cd app && mvn -q test";

    private static ShellTestRunner runner(FakeProcessRunner processes, String command) {
        return new ShellTestRunner(processes, command, Path.of("/repo"), Duration.ofSeconds(300));
    }

    @Test
    void runsTheConfiguredCommandThroughAShell() {
        FakeProcessRunner processes = FakeProcessRunner.always(ProcessOutcome.completed(0, "BUILD SUCCESS", ""));
        assertTrue(runner(processes, COMMAND).run().ok());
        assertEquals(Platform.shell(COMMAND), processes.command());
    }

    @Test
    @DisplayName("the whole output is carried, because the repair pass is written from it")
    void carriesBothStreamsOnFailure() {
        VerificationResult result = runner(
                FakeProcessRunner.always(ProcessOutcome.completed(1, "tests run", "symbol not found")), COMMAND).run();
        assertFalse(result.ok());
        assertTrue(result.output().contains("tests run"));
        assertTrue(result.output().contains("symbol not found"));
    }

    @Test
    @DisplayName("a project with no suite has not failed one")
    void anEmptyCommandDisablesTheGate() {
        FakeProcessRunner processes = FakeProcessRunner.always(ProcessOutcome.completed(0, "", ""));
        VerificationResult result = runner(processes, "  ").run();
        assertTrue(result.ok());
        assertFalse(result.ran());
        assertEquals(List.of(), processes.commands());
    }

    @Test
    @DisplayName("a component with no build tool has no tests run, and is never said to have passed them")
    void theNoTestsCommandIsNotRun() {
        FakeProcessRunner processes = FakeProcessRunner.always(ProcessOutcome.completed(0, "", ""));
        VerificationResult result = runner(processes, ShellTestRunner.NO_TESTS_COMMAND).run();
        assertTrue(result.ok());
        assertFalse(result.ran());
        assertEquals(ShellTestRunner.NO_TESTS_REASON, result.output());
        assertEquals(List.of(), processes.commands());
    }

    @Test
    void testsThatRanSaySo() {
        FakeProcessRunner processes = FakeProcessRunner.always(ProcessOutcome.completed(0, "BUILD SUCCESS", ""));
        assertTrue(runner(processes, COMMAND).run().ran());
    }

    @Test
    void aShellThatCannotRunIsAFailedVerification() {
        VerificationResult result =
                runner(FakeProcessRunner.always(ProcessOutcome.timedOut("did not respond within 300s.")), COMMAND).run();
        assertFalse(result.ok());
        assertTrue(result.output().contains("did not respond"));
    }
}
