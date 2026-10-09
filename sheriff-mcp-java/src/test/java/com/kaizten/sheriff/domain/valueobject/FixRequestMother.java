package com.kaizten.sheriff.domain.valueobject;

import java.util.Set;

/**
 * Object Mother for FixRequest.
 *
 * <p>Sheriff's value-object profile expects the tests of a value object to
 * build their instances through a Mother rather than inline, so that what a
 * valid, an equal, a different and an invalid value are lives in one place.
 */
final class FixRequestMother {

    private FixRequestMother() {
    }

    /**
     * Builds an arbitrary valid instance.
     *
     * @return a valid FixRequest
     */
    public static FixRequest random() {
        return FixRequest.scoped("fix it", "iteration-1", Set.of("A.java"));
    }

    /**
     * Builds an instance holding the same value as the given one.
     *
     * @param value the instance to match
     * @return a FixRequest equal to {@code value}
     */
    public static FixRequest equalTo(FixRequest value) {
        return FixRequest.scoped(value.prompt(), value.label(), value.allowedFiles());
    }

    /**
     * Builds an instance holding a different value from the given one.
     *
     * @param value the instance to differ from
     * @return a FixRequest not equal to {@code value}
     */
    public static FixRequest differentTo(FixRequest value) {
        return FixRequest.scoped(value.prompt() + " (changed)", value.label(), value.allowedFiles());
    }

    /**
     * Builds an instance from a value the domain refuses.
     *
     * @return never returns; construction fails
     */
    public static FixRequest generateInvalidValue() {
        return FixRequest.scoped(null, "iteration-1", Set.of("A.java"));
    }
}
