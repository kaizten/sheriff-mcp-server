package com.kaizten.sheriff.infrastructure.mcp.task;

/**
 * A task run in the caller's thread failed, and this is the task it failed
 * as. The cause is what the work threw.
 *
 * <p>Without it, the caller got the work's exception and nothing else: the
 * task was recorded as failed, with its folder and whatever Sheriff exported
 * into it, but the answer never said which task that was, so the one record
 * that explains a failure was the one the client could not find.
 */
public final class TaskFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * The task, as it was recorded when it failed.
     */
    private final transient Task task;

    /**
     * Wraps what the work threw, with the task it failed.
     *
     * @param task the task, recorded as failed
     * @param cause what the work threw
     */
    public TaskFailure(Task task, RuntimeException cause) {
        super(cause.getMessage(), cause);
        this.task = task;
    }

    /**
     * The task that failed.
     *
     * @return that task, with its id and folder
     */
    public Task task() {
        return task;
    }
}
