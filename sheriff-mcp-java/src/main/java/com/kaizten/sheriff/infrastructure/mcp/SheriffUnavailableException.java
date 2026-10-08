package com.kaizten.sheriff.infrastructure.mcp;

/**
 * Sheriff could not be run, as distinct from Sheriff finding problems: no
 * Docker, no image, a component that does not exist, or a run that
 * produced no usable report. The equivalent of {@code SheriffUnavailable}
 * in the Python server this replaced.
 */
public final class SheriffUnavailableException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Wraps why Sheriff could not be run.
     *
     * @param message why it could not be run
     */
    public SheriffUnavailableException(String message) {
        super(message);
    }
}
