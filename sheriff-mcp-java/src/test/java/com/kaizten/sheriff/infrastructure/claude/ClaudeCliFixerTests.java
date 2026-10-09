package com.kaizten.sheriff.infrastructure.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.valueobject.FixRequest;
import com.kaizten.sheriff.domain.valueobject.FixResult;
import com.kaizten.sheriff.infrastructure.process.FakeProcessRunner;
import com.kaizten.sheriff.infrastructure.process.Platform;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for the Claude Code CLI fixer. */
class ClaudeCliFixerTests {

    /**
     * {@code /work/app} as the rule names it on this platform: itself on
     * POSIX, {@code /d/work/app} on a Windows runner whose drive is D.
     */
    private static final String WORK_APP =
            Platform.posixForm(Path.of("/work/app").toAbsolutePath().normalize().toString());

    @TempDir
    private Path logs;

    private static final FixRequest REQUEST =
            FixRequest.scoped("fix these things", "iteration-1", Set.of("A.java"));

    private ClaudeCliFixer fixer(FakeProcessRunner runner) {
        return new ClaudeCliFixer(runner, new PromptLog(logs), Path.of("/repo"), Duration.ofSeconds(600));
    }

    @Test
    void invokesTheCliInPrintModeWithEditsAllowedAndStructuredOutput() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "done", ""));
        assertTrue(fixer(runner).fix(REQUEST).ok());
        assertEquals(List.of("claude", "-p", "--permission-mode", "acceptEdits",
                        "--allowedTools", "Read,Edit,Bash(git mv:*)", "--output-format", "json"),
                runner.command());
    }

    @Test
    @DisplayName("with a component, edits are confined to it by an absolute rule: a relative one breaks after a cd")
    void editsAreConfinedToTheComponent() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "done", ""));
        ClaudeCliFixer scoped = new ClaudeCliFixer(
                runner, new PromptLog(logs), Path.of("/work"), "app", Duration.ofSeconds(600));
        assertTrue(scoped.fix(REQUEST).ok());
        assertEquals(List.of("claude", "-p", "--allowedTools", "Read,Edit(/" + WORK_APP + "/**),Bash(git mv:*)",
                        "--output-format", "json"),
                runner.command());
    }

    @Test
    @DisplayName("it may run the project's tests, exactly, without the cd, and the prompt names the command")
    void itMayRunTheProjectsTests() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "done", ""));
        ClaudeCliFixer scoped = new ClaudeCliFixer(runner, new PromptLog(logs), Path.of("/work"), "app",
                "cd 'app' && mvn -q test", Duration.ofSeconds(600));
        assertTrue(scoped.fix(REQUEST).ok());
        assertEquals(List.of("claude", "-p", "--allowedTools",
                        "Read,Edit(/" + WORK_APP + "/**),Bash(git mv:*),Bash(mvn -q test)", "--output-format", "json"),
                runner.command());
        assertTrue(runner.input().contains("`cd 'app' && mvn -q test`"), runner.input());
    }

    @Test
    @DisplayName("a test command the rule syntax cannot carry is not allowed at all, rather than allowed wrongly")
    void aTestCommandWithParenthesesIsLeftOut() {
        assertEquals(",Bash(npm test)", ClaudeCliFixer.testRule("cd \"web app\" && npm test"));
        assertEquals(",Bash(./gradlew test)", ClaudeCliFixer.testRule("./gradlew test"));
        assertEquals("", ClaudeCliFixer.testRule("cd app && (mvn test)"));
        assertEquals("", ClaudeCliFixer.testRule(""));
    }

    @Test
    @DisplayName("the test command a model runs from inside the component, which Antigravity's rules take too")
    void theTestCommandWithoutItsCd() {
        assertEquals("./mvnw test", ClaudeCliFixer.testCommandCore("cd 'spring petclinic' && ./mvnw test"));
        assertEquals("npm test", ClaudeCliFixer.testCommandCore("  npm test  "));
        assertEquals("", ClaudeCliFixer.testCommandCore(""));
        assertEquals("", ClaudeCliFixer.testCommandCore("cd app && (mvn test)"));
    }

    @Test
    @DisplayName("every prompt tells the CLI backend specifically that git mv is available")
    void everyPromptMentionsGitMv() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "done", ""));
        fixer(runner).fix(REQUEST);
        String sentPrompt = runner.input();
        assertTrue(sentPrompt.startsWith("fix these things"));
        assertTrue(sentPrompt.contains("git mv"));
    }


    @Test
    @DisplayName("children are marked so the repository's own gate stands aside for them")
    void marksItsChildProcesses() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "done", ""));
        fixer(runner).fix(REQUEST);
        assertEquals("1", runner.environment().get("SHERIFF_AGENT_RUNNING"));
    }

    @Test
    @DisplayName("Claude Code explains itself on stdout, so reading stderr alone loses the reason")
    void failureReasonPrefersWhateverIsActuallyThere() {
        FixResult onStdout = fixer(FakeProcessRunner.always(
                ProcessOutcome.completed(1, "5-hour limit reached", ""))).fix(REQUEST);
        assertFalse(onStdout.ok());
        assertTrue(onStdout.output().contains("5-hour limit"));
        FixResult onStderr = fixer(FakeProcessRunner.always(
                ProcessOutcome.completed(1, "", "hard failure"))).fix(REQUEST);
        assertTrue(onStderr.output().contains("hard failure"));
        FixResult silent = fixer(FakeProcessRunner.always(ProcessOutcome.completed(7, "", ""))).fix(REQUEST);
        assertTrue(silent.output().contains("7"));
    }

    @Test
    void aMissingCliIsAFailedFixNotACrash() {
        FixResult result = fixer(FakeProcessRunner.always(
                ProcessOutcome.unavailable("'claude' is not installed or not on the PATH."))).fix(REQUEST);
        assertFalse(result.ok());
        assertTrue(result.output().contains("claude"));
    }

    @Test
    void everyInvocationIsLogged() throws IOException {
        fixer(FakeProcessRunner.always(ProcessOutcome.completed(0, "the response", ""))).fix(REQUEST);
        List<Path> written = Files.list(logs).toList();
        assertEquals(1, written.size());
        String content = Files.readString(written.get(0));
        assertTrue(written.get(0).getFileName().toString().endsWith("_iteration-1.txt"));
        assertTrue(content.contains("fix these things"));
        assertTrue(content.contains("the response"));
    }

    @Test
    @DisplayName("losing the audit trail is not a reason to fail a fix that worked")
    void loggingIsBestEffort() {
        ClaudeCliFixer silent = new ClaudeCliFixer(
                FakeProcessRunner.always(ProcessOutcome.completed(0, "done", "")),
                new PromptLog(null), Path.of("/repo"), Duration.ofSeconds(600));
        assertTrue(silent.fix(REQUEST).ok());
    }

    private static final String STRUCTURED_SUCCESS = """
            {"type":"result","subtype":"success","is_error":false,"result":"fixed it",
            "num_turns":3,"total_cost_usd":0.164228,
            "usage":{"input_tokens":2,"output_tokens":4,"cache_creation_input_tokens":40806,
            "cache_read_input_tokens":0}}
            """;

    private static final String STRUCTURED_FAILURE = """
            {"type":"result","subtype":"error_max_turns","is_error":true,
            "result":"reached the turn limit before finishing"}
            """;

    @Test
    @DisplayName("the answer is read off the structured result, not the raw JSON")
    void takesTheAnswerFromTheStructuredResult() {
        FixResult result = fixer(FakeProcessRunner.always(
                ProcessOutcome.completed(0, STRUCTURED_SUCCESS, ""))).fix(REQUEST);
        assertTrue(result.ok());
        assertEquals("fixed it", result.output());
    }

    @Test
    @DisplayName("is_error in the structured result is a failed fix even on exit code 0")
    void isErrorInTheStructuredResultIsAFailure() {
        FixResult result = fixer(FakeProcessRunner.always(
                ProcessOutcome.completed(0, STRUCTURED_FAILURE, ""))).fix(REQUEST);
        assertFalse(result.ok());
        assertTrue(result.output().contains("turn limit"));
    }

    @Test
    @DisplayName("the cost and token counts are logged, not just the answer")
    void logsWhatTheInvocationCost() throws IOException {
        fixer(FakeProcessRunner.always(ProcessOutcome.completed(0, STRUCTURED_SUCCESS, ""))).fix(REQUEST);
        List<Path> written = Files.list(logs).toList();
        String content = Files.readString(written.get(0));
        assertTrue(content.contains("cost_usd=0.164228"));
        assertTrue(content.contains("turns=3"));
        assertTrue(content.contains("input_tokens=2"));
        assertTrue(content.contains("output_tokens=4"));
        assertTrue(content.contains("cache_creation_input_tokens=40806"));
        assertTrue(content.contains("cache_read_input_tokens=0"));
    }

    @Test
    @DisplayName("stdout that is not the structured shape falls back exactly as plain text always did")
    void nonJsonStdoutFallsBackToPlainText() {
        FixResult result = fixer(FakeProcessRunner.always(
                ProcessOutcome.completed(0, "just some plain text, not JSON at all", ""))).fix(REQUEST);
        assertTrue(result.ok());
        assertEquals("just some plain text, not JSON at all", result.output());
    }
}
