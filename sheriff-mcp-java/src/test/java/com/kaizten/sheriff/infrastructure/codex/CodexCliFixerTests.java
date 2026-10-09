package com.kaizten.sheriff.infrastructure.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.valueobject.FixRequest;
import com.kaizten.sheriff.domain.valueobject.FixResult;
import com.kaizten.sheriff.infrastructure.claude.PromptLog;
import com.kaizten.sheriff.infrastructure.process.FakeProcessRunner;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the Codex command line backend.
 *
 * <p>What is worth asserting is the invocation, because that is the part
 * nobody can check by reading: the exact argument list, and that the prompt
 * goes last rather than being interpolated into a flag. The Codex binary is
 * not installed on the machine this was written on, which is precisely why
 * the command is configurable and why these assert against recorded values.
 */
class CodexCliFixerTests {

    private static final List<String> DEFAULT_COMMAND = List.of("codex", "exec", "--sandbox", "workspace-write");
    private static final FixRequest REQUEST = FixRequest.unrestricted("arregla esto", "iteration-1");
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static CodexCliFixer fixer(FakeProcessRunner runner, List<String> command) {
        return new CodexCliFixer(runner, new PromptLog(null), Path.of("/repo"), command, TIMEOUT);
    }

    @Test
    @DisplayName("the prompt goes in on standard input, never as an argument capped at 128 KB")
    void sendsThePromptOnStandardInput() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "done", ""));
        fixer(runner, DEFAULT_COMMAND).fix(REQUEST);
        assertEquals(List.of("codex", "exec", "--sandbox", "workspace-write"), runner.command());
        assertEquals("arregla esto", runner.input());
    }

    @Test
    @DisplayName("the whole command is configurable, because Codex's flags have moved between releases")
    void honoursAConfiguredCommand() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "done", ""));
        fixer(runner, List.of("codex", "--yolo")).fix(REQUEST);
        assertEquals(List.of("codex", "--yolo"), runner.command());
    }

    @Test
    @DisplayName("children are marked so the repository's own gate stands aside for them")
    void marksItsChildProcesses() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "done", ""));
        fixer(runner, DEFAULT_COMMAND).fix(REQUEST);
        assertEquals("1", runner.environment().get("SHERIFF_AGENT_RUNNING"));
    }

    @Test
    @DisplayName("a process that never started reports why, rather than an exit code nobody produced")
    void reportsAnInvocationThatCouldNotRun() {
        FixResult result = fixer(FakeProcessRunner.always(ProcessOutcome.unavailable("codex not found")),
                DEFAULT_COMMAND).fix(REQUEST);
        assertFalse(result.ok());
        assertTrue(result.output().contains("codex not found"), result.output());
    }

    @Test
    @DisplayName("a bad exit prefers whatever the CLI itself said over the number")
    void prefersTheReportedReason() {
        FixResult result = fixer(FakeProcessRunner.always(ProcessOutcome.completed(1, "", "sandbox denied")),
                DEFAULT_COMMAND).fix(REQUEST);
        assertFalse(result.ok());
        assertTrue(result.output().contains("sandbox denied"), result.output());
    }

    @Test
    @DisplayName("a clean run hands back what the CLI printed")
    void passesTheOutputThrough() {
        FixResult result = fixer(FakeProcessRunner.always(ProcessOutcome.completed(0, "edited 2 files", "")),
                DEFAULT_COMMAND).fix(REQUEST);
        assertTrue(result.ok());
        assertTrue(result.output().contains("edited 2 files"), result.output());
    }

    @Test
    @DisplayName("with a component, Codex works inside it: in the repository, and able to write only there")
    void worksInsideTheComponent() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "done", ""));
        new CodexCliFixer(runner, new PromptLog(null), Path.of("/work"), "app", DEFAULT_COMMAND, TIMEOUT).fix(REQUEST);
        assertEquals(List.of("codex", "exec", "--sandbox", "workspace-write", "--cd", "app"), runner.command());
        assertTrue(runner.input().startsWith("You are working inside the directory `app`"));
        assertTrue(runner.input().endsWith("arregla esto"));
    }
}
