package com.kaizten.sheriff.domain.valueobject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.enumerate.StopReason;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for the RunSummary value object.
 *
 * <p>Named and shaped after the behaviours Sheriff's value-object profile
 * requires. It checks the body of each test, not only its name, so every one
 * of these builds its own instances inside the method.
 */
class RunSummaryTests {

    /**
     * A value object must be final, or a record.
     */
    @Test
    void shouldBeDeclaredFinal() {
        assertTrue(RunSummary.class.isRecord() || Modifier.isFinal(RunSummary.class.getModifiers()));
    }

    /**
     * Every instance field must be private and final.
     */
    @Test
    void shouldHaveOnlyPrivateFinalInstanceFields() {
        for (Field field : RunSummary.class.getDeclaredFields()) {
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
        RunSummary value = new RunSummary(true, StopReason.SUCCESS, 2, 8, false, List.of());
        assertEquals(value, value);
    }

    /**
     * Two instances holding the same value are equal.
     */
    @Test
    void shouldBeEqualToAnotherInstanceWithSameValue() {
        RunSummary first = new RunSummary(true, StopReason.SUCCESS, 2, 8, false, List.of());
        RunSummary second = new RunSummary(true, StopReason.SUCCESS, 2, 8, false, List.of());
        assertEquals(first, second);
    }

    /**
     * Instances holding different values are not equal.
     */
    @Test
    void shouldNotBeEqualToAnotherInstanceWithDifferentValue() {
        RunSummary first = new RunSummary(true, StopReason.SUCCESS, 2, 8, false, List.of());
        RunSummary second = new RunSummary(false, StopReason.STALLED, 2, 8, false, List.of());
        assertNotEquals(first, second);
    }

    /**
     * An instance is never equal to null.
     */
    @Test
    void shouldNotBeEqualToNull() {
        RunSummary value = new RunSummary(true, StopReason.SUCCESS, 2, 8, false, List.of());
        assertNotEquals(null, value);
    }

    /**
     * An instance is not equal to an object of another type.
     */
    @Test
    void shouldNotBeEqualToAnObjectOfAnotherType() {
        RunSummary value = new RunSummary(true, StopReason.SUCCESS, 2, 8, false, List.of());
        assertNotEquals(value, "not a RunSummary");
    }

    /**
     * Equality is symmetric.
     */
    @Test
    void shouldBeSymmetricallyEqual() {
        RunSummary first = new RunSummary(true, StopReason.SUCCESS, 2, 8, false, List.of());
        RunSummary second = new RunSummary(true, StopReason.SUCCESS, 2, 8, false, List.of());
        assertEquals(first, second);
        assertEquals(second, first);
    }

    /**
     * Equality is transitive.
     */
    @Test
    void shouldBeTransitivelyEqual() {
        RunSummary first = new RunSummary(true, StopReason.SUCCESS, 2, 8, false, List.of());
        RunSummary second = new RunSummary(true, StopReason.SUCCESS, 2, 8, false, List.of());
        RunSummary third = new RunSummary(true, StopReason.SUCCESS, 2, 8, false, List.of());
        assertEquals(first, second);
        assertEquals(second, third);
        assertEquals(first, third);
    }

    /**
     * Equal instances share a hash code.
     */
    @Test
    void shouldHaveSameHashCodeWhenEqual() {
        RunSummary first = new RunSummary(true, StopReason.SUCCESS, 2, 8, false, List.of());
        RunSummary second = new RunSummary(true, StopReason.SUCCESS, 2, 8, false, List.of());
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    /**
     * A hash code does not change during an instance's lifetime.
     */
        @Test
    void shouldKeepHashCodeUnchanged() {
        final RunSummary value = RunSummaryMother.random();
        final int initialHashCode = value.hashCode();
        value.toString();
        assertEquals(initialHashCode, value.hashCode());
    }

    /**
     * A null value is refused rather than corrected.
     */
    @Test
    void shouldRejectNullValue() {
        assertThrows(IllegalArgumentException.class, () -> new RunSummary(true, null, 2, 8, false, List.of()));
    }

    /**
     * A value that breaks a domain rule is refused at construction.
     */
    @Test
    void shouldRejectInvalidValue() {
        assertThrows(IllegalArgumentException.class, () -> new RunSummary(true, StopReason.SUCCESS, 2, 8, false, null));
    }

    /**
     * An unknown branch is refused; "no branch" is the empty string.
     */
    @Test
    void shouldRejectAnUndefinedWorkingBranch() {
        assertThrows(IllegalArgumentException.class,
                () -> new RunSummary(true, StopReason.SUCCESS, 2, 8, false, List.of(), null));
    }
}
