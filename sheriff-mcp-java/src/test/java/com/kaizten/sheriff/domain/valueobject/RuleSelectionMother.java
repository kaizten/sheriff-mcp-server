package com.kaizten.sheriff.domain.valueobject;

import java.util.List;

/**
 * Object Mother for RuleSelection.
 *
 * <p>Sheriff's value-object profile expects the tests of a value object to
 * build their instances through a Mother rather than inline, so that what a
 * valid, an equal, a different and an invalid value are lives in one place.
 */
final class RuleSelectionMother {

    private RuleSelectionMother() {
    }

    /**
     * Builds an arbitrary valid instance.
     *
     * @return a valid RuleSelection
     */
    public static RuleSelection random() {
        return new RuleSelection(List.of(SheriffRuleMother.random()), true);
    }

    /**
     * Builds an instance holding the same value as the given one.
     *
     * @param value the instance to match
     * @return a RuleSelection equal to {@code value}
     */
    public static RuleSelection equalTo(RuleSelection value) {
        return new RuleSelection(value.rules(), value.exact());
    }

    /**
     * Builds an instance holding a different value from the given one.
     *
     * @param value the instance to differ from
     * @return a RuleSelection not equal to {@code value}
     */
    public static RuleSelection differentTo(RuleSelection value) {
        return new RuleSelection(List.of(), !value.exact());
    }

    /**
     * Builds an instance from a value the domain refuses.
     *
     * @return never returns; construction fails
     */
    public static RuleSelection generateInvalidValue() {
        return new RuleSelection(null, true);
    }
}
