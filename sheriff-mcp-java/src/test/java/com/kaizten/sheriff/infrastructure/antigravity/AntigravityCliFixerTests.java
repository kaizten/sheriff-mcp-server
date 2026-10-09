package com.kaizten.sheriff.infrastructure.antigravity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.valueobject.FixRequest;
import com.kaizten.sheriff.domain.valueobject.FixResult;
import com.kaizten.sheriff.infrastructure.claude.PromptLog;
import com.kaizten.sheriff.infrastructure.process.FakeProcessRunner;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the Antigravity command line backend: the invocation, which is
 * what confines it, as measured with agy 1.1.16.
 */
class AntigravityCliFixerTests {

    private static final List<String> DEFAULT_COMMAND = List.of("agy", "--mode", "accept-edits");
    private static final FixRequest REQUEST = FixRequest.unrestricted("arregla esto", "iteration-1");
    private static final Duration TIMEOUT = Duration.ofSeconds(600);

    @TempDir
    Path userHome;

    private static AntigravityCliFixer fixer(ProcessRunner runner, String component, AntigravityHome home) {
        return new AntigravityCliFixer(runner, new PromptLog(null), Path.of("/work"), component, DEFAULT_COMMAND,
                TIMEOUT, home);
    }

    private static AntigravityCliFixer fixer(ProcessRunner runner, String component) {
        return fixer(runner, component, AntigravityHome.none());
    }

    private static FakeProcessRunner answering(ProcessOutcome outcome) {
        return FakeProcessRunner.always(outcome);
    }

    private AntigravityHome grantingTests() {
        return new AntigravityHome(userHome, Map.of("MAVEN_USER_HOME", userHome.resolve(".m2").toString()),
                "./mvnw test");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException unreadable) {
            return "unreadable: " + unreadable.getMessage();
        }
    }

    @Test
    @DisplayName("it runs inside the component, as accept-edits writes only in the directory agy runs in")
    void runsInsideTheComponent() {
        FakeProcessRunner runner = answering(ProcessOutcome.completed(0, "done", ""));
        fixer(runner, "app").fix(REQUEST);
        assertEquals(Path.of("/work/app"), runner.directory());
    }

    @Test
    void itsWorkingDirectoryIsTheComponentOrTheMount() {
        FakeProcessRunner runner = answering(ProcessOutcome.completed(0, "done", ""));
        assertEquals(Path.of("/work/app"), fixer(runner, "app").workingDirectory());
        assertEquals(Path.of("/work"), fixer(runner, "").workingDirectory());
    }

    @Test
    @DisplayName("agy gives up on an answer after 5 minutes, so it is given the fixer's own time limit")
    void givesAgyTheFixersTimeLimit() {
        FakeProcessRunner runner = answering(ProcessOutcome.completed(0, "done", ""));
        fixer(runner, "app").fix(REQUEST);
        assertEquals(List.of("agy", "--mode", "accept-edits", "--print-timeout", "600s"), runner.command());
    }

    @Test
    @DisplayName("the prompt goes in on standard input: -p takes it as an argument, capped at 128 KB")
    void sendsThePromptOnStandardInput() {
        FakeProcessRunner runner = answering(ProcessOutcome.completed(0, "done", ""));
        fixer(runner, "app").fix(REQUEST);
        assertFalse(runner.command().contains("-p"), runner.command().toString());
        assertTrue(runner.input().startsWith("You are working inside the directory `app`"), runner.input());
        assertTrue(runner.input().endsWith("arregla esto"), runner.input());
    }

    @Test
    @DisplayName("with no command granted, the prompt says to use only the tools that read and edit files")
    void tellsTheModelToRunNothingWhenNothingIsGranted() {
        FakeProcessRunner runner = answering(ProcessOutcome.completed(0, "done", ""));
        fixer(runner, "").fix(REQUEST);
        assertTrue(runner.input().startsWith("Use only the tools that read and edit files"), runner.input());
        assertTrue(runner.input().contains("no URL"), "a URL is what cut PetClinic's repair pass: " + runner.input());
    }

    @Test
    @DisplayName("with the tests granted, the prompt names the exact command, and git mv, as the only ones")
    void namesTheTestCommandItMayRun() {
        FakeProcessRunner runner = answering(ProcessOutcome.completed(0, "done", ""));
        fixer(runner, "app", grantingTests()).fix(REQUEST);
        assertTrue(runner.input().contains("run the project's tests with exactly `./mvnw test`"), runner.input());
        assertTrue(runner.input().contains("no pipe, no redirection"), runner.input());
        assertTrue(runner.input().contains("`git mv <old path> <new path>`"), runner.input());
        assertTrue(runner.input().contains("Use no other command, no URL"), runner.input());
    }

    @Test
    @DisplayName("a pass runs with a home of its own, whose settings allow the tests, and that is gone after")
    void runsWithAHomeOfItsOwn() {
        List<String> seen = new ArrayList<>();
        ProcessRunner runner = (command, directory, environment, timeout) -> {
            Path home = Path.of(environment.get("HOME"));
            seen.add(home.toString());
            seen.add(read(home.resolve(".gemini/antigravity-cli/settings.json")));
            seen.add(environment.get("MAVEN_USER_HOME"));
            seen.add(environment.get("SHERIFF_AGENT_RUNNING"));
            return ProcessOutcome.completed(0, "done", "");
        };
        assertTrue(fixer(runner, "app", grantingTests()).fix(REQUEST).ok());
        assertNotEquals(userHome.toString(), seen.get(0));
        assertTrue(seen.get(1).contains("command(./mvnw test)"), seen.get(1));
        assertTrue(seen.get(1).contains("command(git mv)"), seen.get(1));
        assertEquals(userHome.resolve(".m2").toString(), seen.get(2));
        assertEquals("1", seen.get(3));
        assertFalse(Files.exists(Path.of(seen.get(0))), "the pass's home must not outlive it");
    }

    @Test
    @DisplayName("with no home of its own it runs in the user's, with nothing but the agent's marker added")
    void runsInTheUsersHomeWhenNothingIsGranted() {
        FakeProcessRunner runner = answering(ProcessOutcome.completed(0, "done", ""));
        fixer(runner, "app").fix(REQUEST);
        assertEquals(Map.of("SHERIFF_AGENT_RUNNING", "1"), runner.environment());
    }

    @Test
    @DisplayName("a session cut at a refused tool keeps its edits: reported, not failed, as a failure stops the run")
    void aRefusedToolIsNotAFailure() {
        String refused = "jetski: no output produced -- a tool required the \"command\" permission that headless "
                + "mode cannot prompt for, so it was auto-denied.";
        FixResult result = fixer(answering(ProcessOutcome.completed(0, "", refused)), "app").fix(REQUEST);
        assertTrue(result.ok());
    }

    @Test
    void reportsAnInvocationThatCouldNotRun() {
        FixResult result = fixer(answering(ProcessOutcome.unavailable("agy not found")), "app").fix(REQUEST);
        assertFalse(result.ok());
        assertTrue(result.output().contains("agy not found"), result.output());
    }

    @Test
    void prefersTheReportedReasonOnABadExit() {
        FixResult result = fixer(answering(ProcessOutcome.completed(2, "", "not logged in")), "app").fix(REQUEST);
        assertFalse(result.ok());
        assertTrue(result.output().contains("not logged in"), result.output());
    }

    @Test
    void passesTheOutputThrough() {
        FixResult result = fixer(answering(ProcessOutcome.completed(0, "edited 2 files", "")), "app").fix(REQUEST);
        assertTrue(result.ok());
        assertTrue(result.output().contains("edited 2 files"), result.output());
    }
}
