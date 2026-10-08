package com.kaizten.sheriff.infrastructure.antigravity;

import com.kaizten.sheriff.domain.port.CodeFixer;
import com.kaizten.sheriff.domain.valueobject.FixRequest;
import com.kaizten.sheriff.domain.valueobject.FixResult;
import com.kaizten.sheriff.infrastructure.claude.PromptLog;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The fixer that hands the work to Google Antigravity's command line,
 * {@code agy}.
 *
 * <p>The same shape as the Claude Code and Codex ones. What differs is how
 * the edits are confined, measured with {@code agy} 1.1.16. Without its
 * interface, every write is refused unless {@code --mode accept-edits} is
 * given, and that mode accepts edits inside the directory it runs in and
 * refuses them outside, as long as {@code allowNonWorkspaceAccess} keeps its
 * default. So it runs inside the component, the confinement Codex gets from
 * {@code --cd}. Under {@code /tmp} it writes anywhere, so that measurement had
 * to be taken in a project elsewhere.
 *
 * <p>A terminal command, a URL or any tool but reading and editing files is
 * refused too, and a refusal ends the session with exit code 0 and nothing
 * on standard output. So each pass runs with a home of its own
 * ({@link AntigravityHome}) whose settings allow the project's test command
 * and {@code git mv}, and the prompt names them as the only ones: on
 * PetClinic, a repair pass that could not run the tests reached for a URL
 * instead and was cut with nothing done. A session cut anyway still keeps the
 * edits it made: it is reported, not failed, since a failed fixer stops the
 * whole run.
 *
 * <p>The prompt goes in on standard input. {@code -p} takes it as an argument,
 * which is capped at 128 KB, and with no {@code -p} and standard input not a
 * terminal, {@code agy} reads it from there.
 */
public final class AntigravityCliFixer implements CodeFixer {

    /**
     * Marks the child process as the agent's own.
     */
    private static final String AGENT_MARKER = "SHERIFF_AGENT_RUNNING";

    /**
     * The value that marker carries.
     */
    private static final String AGENT_MARKER_VALUE = "1";

    /**
     * The flag that sets how long {@code agy} waits for its answer.
     */
    private static final String PRINT_TIMEOUT_FLAG = "--print-timeout";

    /**
     * How that flag's value is written: seconds, with Go's unit.
     */
    private static final String SECONDS = "%ds";

    /**
     * Reported when the CLI ran but ended badly.
     */
    private static final String EXIT_FAILURE = "exit code %d";

    /**
     * Printed once the exchange has been written down.
     */
    private static final String LOGGED_MESSAGE = "  prompt/response logged to %s%n";

    /**
     * What {@code agy} writes when a tool it may not use ended the session.
     */
    private static final String REFUSED_MARKER = "auto-denied";

    /**
     * Printed when the session ended at a refused tool.
     */
    private static final String REFUSED_MESSAGE = "  Antigravity stopped at a tool it may not use here: %s%n";

    /**
     * What the prompt says first when there is no component to confine it to.
     */
    private static final String NO_COMPONENT = "";

    /**
     * What the prompt says first when Antigravity works inside the component.
     */
    private static final String INSIDE_COMPONENT = """
            You are working inside the directory `%1$s`. Every file path below is written
            from its parent, so `%1$s/src/X.java` is `src/X.java` for you. You can only
            write inside `%1$s`.

            """;

    /**
     * What the prompt says when no command is granted.
     */
    private static final String NO_COMMANDS = """
            Use only the tools that read and edit files: no terminal command, not even the build
            or the tests, no URL and no other tool. Anything else is refused, and a refusal ends
            your turn before your edits are done. The tests are run for you after you finish.

            """;

    /**
     * What the prompt says when the tests may be run.
     */
    private static final String TESTS_GRANTED = """
            To check your work, run the project's tests with exactly `%s`, with nothing added:
            no pipe, no redirection.
            """;

    /**
     * What the prompt says about the commands granted, the tests or not.
     */
    private static final String COMMANDS_GRANTED = """
            To rename a file, use `git mv <old path> <new path>`. Use no other command, no URL and
            no tool but those that read and edit files: anything else is refused, and a refusal
            ends your turn before your edits are done.

            """;

    /**
     * What is said about the tests when there are none to run.
     */
    private static final String NO_TESTS = "";

    /**
     * Reported when a pass's home could not be written.
     */
    private static final String HOME_FAILURE = "could not prepare Antigravity's settings for this pass: %s";

    /**
     * The component the edits are confined to, empty for none.
     */
    private final String component;

    /**
     * How to run the process.
     */
    private final ProcessRunner processes;

