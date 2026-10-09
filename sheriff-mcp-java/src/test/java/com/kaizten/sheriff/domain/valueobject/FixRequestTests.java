package com.kaizten.sheriff.domain.valueobject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Tests for the FixRequest value object.
 *
 * <p>Named and shaped after the behaviours Sheriff's value-object profile
 * requires. It checks the body of each test, not only its name, so every one
 * of these builds its own instances inside the method.
 */
class FixRequestTests {

    /**
     * A value object must be final, or a record.
     */
    @Test
    void shouldBeDeclaredFinal() {
        assertTrue(FixRequest.class.isRecord() || Modifier.isFinal(FixRequest.class.getModifiers()));
    }

    /**
     * Every instance field must be private and final.
     */
    @Test
    void shouldHaveOnlyPrivateFinalInstanceFields() {
        for (Field field : FixRequest.class.getDeclaredFields()) {
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
        FixRequest value = FixRequest.scoped("fix it", "iteration-1", Set.of("A.java"));
        assertEquals(value, value);
    }

    /**
     * Two instances holding the same value are equal.
     */
    @Test
    void shouldBeEqualToAnotherInstanceWithSameValue() {
        FixRequest first = FixRequest.scoped("fix it", "iteration-1", Set.of("A.java"));
        FixRequest second = FixRequest.scoped("fix it", "iteration-1", Set.of("A.java"));
        assertEquals(first, second);
    }

    /**
     * Instances holding different values are not equal.
     */
    @Test
    void shouldNotBeEqualToAnotherInstanceWithDifferentValue() {
        FixRequest first = FixRequest.scoped("fix it", "iteration-1", Set.of("A.java"));
        FixRequest second = FixRequest.scoped("fix it", "iteration-2", Set.of("A.java"));
        assertNotEquals(first, second);
    }

    /**
     * An instance is never equal to null.
     */
    @Test
    void shouldNotBeEqualToNull() {
        FixRequest value = FixRequest.scoped("fix it", "iteration-1", Set.of("A.java"));
        assertNotEquals(null, value);
    }

    /**
     * An instance is not equal to an object of another type.
     */
    @Test
    void shouldNotBeEqualToAnObjectOfAnotherType() {
        FixRequest value = FixRequest.scoped("fix it", "iteration-1", Set.of("A.java"));
        assertNotEquals(value, "not a FixRequest");
    }

    /**
     * Equality is symmetric.
     */
    @Test
    void shouldBeSymmetricallyEqual() {
        FixRequest first = FixRequest.scoped("fix it", "iteration-1", Set.of("A.java"));
        FixRequest second = FixRequest.scoped("fix it", "iteration-1", Set.of("A.java"));
        assertEquals(first, second);
        assertEquals(second, first);
    }

    /**
     * Equality is transitive.
     */
    @Test
    void shouldBeTransitivelyEqual() {
        FixRequest first = FixRequest.scoped("fix it", "iteration-1", Set.of("A.java"));
        FixRequest second = FixRequest.scoped("fix it", "iteration-1", Set.of("A.java"));
        FixRequest third = FixRequest.scoped("fix it", "iteration-1", Set.of("A.java"));
        assertEquals(first, second);
        assertEquals(second, third);
        assertEquals(first, third);
    }

    /**
     * Equal instances share a hash code.
     */
    @Test
    void shouldHaveSameHashCodeWhenEqual() {
        FixRequest first = FixRequest.scoped("fix it", "iteration-1", Set.of("A.java"));
        FixRequest second = FixRequest.scoped("fix it", "iteration-1", Set.of("A.java"));
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    /**
     * A hash code does not change during an instance's lifetime.
     */
        @Test
    void shouldKeepHashCodeUnchanged() {
        final FixRequest value = FixRequestMother.random();
        final int initialHashCode = value.hashCode();
        value.toString();
        assertEquals(initialHashCode, value.hashCode());
    }

    /**
     * A null value is refused rather than corrected.
     */
    @Test
    void shouldRejectNullValue() {
        assertThrows(IllegalArgumentException.class, () -> FixRequest.scoped(null, "iteration-1", Set.of("A.java")));
    }

    /**
     * A value that breaks a domain rule is refused at construction.
     */
    @Test
    void shouldRejectInvalidValue() {
        assertThrows(IllegalArgumentException.class, () -> FixRequest.scoped("fix it", null, Set.of("A.java")));
    }
}
