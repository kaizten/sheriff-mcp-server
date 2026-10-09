package com.kaizten.sheriff.infrastructure.hook;

/**
 * The exit codes Claude Code reads from a hook.
 *
 * <p>Their own constants because they are a contract with the harness, not
 * this program's own convention: 0 lets the work continue, 2 stops it and
 * shows the hook's standard error to the model.
 */
public final class HookExit {

    /**
     * Let the tool call, or the turn, go ahead.
     */
    public static final int ALLOW = 0;

    /**
     * Stop it, and tell the model why.
     */
    public static final int BLOCK = 2;

    private static final String UTILITY_CLASS_MESSAGE = "This is a utility class and cannot be instantiated.";

    /**
     * Never called: this class only carries the two exit codes, so an instance
     * of it would mean nothing.
     */
    private HookExit() {
        throw new UnsupportedOperationException(UTILITY_CLASS_MESSAGE);
    }
}
