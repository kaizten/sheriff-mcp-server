package com.kaizten.sheriff.domain.valueobject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * Tests for the PassMetrics value object.
 *
 * <p>Named after the behaviours Sheriff's value-object profile requires, to
 * check whether it recognises them by method name.
 */
class PassMetricsTests {

    /**
     * A value object must be final, or a record.
     */
    @Test
    void shouldBeDeclaredFinal() {
        assertTrue(PassMetrics.class.isRecord() || Modifier.isFinal(PassMetrics.class.getModifiers()));
    }

    /**
     * Every instance field must be private and final.
     */
    @Test
    void shouldHaveOnlyPrivateFinalInstanceFields() {
        for (Field field : PassMetrics.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                continue;
            }
            assertTrue(Modifier.isPrivate(field.getModifiers()));
            assertTrue(Modifier.isFinal(field.getModifiers()));
        }
    }

    /**
     * An instance is equal to itself.
     */
    @Test
    void shouldBeEqualToItself() {
        PassMetrics result = PassMetricsMother.random();
        assertEquals(result, result);
    }

    /**
     * Two instances holding the same value are equal.
     */
    @Test
    void shouldBeEqualToAnotherInstanceWithSameValue() {
        PassMetrics first = new PassMetrics("iteration-1", PassMetrics.MODEL, Map.of("R1", 2), Map.of("R1", 1),
                Duration.ZERO, null);
        PassMetrics second = new PassMetrics("iteration-1", PassMetrics.MODEL, Map.of("R1", 2), Map.of("R1", 1),
                Duration.ZERO, null);
        assertEquals(first, second);
    }

    /**
     * Instances holding different values are not equal.
     */
    @Test
    void shouldNotBeEqualToAnotherInstanceWithDifferentValue() {
        PassMetrics first = PassMetricsMother.random();
        PassMetrics second = PassMetricsMother.differentTo(PassMetricsMother.random());
        assertNotEquals(first, second);
    }

    /**
     * An instance is never equal to null.
     */
    @Test
    void shouldNotBeEqualToNull() {
        PassMetrics result = PassMetricsMother.random();
        assertNotEquals(null, result);
    }

    /**
     * An instance is not equal to an object of another type.
     */
    @Test
    void shouldNotBeEqualToAnObjectOfAnotherType() {
        PassMetrics result = PassMetricsMother.random();
        assertNotEquals(result, "another type");
    }

    /**
     * Equality is symmetric.
     */
    @Test
    void shouldBeSymmetricallyEqual() {
        PassMetrics first = PassMetricsMother.random();
        PassMetrics second = PassMetricsMother.random();
        assertEquals(first, second);
        assertEquals(second, first);
    }

    /**
     * Equality is transitive.
     */
    @Test
    void shouldBeTransitivelyEqual() {
        PassMetrics first = PassMetricsMother.random();
        PassMetrics second = PassMetricsMother.random();
        PassMetrics third = PassMetricsMother.random();
        assertEquals(first, second);
        assertEquals(second, third);
        assertEquals(first, third);
    }

    /**
     * Equal instances share a hash code.
     */
    @Test
    void shouldHaveSameHashCodeWhenEqual() {
        PassMetrics first = PassMetricsMother.random();
        PassMetrics second = PassMetricsMother.random();
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    /**
     * A hash code does not change during an instance's lifetime.
     */
        @Test
    void shouldKeepHashCodeUnchanged() {
        final PassMetrics value = PassMetricsMother.random();
        final int initialHashCode = value.hashCode();
        value.toString();
        assertEquals(initialHashCode, value.hashCode());
    }

    /**
     * What a pass repaired is what its rules lost, and nothing when unmeasured.
     */
    @Test
    void shouldCountWhatEachRuleLost() {
        PassMetrics pass = new PassMetrics("iteration-1", PassMetrics.MODEL, Map.of("R1", 3, "R2", 1, "R3", 1),
                Map.of("R1", 1, "R3", 2, "R4", 1), Duration.ZERO, null);
        assertEquals(Map.of("R1", 2, "R2", 1), pass.repaired());
        assertEquals(Map.of(), new PassMetrics("sheriff-fixers", PassMetrics.DETERMINISTIC, Map.of("R1", 1), null,
                Duration.ZERO, null).repaired());
        assertFalse(pass.errorsBefore().isEmpty());
    }

    /**
     * Each missing value is refused on its own, and the kind is one of two.
     */
    @Test
    void shouldRefuseEachMissingValueAndAnyOtherKind() {
        assertThrows(IllegalArgumentException.class,
                () -> new PassMetrics(null, PassMetrics.MODEL, Map.of(), null, Duration.ZERO, null));
        assertThrows(IllegalArgumentException.class,
                () -> new PassMetrics("p", PassMetrics.MODEL, null, null, Duration.ZERO, null));
        assertThrows(IllegalArgumentException.class,
                () -> new PassMetrics("p", PassMetrics.MODEL, Map.of(), null, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new PassMetrics("p", null, Map.of(), null, Duration.ZERO, null));
        assertEquals(PassMetrics.DETERMINISTIC,
                new PassMetrics("p", PassMetrics.DETERMINISTIC, Map.of(), null, Duration.ZERO, null).kind());
        assertEquals(PassMetrics.MODEL,
                new PassMetrics("p", PassMetrics.MODEL, Map.of(), null, Duration.ZERO, null).kind());
    }

    /**
     * Every collection it returns is unmodifiable.
     */
    @Test
    void shouldReturnImmutableCollection() {
        final PassMetrics value = new PassMetrics("iteration-1", PassMetrics.MODEL, Map.of("R1", 2), Map.of("R1", 1),
                Duration.ZERO, null);
        assertThrows(UnsupportedOperationException.class, () -> value.errorsBefore().clear());
        assertThrows(UnsupportedOperationException.class, () -> value.errorsAfter().clear());
        assertThrows(UnsupportedOperationException.class, () -> value.repaired().clear());
    }

    /**
     * A null value is refused rather than corrected.
     */
    @Test
    void shouldRejectNullValue() {
        assertThrows(IllegalArgumentException.class, () -> PassMetricsMother.generateInvalidValue());
    }

    /**
     * A value that breaks a domain rule is refused at construction.
     */
    @Test
    void shouldRejectInvalidValue() {
        assertThrows(IllegalArgumentException.class, () -> new PassMetrics(null, PassMetrics.MODEL, Map.of(), null, Duration.ZERO, null));
    }
}
