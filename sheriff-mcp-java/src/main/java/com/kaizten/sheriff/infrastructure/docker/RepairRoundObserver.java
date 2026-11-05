package com.kaizten.sheriff.infrastructure.docker;

import com.kaizten.sheriff.domain.valueobject.AnalysisResult;

/**
 * What is told of every round of a {@link SheriffRepair} as it happens: the
 * analysis that opens it, profile by profile, and whether fixers ran after
 * it. The next round's analysis measures what they did.
 */
@FunctionalInterface
public interface RepairRoundObserver {

    /**
     * One analysis of a round, and what followed it.
     *
     * @param round the round, from 1
     * @param profile the profile analyzed
     * @param analysis what the analysis found
     * @param fixersRan whether Sheriff's fixers ran on what it found
     */
    void analyzed(int round, String profile, AnalysisResult analysis, boolean fixersRan);
}
