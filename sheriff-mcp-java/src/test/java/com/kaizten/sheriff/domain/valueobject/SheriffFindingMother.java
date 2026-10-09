package com.kaizten.sheriff.domain.valueobject;

import java.util.Map;

/**
 * Object Mother for SheriffFinding.
 *
 * <p>Sheriff's value-object profile expects the tests of a value object to
 * build their instances through a Mother rather than inline, so that what a
 * valid, an equal, a different and an invalid value are lives in one place.
 */
final class SheriffFindingMother {

    private SheriffFindingMother() {
    }

    /**
     * Builds an arbitrary valid instance.
     *
     * @return a valid SheriffFinding
     */
    public static SheriffFinding random() {
        return new SheriffFinding("A.java", "what", "how", "", SheriffFinding.ERROR, Map.of());
    }

    /**
     * Builds an instance holding the same value as the given one.
     *
     * @param value the instance to match
     * @return a SheriffFinding equal to {@code value}
     */
    public static SheriffFinding equalTo(SheriffFinding value) {
        return new SheriffFinding(value.file(), value.description(), value.howToSolve(), value.referenceCode(), value.type(), value.raw());
    }

    /**
     * Builds an instance holding a different value from the given one.
     *
     * @param value the instance to differ from
     * @return a SheriffFinding not equal to {@code value}
     */
    public static SheriffFinding differentTo(SheriffFinding value) {
        return new SheriffFinding(value.file() + "x", value.description(), value.howToSolve(), value.referenceCode(), value.type(), value.raw());
    }

    /**
     * Builds an instance from a value the domain refuses.
     *
     * @return never returns; construction fails
     */
    public static SheriffFinding generateInvalidValue() {
        return new SheriffFinding(null, "what", "how", "", SheriffFinding.ERROR, Map.of());
    }
}
