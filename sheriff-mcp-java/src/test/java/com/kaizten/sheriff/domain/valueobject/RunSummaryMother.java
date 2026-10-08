package com.kaizten.sheriff.domain.valueobject;

import com.kaizten.sheriff.domain.enumerate.StopReason;
import java.util.List;

/**
 * Object Mother for RunSummary.
 *
 * <p>Sheriff's value-object profile expects the tests of a value object to
 * build their instances through a Mother rather than inline, so that what a
 * valid, an equal, a different and an invalid value are lives in one place.
 */
final class RunSummaryMother {

    private RunSummaryMother() {
    }

    /**
     * Builds an arbitrary valid instance.
     *
     * @return a valid RunSummary
     */
    public static RunSummary random() {
        return new RunSummary(true, StopReason.SUCCESS, 2, 8, false, List.of(), "sheriff-agent/20260924-120000", true);
    }

    /**
     * Builds an instance holding the same value as the given one.
     *
     * @param value the instance to match
     * @return a RunSummary equal to {@code value}
     */
    public static RunSummary equalTo(RunSummary value) {
        return new RunSummary(value.ok(), value.stoppedReason(), value.iterationsUsed(), value.maxIterations(), value.repairPassUsed(), value.parkedFiles(), value.workingBranch(), value.testsPassed());
    }

    /**
     * Builds an instance holding a different value from the given one.
     *
     * @param value the instance to differ from
     * @return a RunSummary not equal to {@code value}
     */
    public static RunSummary differentTo(RunSummary value) {
        return new RunSummary(!value.ok(), StopReason.STALLED, value.iterationsUsed() + 1, value.maxIterations(), value.repairPassUsed(), value.parkedFiles(), value.workingBranch(), value.testsPassed());
    }

    /**
     * Builds an instance from a value the domain refuses.
     *
     * @return never returns; construction fails
     */
    public static RunSummary generateInvalidValue() {
        return new RunSummary(true, null, 2, 8, false, List.of());
    }
}
