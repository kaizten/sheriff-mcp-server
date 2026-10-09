package com.kaizten.sheriff.domain.valueobject;


/**
 * Object Mother for VerificationResult.
 *
 * <p>Sheriff's value-object profile expects the tests of a value object to
 * build their instances through a Mother rather than inline, so that what a
 * valid, an equal, a different and an invalid value are lives in one place.
 */
final class VerificationResultMother {

    private VerificationResultMother() {
    }

    /**
     * Builds an arbitrary valid instance.
     *
     * @return a valid VerificationResult
     */
    public static VerificationResult random() {
        return new VerificationResult(true, "all good");
    }

    /**
     * Builds an instance holding the same value as the given one.
     *
     * @param value the instance to match
     * @return a VerificationResult equal to {@code value}
     */
    public static VerificationResult equalTo(VerificationResult value) {
        return new VerificationResult(value.ok(), value.output());
    }

    /**
     * Builds an instance holding a different value from the given one.
     *
     * @param value the instance to differ from
     * @return a VerificationResult not equal to {@code value}
     */
    public static VerificationResult differentTo(VerificationResult value) {
        return new VerificationResult(!value.ok(), value.output() + " (changed)");
    }

    /**
     * Builds an instance from a value the domain refuses.
     *
     * @return never returns; construction fails
     */
    public static VerificationResult generateInvalidValue() {
        return new VerificationResult(true, null);
    }
}
