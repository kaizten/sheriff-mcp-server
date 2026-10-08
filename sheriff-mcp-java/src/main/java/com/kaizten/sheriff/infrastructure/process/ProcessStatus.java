package com.kaizten.sheriff.infrastructure.process;

/**
 * How an attempt to run an external command ended.
 *
 * <p>Three outcomes rather than two, because "it ran and said no" and "it never
 * ran" must lead to different decisions everywhere in this agent.
 */
public enum ProcessStatus {

    /**
     * The command ran to completion and left an exit code worth reading,
     * whatever that code turned out to be.
     */
    COMPLETED,

    /**
     * The command never started — usually an executable that is not installed
     * or not on the PATH.
     */
    UNAVAILABLE,

    /**
     * The command was still running when its deadline passed and was killed,
     * so whatever it had produced is incomplete.
     */
    TIMED_OUT
}
