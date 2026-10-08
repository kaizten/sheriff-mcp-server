package com.kaizten.sheriff.infrastructure.mcp.task;

import java.util.Locale;

/**
 * Where a task is in its life.
 *
 * <p>{@link #QUEUED} exists because background tasks run one at a time: two
 * repair loops on one repository would fight over its branch.
 */
public enum TaskState {

    /**
     * Accepted, waiting for the one before it to finish.
     */
    QUEUED,

    /**
     * Running now.
     */
    RUNNING,

    /**
     * Finished, with a result to read.
     */
    COMPLETED,

    /**
     * Stopped by an error, with the error as its result.
     */
    FAILED,

    /**
     * Cut short because the server itself was stopped while it ran, which is
     * how Codex ends a session: without this state its record said
     * {@code running} forever.
     */
    INTERRUPTED;

    /**
     * The name a client reads.
     *
     * @return the state in lower case
     */
    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether the task is over, one way or another.
     *
     * @return {@code true} for a completed, failed or interrupted task
     */
    public boolean finished() {
        return this == COMPLETED || this == FAILED || this == INTERRUPTED;
    }
}
