package com.kaizten.sheriff.infrastructure.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Runs an external command. Every adapter that talks to Docker, git, Maven or
 * the Claude CLI goes through this one interface.
 *
 * <p>It exists as an interface for a reason the Python original never needed:
 * there, a test monkeypatches {@code subprocess.run} and the adapter is none
 * the wiser. Java has no such trapdoor, so the seam has to be designed in —
 * which is the better arrangement anyway, since it makes "what command did we
 * actually build?" an assertion rather than an inspection of a mock.
 */
@FunctionalInterface
public interface ProcessRunner {

    /**
     * Runs a command and waits for it, up to a deadline.
     *
     * @param command the executable and its arguments
     * @param workingDirectory where to run it
     * @param environment extra environment variables, merged over the current
     *     process's own
     * @param timeout how long to wait before killing it
     * @return what it produced, including whether it ran at all
     */
    ProcessOutcome run(List<String> command, Path workingDirectory, Map<String, String> environment, Duration timeout);

    /**
     * Runs a command with something to read on its standard input.
     *
     * <p>For input too large to pass as an argument: Linux caps a single
     * argument at 128 KB, and a prompt covering a batch of large files can
     * pass that.
     *
     * @param command the executable and its arguments
     * @param workingDirectory where to run it
     * @param environment extra environment variables
     * @param timeout how long to wait before killing it
     * @param standardInput what to write to its standard input
     * @return what it produced, including whether it ran at all
     */
    default ProcessOutcome run(List<String> command, Path workingDirectory, Map<String, String> environment,
            Duration timeout, String standardInput) {
        return run(command, workingDirectory, environment, timeout);
    }
}
