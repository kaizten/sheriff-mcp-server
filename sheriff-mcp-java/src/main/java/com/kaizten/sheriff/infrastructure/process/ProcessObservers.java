package com.kaizten.sheriff.infrastructure.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * The one observer of every command this process runs through
 * {@link SystemProcessRunner}, whichever instance runs it.
 *
 * <p>Process-wide, as the MCP server's tools build runners of their own (for
 * the project's tests, git and PIT) beside the one that runs Sheriff, and its
 * session's report is to name every command a call ran. The observer is told
 * on the thread that ran the command, so it can tell which call it was for.
 * By default nothing observes.
 */
public final class ProcessObservers {

    private static final String NOT_BUILT = "ProcessObservers only holds the observer";
    private static final ProcessObserver NOBODY = (command, workingDirectory, outcome, took) -> { };
    private static volatile ProcessObserver observer = NOBODY;

    /**
     * Not built: it only holds the observer.
     */
    private ProcessObservers() {
        throw new UnsupportedOperationException(NOT_BUILT);
    }

    /**
     * Has every command from now on told to an observer, in place of the one
     * before.
     *
     * @param chosen the observer
     */
    public static void observeWith(ProcessObserver chosen) {
        observer = chosen;
    }

    /**
     * Has no command told to anyone from now on.
     */
    public static void stopObserving() {
        observer = NOBODY;
    }

    /**
     * Tells the observer of one command about to run.
     *
     * @param command the command
     * @param workingDirectory where it runs
     */
    static void starting(List<String> command, Path workingDirectory) {
        observer.starting(command, workingDirectory);
    }

    /**
     * Tells the observer of one command that ended.
     *
     * @param command the command
     * @param workingDirectory where it ran
     * @param outcome how it ended
     * @param took how long it ran
     */
    static void ran(List<String> command, Path workingDirectory, ProcessOutcome outcome, Duration took) {
        observer.ran(command, workingDirectory, outcome, took);
    }
}
