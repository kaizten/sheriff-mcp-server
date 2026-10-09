package com.kaizten.sheriff.domain.valueobject;


/**
 * Object Mother for FixResult.
 *
 * <p>Sheriff's value-object profile expects the tests of a value object to
 * build their instances through a Mother rather than inline, so that what a
 * valid, an equal, a different and an invalid value are lives in one place.
 */
final class FixResultMother {

    private FixResultMother() {
    }

    /**
     * Builds an arbitrary valid instance.
     *
     * @return a valid FixResult
     */
    public static FixResult random() {
        return new FixResult(true, "fixed");
    }

    /**
     * Builds an instance holding the same value as the given one.
     *
     * @param value the instance to match
     * @return a FixResult equal to {@code value}
     */
    public static FixResult equalTo(FixResult value) {
        return new FixResult(value.ok(), value.output());
    }

    /**
     * Builds an instance holding a different value from the given one.
     *
     * @param value the instance to differ from
     * @return a FixResult not equal to {@code value}
     */
    public static FixResult differentTo(FixResult value) {
        return new FixResult(!value.ok(), value.output() + " (changed)");
    }

    /**
     * Builds an instance from a value the domain refuses.
     *
     * @return never returns; construction fails
     */
    public static FixResult generateInvalidValue() {
        return new FixResult(true, null);
    }
}
