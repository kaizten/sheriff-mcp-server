package com.kaizten.sheriff.domain.valueobject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * Tests for the LoopSettings value object.
 *
 * <p>Named and shaped after the behaviours Sheriff's value-object profile
 * requires. It checks the body of each test, not only its name, so every one
 * of these builds its own instances inside the method.
 */
class LoopSettingsTests {

    /**
     * A value object must be final, or a record.
     */
    @Test
    void shouldBeDeclaredFinal() {
        assertTrue(LoopSettings.class.isRecord() || Modifier.isFinal(LoopSettings.class.getModifiers()));
    }

    /**
     * Every instance field must be private and final.
     */
    @Test
    void shouldHaveOnlyPrivateFinalInstanceFields() {
        for (Field field : LoopSettings.class.getDeclaredFields()) {
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
        LoopSettings value = new LoopSettings(8, true, 5);
        assertEquals(value, value);
    }

    /**
     * Two instances holding the same value are equal.
     */
    @Test
    void shouldBeEqualToAnotherInstanceWithSameValue() {
        LoopSettings first = new LoopSettings(8, true, 5);
        LoopSettings second = new LoopSettings(8, true, 5);
        assertEquals(first, second);
    }

    /**
     * Instances holding different values are not equal.
     */
    @Test
    void shouldNotBeEqualToAnotherInstanceWithDifferentValue() {
        LoopSettings first = new LoopSettings(8, true, 5);
        LoopSettings second = new LoopSettings(8, true, 3);
        assertNotEquals(first, second);
    }

    /**
     * An instance is never equal to null.
     */
    @Test
    void shouldNotBeEqualToNull() {
        LoopSettings value = new LoopSettings(8, true, 5);
        assertNotEquals(null, value);
    }

    /**
     * An instance is not equal to an object of another type.
     */
    @Test
    void shouldNotBeEqualToAnObjectOfAnotherType() {
        LoopSettings value = new LoopSettings(8, true, 5);
        assertNotEquals(value, "not a LoopSettings");
    }

    /**
     * Equality is symmetric.
     */
    @Test
    void shouldBeSymmetricallyEqual() {
        LoopSettings first = new LoopSettings(8, true, 5);
        LoopSettings second = new LoopSettings(8, true, 5);
        assertEquals(first, second);
        assertEquals(second, first);
    }

    /**
     * Equality is transitive.
     */
    @Test
    void shouldBeTransitivelyEqual() {
        LoopSettings first = new LoopSettings(8, true, 5);
        LoopSettings second = new LoopSettings(8, true, 5);
        LoopSettings third = new LoopSettings(8, true, 5);
        assertEquals(first, second);
        assertEquals(second, third);
        assertEquals(first, third);
    }

    /**
     * Equal instances share a hash code.
     */
    @Test
    void shouldHaveSameHashCodeWhenEqual() {
        LoopSettings first = new LoopSettings(8, true, 5);
        LoopSettings second = new LoopSettings(8, true, 5);
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    /**
     * A hash code does not change during an instance's lifetime.
     */
        @Test
    void shouldKeepHashCodeUnchanged() {
        final LoopSettings value = LoopSettingsMother.random();
        final int initialHashCode = value.hashCode();
        value.toString();
        assertEquals(initialHashCode, value.hashCode());
    }

    /**
     * A null value is refused rather than corrected.
     */
    @Test
    void shouldRejectNullValue() {
        assertThrows(IllegalArgumentException.class, () -> new LoopSettings(8, true, -1));
    }

    /**
     * A value that breaks a domain rule is refused at construction.
     */
    @Test
    void shouldRejectInvalidValue() {
        assertThrows(IllegalArgumentException.class, () -> new LoopSettings(null, false, -5));
    }

    @Test
    void aCapOfNoIterationsIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new LoopSettings(0, true, 5));
        assertEquals(1, new LoopSettings(1, true, 5).maxIterations());
    }
}
