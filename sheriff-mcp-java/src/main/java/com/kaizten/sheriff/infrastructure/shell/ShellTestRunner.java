package com.kaizten.sheriff.infrastructure.shell;

import com.kaizten.sheriff.domain.port.TestRunner;
import com.kaizten.sheriff.domain.valueobject.VerificationResult;
import com.kaizten.sheriff.infrastructure.process.Platform;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Runs the target project's own test suite through a shell, because the
 * command is configuration ("cd some-module &amp;&amp; mvn -q test") rather than a
 * fixed executable.
 *
 * <p>An empty command disables the gate, and so does {@link #NO_TESTS_COMMAND},
 * the one given for a component with no build tool. Either is reported as
 * tests not run, which stops nothing, since a project that has no suite has
 * not failed one, and claims nothing either.
 */
public final class ShellTestRunner implements TestRunner {

    /**
     * Why a component with no build tool had no tests run, as it is reported.
     */
    public static final String NO_TESTS_REASON =
            "No build tool with tests was found in the component, so nothing was verified beyond Sheriff.";

    /**
     * The command given for a component with no build tool: it is never run,
     * but a command it has to be, because an empty one is replaced by the
     * default on its way through the environment.
     */
    public static final String NO_TESTS_COMMAND = "echo '" + NO_TESTS_REASON + "'";

    private static final String DISABLED_MESSAGE = "No verification command configured; skipping the test gate.";

    private final ProcessRunner processes;
    private final String command;
    private final Path workingDirectory;
    private final Duration timeout;

    /**
     * Wires the runner to one command.
     *
     * @param processes how to run the shell
     * @param command the shell command, empty to disable the gate
     * @param workingDirectory where to run it
     * @param timeout how long it is given
     */
    public ShellTestRunner(ProcessRunner processes, String command, Path workingDirectory, Duration timeout) {
        this.processes = processes;
        this.command = command;
        this.workingDirectory = workingDirectory;
        this.timeout = timeout;
    }

    /**
     * Runs the configured command and reads its result.
     *
     * <p>Both streams are reported together, because a failing build says why
     * on whichever of the two it feels like.
     *
     * @return not run when there is nothing to run, passed when the command
     *     exited zero, failed with the command's own output otherwise
     */
    @Override
    public VerificationResult run() {
        if (command.isBlank()) {
            return VerificationResult.notRun(DISABLED_MESSAGE);
        }
        if (NO_TESTS_COMMAND.equals(command)) {
            return VerificationResult.notRun(NO_TESTS_REASON);
        }
        ProcessOutcome outcome =
                processes.run(Platform.shell(command), workingDirectory, Map.of(), timeout);
        if (!outcome.ran()) {
            return VerificationResult.failed(outcome.failure());
        }
        String output = outcome.standardOutput() + outcome.standardError();
        return outcome.succeeded() ? VerificationResult.passed(output) : VerificationResult.failed(output);
    }
}
