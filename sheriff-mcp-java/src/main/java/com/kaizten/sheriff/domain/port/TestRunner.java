package com.kaizten.sheriff.domain.port;

import com.kaizten.sheriff.domain.valueobject.VerificationResult;

/**
 * Runs the target project's own tests: the correctness gate that catches what
 * a green Sheriff run cannot, such as a rename that satisfied a style rule and
 * broke every caller.
 */
@FunctionalInterface
public interface TestRunner {

    /**
     * Runs the suite once.
     *
     * @return whether it passed, with the output the repair pass needs
     */
    VerificationResult run();
}