    /**
     * Where the exchange is recorded.
     */
    private final PromptLog promptLog;

    /**
     * The directory Sheriff mounts.
     */
    private final Path repositoryRoot;

    /**
     * The command that invokes {@code agy}, prompt and time limit excluded.
     */
    private final List<String> baseCommand;

    /**
     * How long one invocation is given.
     */
    private final Duration timeout;

    /**
     * The home each pass runs with, and the commands it grants.
     */
    private final AntigravityHome home;

    /**
     * Wires the fixer to one component, which Antigravity works inside.
     *
     * @param processes how to run the CLI
     * @param promptLog where to record what was asked
     * @param repositoryRoot the directory Sheriff mounts
     * @param component the component Antigravity works inside, empty to work
     *     in the mount itself
     * @param baseCommand the command that invokes {@code agy}, prompt and
     *     time limit excluded
     * @param timeout how long one invocation is given
     * @param home the home each pass runs with, and the commands it grants
     */
    public AntigravityCliFixer(
            ProcessRunner processes,
            PromptLog promptLog,
            Path repositoryRoot,
            String component,
            List<String> baseCommand,
            Duration timeout,
            AntigravityHome home) {
        this.component = component;
        this.processes = processes;
        this.promptLog = promptLog;
        this.repositoryRoot = repositoryRoot;
        this.baseCommand = List.copyOf(baseCommand);
        this.timeout = timeout;
        this.home = home;
    }

    /**
     * Runs one fixing session and reports what the CLI made of it.
     *
     * @param request the prompt to hand the CLI, and the label its log gets
     * @return what the session produced, or why it could not run
     */
    @Override
    public FixResult fix(FixRequest request) {
        ProcessOutcome outcome;
        try (AntigravityPass pass = home.open()) {
            Map<String, String> environment = new HashMap<>(pass.environment());
            environment.put(AGENT_MARKER, AGENT_MARKER_VALUE);
            outcome = processes.run(command(), workingDirectory(), environment, timeout, prompt(request));
        } catch (IOException unwritable) {
            return FixResult.failed(String.format(HOME_FAILURE, unwritable.getMessage()));
        }
        if (!outcome.ran()) {
            return FixResult.failed(outcome.failure());
        }
        Path logged = promptLog.record(
                request.label(), request.prompt(), outcome.standardOutput() + outcome.standardError());
        if (logged != null) {
            System.out.printf(LOGGED_MESSAGE, logged);
        }
        if (!outcome.succeeded()) {
            return FixResult.failed(failureReason(outcome));
        }
        if (outcome.standardError().contains(REFUSED_MARKER)) {
            System.out.printf(REFUSED_MESSAGE, outcome.standardError().strip());
        }
        return FixResult.succeeded(outcome.standardOutput());
    }

    /**
     * The exact command this fixer runs: the configured one, with a time
     * limit of its own as long as this fixer's, since {@code agy} gives up on
     * an answer after five minutes by default.
     *
     * @return the argument list
     */
    List<String> command() {
        List<String> command = new ArrayList<>(baseCommand);
        command.add(PRINT_TIMEOUT_FLAG);
        command.add(String.format(SECONDS, timeout.toSeconds()));
        return List.copyOf(command);
    }

    /**
     * Where {@code agy} runs, which is also all it may write in.
     *
     * @return the component, or the mount when there is none
     */
    Path workingDirectory() {
        return component.isEmpty() ? repositoryRoot : repositoryRoot.resolve(component);
    }

    /**
     * The prompt, told where Antigravity is and which commands it may run.
     *
     * @param request what to fix
     * @return the text to send
     */
    private String prompt(FixRequest request) {
        String where = component.isEmpty() ? NO_COMPONENT : String.format(INSIDE_COMPONENT, component);
        return where + commands() + request.prompt();
    }

    /**
     * What the prompt says about commands.
     *
     * @return the tests and {@code git mv} when this home grants them, and
     *     that none may be run otherwise
     */
    private String commands() {
        if (!home.grantsCommands()) {
            return NO_COMMANDS;
        }
        String tests = home.testCommand().isEmpty() ? NO_TESTS : String.format(TESTS_GRANTED, home.testCommand());
        return tests + COMMANDS_GRANTED;
    }

    /**
     * Why an invocation failed.
     *
     * @param outcome what the process reported
     * @return the reason, preferring whatever the CLI itself said
     */
    private String failureReason(ProcessOutcome outcome) {
        String reported = outcome.standardError().isBlank() ? outcome.standardOutput() : outcome.standardError();
        return reported.isBlank() ? String.format(EXIT_FAILURE, outcome.exitCode()) : reported.strip();
    }
}
