package com.kaizten.sheriff.infrastructure.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * What is told of every command {@link SystemProcessRunner} has run, once it
 * has ended, on the thread that ran it.
 */
@FunctionalInterface
public interface ProcessObserver {

    /**
     * One command, run and ended.
     *
     * @param command the command, as it was given
     * @param workingDirectory where it ran, {@code null} for this process's own
     * @param outcome how it ended
     * @param took how long it ran
     */
    void ran(List<String> command, Path workingDirectory, ProcessOutcome outcome, Duration took);
}
