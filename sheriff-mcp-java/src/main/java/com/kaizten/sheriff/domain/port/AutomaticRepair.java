package com.kaizten.sheriff.domain.port;

import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import java.util.Optional;

/**
 * The repairs that need no model: the analyzer's own fixers, applied before
 * the loop spends a token on anything they could have done for free.
 *
 * <p>A port rather than a step the caller runs first, because where it runs
 * is the whole point. It rewrites source, so it belongs inside the run's own
 * branch and its own commit; run ahead of the loop, it edited whatever branch
 * was checked out and then tripped the loop's dirty-tree guard with its own
 * changes.
 */
@FunctionalInterface
public interface AutomaticRepair {

    /**
     * Applies every repair the analyzer can make by itself.
     *
     * @return one line saying what was attempted, for the run's log; empty
     *     when there is nothing worth saying
     */
    String apply();

    /**
     * What the last {@link #apply()} found before it repaired anything,
     * so a run can measure what the repair did.
     *
     * @return that analysis, empty when it did not analyze or could not
     */
    default Optional<AnalysisResult> analyzedBefore() {
        return Optional.empty();
    }
}
