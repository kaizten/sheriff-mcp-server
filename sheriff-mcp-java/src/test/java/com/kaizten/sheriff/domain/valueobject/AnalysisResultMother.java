package com.kaizten.sheriff.domain.valueobject;

import java.util.List;

/**
 * Object Mother for AnalysisResult.
 *
 * <p>Sheriff's value-object profile expects the tests of a value object to
 * build their instances through a Mother rather than inline, so that what a
 * valid, an equal, a different and an invalid value are lives in one place.
 */
final class AnalysisResultMother {

    private AnalysisResultMother() {
    }

    /**
     * Builds an arbitrary valid instance.
     *
     * @return a valid AnalysisResult
     */
    public static AnalysisResult random() {
        return new AnalysisResult(List.of(SheriffFindingMother.random()), false, "");
    }

    /**
     * Builds an instance holding the same value as the given one.
     *
     * @param value the instance to match
     * @return a AnalysisResult equal to {@code value}
     */
    public static AnalysisResult equalTo(AnalysisResult value) {
        return new AnalysisResult(value.findings(), value.error(), value.message());
    }

    /**
     * Builds an instance holding a different value from the given one.
     *
     * @param value the instance to differ from
     * @return a AnalysisResult not equal to {@code value}
     */
    public static AnalysisResult differentTo(AnalysisResult value) {
        return new AnalysisResult(List.of(), !value.error(), value.message() + " (changed)");
    }

    /**
     * Builds an instance from a value the domain refuses.
     *
     * @return never returns; construction fails
     */
    public static AnalysisResult generateInvalidValue() {
        return new AnalysisResult(null, false, "");
    }
}
