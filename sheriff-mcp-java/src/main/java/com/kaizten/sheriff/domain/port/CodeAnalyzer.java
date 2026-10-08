package com.kaizten.sheriff.domain.port;

import com.kaizten.sheriff.domain.valueobject.AnalysisResult;

/**
 * Inspects the target repository and reports what is wrong with it: Sheriff,
 * real or mocked.
 */
@FunctionalInterface
public interface CodeAnalyzer {

    /**
     * Runs one analysis.
     *
     * @return what was found, or a failure when the analysis could not run at
     *     all — the two are not the same and the loop treats them differently
     */
    AnalysisResult analyze();
}
