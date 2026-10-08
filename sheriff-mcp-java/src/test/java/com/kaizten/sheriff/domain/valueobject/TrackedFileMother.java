package com.kaizten.sheriff.domain.valueobject;


/**
 * Object Mother for TrackedFile.
 *
 * <p>Sheriff's value-object profile expects the tests of a value object to
 * build their instances through a Mother rather than inline, so that what a
 * valid, an equal, a different and an invalid value are lives in one place.
 */
final class TrackedFileMother {

    private TrackedFileMother() {
    }

    /**
     * Builds an arbitrary valid instance.
     *
     * @return a valid TrackedFile
     */
    public static TrackedFile random() {
        return new TrackedFile("app/A.java", "abc");
    }

    /**
     * Builds an instance holding the same value as the given one.
     *
     * @param value the instance to match
     * @return a TrackedFile equal to {@code value}
     */
    public static TrackedFile equalTo(TrackedFile value) {
        return new TrackedFile(value.path(), value.hash());
    }

    /**
     * Builds an instance holding a different value from the given one.
     *
     * @param value the instance to differ from
     * @return a TrackedFile not equal to {@code value}
     */
    public static TrackedFile differentTo(TrackedFile value) {
        return new TrackedFile(value.path(), value.hash() + "0");
    }

    /**
     * Builds an instance from a value the domain refuses.
     *
     * @return never returns; construction fails
     */
    public static TrackedFile generateInvalidValue() {
        return new TrackedFile("app/A.java", null);
    }
}
