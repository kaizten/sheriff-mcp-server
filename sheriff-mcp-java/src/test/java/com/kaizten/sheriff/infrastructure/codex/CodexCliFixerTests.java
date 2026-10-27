package com.kaizten.sheriff.infrastructure.codex;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaizten.sheriff.domain.valueobject.FixRequest;
import com.kaizten.sheriff.domain.valueobject.FixResult;
import com.kaizten.sheriff.domain.valueobject.TokenUsage;
import com.kaizten.sheriff.infrastructure.claude.PromptLog;
import com.kaizten.sheriff.infrastructure.process.FakeProcessRunner;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import java.io.IOException;
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
        assertEquals(List.of("codex", "exec", "--sandbox", "workspace-write", "--json"), runner.command());
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
        assertEquals(List.of("codex", "exec", "--sandbox", "workspace-write", "--json", "--cd", "app"),
                runner.command());
        assertTrue(runner.input().startsWith("You are working inside the directory `app`"));
        assertTrue(runner.input().endsWith("arregla esto"));
    }

    private static final String EVENTS = """
            {"type":"thread.started","thread_id":"t"}
            {"type":"turn.started"}
            {"type":"item.started","item":{"id":"item_0","type":"agent_message","text":"a draft"}}
            {"type":"item.completed","item":{"id":"item_9","type":"command_execution","text":"ls"}}
            {"type":"item.completed","item":{"id":"item_0","type":"agent_message","text":"edited A.java"}}
            {"type":"turn.completed","usage":{"input_tokens":14314,"cached_input_tokens":11520,\
            "cache_write_input_tokens":7,"output_tokens":5,"reasoning_output_tokens":0}}
            {"type":"item.completed","item":{"id":"item_1","type":"agent_message","text":"and B.java"}}
            {"type":"turn.completed","usage":{"input_tokens":100,"cached_input_tokens":0,"output_tokens":10}}
            """;

    @Test
    @DisplayName("a command that already asks for --json is left as it is")
    void aCommandWithJsonIsLeftAlone() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "done", ""));
        fixer(runner, List.of("codex", "exec", "--json", "--model", "o")).fix(REQUEST);
        assertEquals(List.of("codex", "exec", "--json", "--model", "o"), runner.command());
    }

    @Test
    @DisplayName("with --json the answer is the model's messages, and what every turn cost is added up")
    void readsTheAnswerAndTheCostOffTheEvents() {
        FixResult result = fixer(FakeProcessRunner.always(ProcessOutcome.completed(0, EVENTS, "")),
                DEFAULT_COMMAND).fix(REQUEST);
        assertTrue(result.ok());
        assertEquals("edited A.java\nand B.java", result.output());
        assertEquals(new TokenUsage(List.of(), 2894, 15, 11520, 7, 2, null), result.usage());
    }

    @Test
    @DisplayName("the cost is the turns completed, added up, and nothing when none completed")
    void addsUpTheCostOfEveryTurn() throws IOException {
        ObjectMapper json = new ObjectMapper();
        assertNull(CodexCliFixer.usageOf(List.of()));
        assertNull(CodexCliFixer.usageOf(List.of(json.readTree("{\"type\":\"turn.started\"}"))));
        assertEquals(new TokenUsage(List.of(), 0, 3, 5, 0, 1, null), CodexCliFixer.usageOf(List.of(json.readTree(
                "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":5,\"cached_input_tokens\":5,"
                        + "\"output_tokens\":3}}"))));
    }

    @Test
    @DisplayName("a pass that failed after a turn still says what it cost; plain text says nothing")
    void aFailedPassStillSaysWhatItCost() {
        FixResult failed = fixer(FakeProcessRunner.always(ProcessOutcome.completed(1, EVENTS, "quota")),
                DEFAULT_COMMAND).fix(REQUEST);
        assertFalse(failed.ok());
        assertEquals(2, failed.usage().turns());
        FixResult plain = fixer(FakeProcessRunner.always(ProcessOutcome.completed(0, "edited\n{not json", "")),
                DEFAULT_COMMAND).fix(REQUEST);
        assertNull(plain.usage());
        assertEquals("edited\n{not json", plain.output());
        FixResult typeless = fixer(FakeProcessRunner.always(ProcessOutcome.completed(0, "{\"foo\":1}", "")),
                DEFAULT_COMMAND).fix(REQUEST);
        assertEquals("{\"foo\":1}", typeless.output());
    }

    @Test
    @DisplayName("more cached input than input is no input of its own, never a negative count")
    void moreCachedThanInputIsNoInput() throws IOException {
        TokenUsage usage = CodexCliFixer.usageOf(List.of(new ObjectMapper().readTree(
                "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":3,\"cached_input_tokens\":5}}")));
        assertEquals(0, usage.inputTokens());
        assertEquals(5, usage.cacheReadTokens());
    }
}
