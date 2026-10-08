package com.kaizten.sheriff.infrastructure.codex;

import com.kaizten.sheriff.domain.port.CodeFixer;
import com.kaizten.sheriff.domain.valueobject.FixRequest;
import com.kaizten.sheriff.domain.valueobject.FixResult;
import com.kaizten.sheriff.infrastructure.claude.PromptLog;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The fixer that hands the work to OpenAI's Codex command line.
 *
 * <p>The same shape as the Claude Code one and for the same reason: a coding
 * CLI already knows how to read a repository, edit files and stop, so the
 * agent does not have to carry a second implementation of any of that. What
 * it carries instead is the loop around it, which is the part that is the
 * same whichever CLI answers.
 *
 * <p><strong>The command is configurable as a whole</strong>, through
 * {@code CODEX_COMMAND}, and that is deliberate rather than generous. Codex's
 * non-interactive flags have moved between releases, so the default here is a
 * best-known form and not a promise; when it turns out to be wrong, the fix is
 * a variable rather than a rebuild.
 *
 * <p>Like its sibling, children are marked with an environment variable, so
 * the repository's own gate hook stands aside for the tool that exists to
 * clear the errors it guards.
 */
public final class CodexCliFixer implements CodeFixer {

    /**
     * Marks the child process as the agent's own.
     */
    private static final String AGENT_MARKER = "SHERIFF_AGENT_RUNNING";

    /**
     * The value that marker carries.
     */
    private static final String AGENT_MARKER_VALUE = "1";

    /**
     * Reported when the CLI ran but ended badly.
     */
    private static final String EXIT_FAILURE = "exit code %d";

    /**
     * Printed once the exchange has been written down.
     */
    private static final String LOGGED_MESSAGE = "  prompt/response logged to %s%n";

    /**
     * The flag that sets the directory Codex works in.
     */
    private static final String DIRECTORY_FLAG = "--cd";

    /**
     * What the prompt says first when Codex works inside the component.
     */
    private static final String INSIDE_COMPONENT = """
            You are working inside the directory `%1$s`. Every file path below is written
            from its parent, so `%1$s/src/X.java` is `src/X.java` for you. You can only
            write inside `%1$s`.

            """;

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
     * The directory the CLI runs in.
     */
    private final Path repositoryRoot;

    /**
     * The command that invokes Codex, prompt excluded.
     */
    private final List<String> baseCommand;

    /**
     * How long one invocation is given.
     */
    private final Duration timeout;

    /**
     * Wires the fixer to a repository.
     *
     * @param processes how to run the CLI
     * @param promptLog where to record what was asked
     * @param repositoryRoot the directory the CLI runs in
     * @param baseCommand the command that invokes Codex, prompt excluded
     * @param timeout how long one invocation is given
     */
    public CodexCliFixer(
            ProcessRunner processes,
            PromptLog promptLog,
            Path repositoryRoot,
            List<String> baseCommand,
            Duration timeout) {
        this(processes, promptLog, repositoryRoot, "", baseCommand, timeout);
    }

    /**
     * Wires the fixer to one component, which Codex works inside.
     *
     * <p>Codex's {@code workspace-write} sandbox lets it write in the
     * directory it runs in and nowhere else, and it refuses to run at all
     * outside a git repository. The directory Sheriff mounts is neither
     * confined enough nor, for a repository that is one module, a repository:
     * it is the parent. Run inside the component, Codex is in the repository
     * and can write only there, the same confinement the Claude backend gets
     * from its permission rule.
     *
     * @param processes how to run the CLI
     * @param promptLog where to record what was asked
     * @param repositoryRoot the directory Sheriff mounts
     * @param component the component Codex works inside, empty to work in
     *     the mount itself
     * @param baseCommand the command that invokes Codex, prompt excluded
     * @param timeout how long one invocation is given
     */
    public CodexCliFixer(
            ProcessRunner processes,
            PromptLog promptLog,
            Path repositoryRoot,
            String component,
            List<String> baseCommand,
            Duration timeout) {
        this.component = component;
        this.processes = processes;
        this.promptLog = promptLog;
        this.repositoryRoot = repositoryRoot;
        this.baseCommand = List.copyOf(baseCommand);
        this.timeout = timeout;
    }

    /**
     * Runs one fixing session and reports what the CLI made of it.
     *
     * @param request the prompt to hand the CLI, and the label its log gets
     * @return what the session produced, or why it could not run
     */
    @Override
    public FixResult fix(FixRequest request) {
        ProcessOutcome outcome = processes.run(
                command(), repositoryRoot, Map.of(AGENT_MARKER, AGENT_MARKER_VALUE), timeout, prompt(request));
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
        return FixResult.succeeded(outcome.standardOutput());
    }

    /**
     * The exact command this fixer runs. The prompt is not part of it:
     * {@code codex exec} reads its instructions from standard input when it
     * is given none as an argument, and an argument is capped at 128 KB.
     *
     * @return the argument list
     */
    List<String> command() {
        if (component.isEmpty()) {
            return List.copyOf(baseCommand);
        }
        List<String> command = new ArrayList<>(baseCommand);
        command.add(DIRECTORY_FLAG);
        command.add(component);
        return List.copyOf(command);
    }

    /**
     * The prompt, told where Codex is when it works inside the component.
     *
     * @param request what to fix
     * @return the text to send
     */
    private String prompt(FixRequest request) {
        return component.isEmpty() ? request.prompt() : String.format(INSIDE_COMPONENT, component) + request.prompt();
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
