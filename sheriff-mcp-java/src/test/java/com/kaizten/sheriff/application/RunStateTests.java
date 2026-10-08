package com.kaizten.sheriff.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The cap's bookkeeping: the first estimate is what bounds how far an
 * automatic cap may grow, so it must survive the cap growing.
 */
class RunStateTests {

    @Test
    @DisplayName("the first estimate is the cap the first analysis gave, and it stays as the cap grows")
    void remembersTheFirstEstimate() {
        RunState state = new RunState(null);
        assertNull(state.maxIterations());
        assertEquals(0, state.firstEstimate());
        state.maxIterations(7);
        state.maxIterations(10);
        assertEquals(7, state.firstEstimate());
        assertEquals(10, state.maxIterations());
    }

    @Test
    @DisplayName("a cap the user set is no estimate")
    void aConfiguredCapIsNoEstimate() {
        RunState state = new RunState(3);
        assertEquals(0, state.firstEstimate());
        assertEquals("1/3", state.label());
    }
}
