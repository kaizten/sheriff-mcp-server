package com.kaizten.sheriff.domain.valueobject;


/**
 * Object Mother for LoopSettings.
 *
 * <p>Sheriff's value-object profile expects the tests of a value object to
 * build their instances through a Mother rather than inline, so that what a
 * valid, an equal, a different and an invalid value are lives in one place.
 */
final class LoopSettingsMother {

    private LoopSettingsMother() {
    }

    /**
     * Builds an arbitrary valid instance.
     *
     * @return a valid LoopSettings
     */
    public static LoopSettings random() {
        return new LoopSettings(8, true, 5);
    }

    /**
     * Builds an instance holding the same value as the given one.
     *
     * @param value the instance to match
     * @return a LoopSettings equal to {@code value}
     */
    public static LoopSettings equalTo(LoopSettings value) {
        return new LoopSettings(value.maxIterations(), value.useGitSafety(), value.maxFilesPerBatch());
    }

    /**
     * Builds an instance holding a different value from the given one.
     *
     * @param value the instance to differ from
     * @return a LoopSettings not equal to {@code value}
     */
    public static LoopSettings differentTo(LoopSettings value) {
        return new LoopSettings(value.maxIterations(), !value.useGitSafety(), value.maxFilesPerBatch() + 1);
    }

    /**
     * Builds an instance from a value the domain refuses.
     *
     * @return never returns; construction fails
     */
    public static LoopSettings generateInvalidValue() {
        return new LoopSettings(8, true, -1);
    }
}
