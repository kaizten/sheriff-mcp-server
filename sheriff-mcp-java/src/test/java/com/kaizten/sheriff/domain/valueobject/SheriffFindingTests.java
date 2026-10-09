package com.kaizten.sheriff.domain.valueobject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for the SheriffFinding value object.
 *
 * <p>Named and shaped after the behaviours Sheriff's value-object profile
 * requires. It checks the body of each test, not only its name, so every one
 * of these builds its own instances inside the method.
 */
class SheriffFindingTests {

    /**
     * A value object must be final, or a record.
     */
    @Test
    void shouldBeDeclaredFinal() {
        assertTrue(SheriffFinding.class.isRecord() || Modifier.isFinal(SheriffFinding.class.getModifiers()));
    }

    /**
     * Every instance field must be private and final.
     */
    @Test
    void shouldHaveOnlyPrivateFinalInstanceFields() {
        for (Field field : SheriffFinding.class.getDeclaredFields()) {
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
        SheriffFinding value = new SheriffFinding("A.java", "what", "how", "", SheriffFinding.ERROR, Map.of());
        assertEquals(value, value);
    }

    /**
     * Two instances holding the same value are equal.
     */
    @Test
    void shouldBeEqualToAnotherInstanceWithSameValue() {
        SheriffFinding first = new SheriffFinding("A.java", "what", "how", "", SheriffFinding.ERROR, Map.of());
        SheriffFinding second = new SheriffFinding("A.java", "what", "how", "", SheriffFinding.ERROR, Map.of());
        assertEquals(first, second);
    }

    /**
     * Instances holding different values are not equal.
     */
    @Test
    void shouldNotBeEqualToAnotherInstanceWithDifferentValue() {
        SheriffFinding first = new SheriffFinding("A.java", "what", "how", "", SheriffFinding.ERROR, Map.of());
        SheriffFinding second = new SheriffFinding("B.java", "what", "how", "", SheriffFinding.ERROR, Map.of());
        assertNotEquals(first, second);
    }

    /**
     * An instance is never equal to null.
     */
    @Test
    void shouldNotBeEqualToNull() {
        SheriffFinding value = new SheriffFinding("A.java", "what", "how", "", SheriffFinding.ERROR, Map.of());
        assertNotEquals(null, value);
    }

    /**
     * An instance is not equal to an object of another type.
     */
    @Test
    void shouldNotBeEqualToAnObjectOfAnotherType() {
        SheriffFinding value = new SheriffFinding("A.java", "what", "how", "", SheriffFinding.ERROR, Map.of());
        assertNotEquals(value, "not a SheriffFinding");
    }

    /**
     * Equality is symmetric.
     */
    @Test
    void shouldBeSymmetricallyEqual() {
        SheriffFinding first = new SheriffFinding("A.java", "what", "how", "", SheriffFinding.ERROR, Map.of());
        SheriffFinding second = new SheriffFinding("A.java", "what", "how", "", SheriffFinding.ERROR, Map.of());
        assertEquals(first, second);
        assertEquals(second, first);
    }

    /**
     * Equality is transitive.
     */
    @Test
    void shouldBeTransitivelyEqual() {
        SheriffFinding first = new SheriffFinding("A.java", "what", "how", "", SheriffFinding.ERROR, Map.of());
        SheriffFinding second = new SheriffFinding("A.java", "what", "how", "", SheriffFinding.ERROR, Map.of());
        SheriffFinding third = new SheriffFinding("A.java", "what", "how", "", SheriffFinding.ERROR, Map.of());
        assertEquals(first, second);
        assertEquals(second, third);
        assertEquals(first, third);
    }

    /**
     * Equal instances share a hash code.
     */
    @Test
    void shouldHaveSameHashCodeWhenEqual() {
        SheriffFinding first = new SheriffFinding("A.java", "what", "how", "", SheriffFinding.ERROR, Map.of());
        SheriffFinding second = new SheriffFinding("A.java", "what", "how", "", SheriffFinding.ERROR, Map.of());
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    /**
     * A hash code does not change during an instance's lifetime.
     */
        @Test
    void shouldKeepHashCodeUnchanged() {
        final SheriffFinding value = SheriffFindingMother.random();
        final int initialHashCode = value.hashCode();
        value.toString();
        assertEquals(initialHashCode, value.hashCode());
    }

    /**
     * A null value is refused rather than corrected.
     */
    @Test
    void shouldRejectNullValue() {
        assertThrows(IllegalArgumentException.class, () -> new SheriffFinding(null, "what", "how", "", SheriffFinding.ERROR, Map.of()));
    }

    /**
     * A value that breaks a domain rule is refused at construction.
     */
    @Test
    void shouldRejectInvalidValue() {
        assertThrows(IllegalArgumentException.class, () -> new SheriffFinding("A.java", "what", "how", "", SheriffFinding.ERROR, null));
    }
}
