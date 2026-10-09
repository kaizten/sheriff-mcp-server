package com.kaizten.sheriff.domain.valueobject;

import java.util.List;

/**
 * Object Mother for SheriffRule.
 *
 * <p>Sheriff's value-object profile expects the tests of a value object to
 * build their instances through a Mother rather than inline, so that what a
 * valid, an equal, a different and an invalid value are lives in one place.
 */
final class SheriffRuleMother {

    private SheriffRuleMother() {
    }

    /**
     * Builds an arbitrary valid instance.
     *
     * @return a valid SheriffRule
     */
    public static SheriffRule random() {
        return new SheriffRule("Code", "what", "how", "java", "format", "", List.of("JAVA"));
    }

    /**
     * Builds an instance holding the same value as the given one.
     *
     * @param value the instance to match
     * @return a SheriffRule equal to {@code value}
     */
    public static SheriffRule equalTo(SheriffRule value) {
        return new SheriffRule(value.code(), value.description(), value.howToSolve(), value.language(), value.category(), value.fixer(), value.profiles());
    }

    /**
     * Builds an instance holding a different value from the given one.
     *
     * @param value the instance to differ from
     * @return a SheriffRule not equal to {@code value}
     */
    public static SheriffRule differentTo(SheriffRule value) {
        return new SheriffRule(value.code() + "Other", value.description(), value.howToSolve(), value.language(), value.category(), value.fixer(), value.profiles());
    }

    /**
     * Builds an instance from a value the domain refuses.
     *
     * @return never returns; construction fails
     */
    public static SheriffRule generateInvalidValue() {
        return new SheriffRule(null, "what", "how", "java", "format", "", List.of("JAVA"));
    }
}
