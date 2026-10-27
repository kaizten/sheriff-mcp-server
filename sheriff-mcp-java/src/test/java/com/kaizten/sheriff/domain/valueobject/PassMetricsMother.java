package com.kaizten.sheriff.domain.valueobject;

import java.time.Duration;
import java.util.Map;


/**
 * Object Mother for PassMetrics.
 *
 * <p>Sheriff's value-object profile expects the tests of a value object to
 * build their instances through a Mother rather than inline, so that what a
 * valid, an equal, a different and an invalid value are lives in one place.
 */
final class PassMetricsMother {

    private PassMetricsMother() {
    }

    /**
     * Builds an arbitrary valid instance.
     *
     * @return a valid PassMetrics
     */
    public static PassMetrics random() {
        return new PassMetrics("iteration-1", PassMetrics.MODEL, Map.of("R1", 2), Map.of("R1", 1), Duration.ofSeconds(3), null);
    }

    /**
     * Builds an instance holding the same value as the given one.
     *
     * @param value the instance to match
     * @return a PassMetrics equal to {@code value}
     */
    public static PassMetrics equalTo(PassMetrics value) {
        return new PassMetrics(value.label(), value.kind(), value.errorsBefore(), value.errorsAfter(), value.elapsed(), value.usage());
    }

    /**
     * Builds an instance holding a different value from the given one.
     *
     * @param value the instance to differ from
     * @return a PassMetrics not equal to {@code value}
     */
    public static PassMetrics differentTo(PassMetrics value) {
        return new PassMetrics(value.label() + "-changed", value.kind(), value.errorsBefore(), value.errorsAfter(), value.elapsed(), value.usage());
    }

    /**
     * Builds an instance from a value the domain refuses.
     *
     * @return never returns; construction fails
     */
    public static PassMetrics generateInvalidValue() {
        return new PassMetrics("iteration-1", "guess", Map.of(), null, Duration.ZERO, null);
    }
}
