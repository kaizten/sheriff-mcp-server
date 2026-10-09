package com.kaizten.sheriff.application.usecase;

import com.kaizten.sheriff.domain.valueobject.RunSummary;

/**
 * Fixes a component's code until the analyzer stops reporting errors and the
 * project's own tests still pass.
 *
 * <p>The driving port of this application: everything outside — the CLI today,
 * a Maven plugin or a CI step tomorrow — asks for this and nothing else. Its
 * return type is a value object rather than a boolean so that a caller that
 * wants the detail (why it stopped, how many iterations, which files were
 * parked) does not have to reach into the implementation for it.
 */
@FunctionalInterface
public interface FixCodeUntilClean {

    /**
     * Runs the loop to a conclusion.
     *
     * @return what happened: whether it succeeded, why it stopped, and the
     *     bookkeeping a report needs
     */
    RunSummary execute();
}
