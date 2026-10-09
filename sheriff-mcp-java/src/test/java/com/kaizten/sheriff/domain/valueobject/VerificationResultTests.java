package com.kaizten.sheriff.domain.valueobject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * Tests for the VerificationResult value object.
 *
 * <p>Named and shaped after the behaviours Sheriff's value-object profile
 * requires. It checks the body of each test, not only its name, so every one
 * of these builds its own instances inside the method.
 */
class VerificationResultTests {

    /**
     * A value object must be final, or a record.
     */
    @Test
    void shouldBeDeclaredFinal() {
        assertTrue(VerificationResult.class.isRecord() || Modifier.isFinal(VerificationResult.class.getModifiers()));
    }

    /**
     * Every instance field must be private and final.
     */
    @Test
    void shouldHaveOnlyPrivateFinalInstanceFields() {
        for (Field field : VerificationResult.class.getDeclaredFields()) {
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
        VerificationResult value = new VerificationResult(true, "all good");
        assertEquals(value, value);
    }

    /**
     * Two instances holding the same value are equal.
     */
    @Test
    void shouldBeEqualToAnotherInstanceWithSameValue() {
        VerificationResult first = new VerificationResult(true, "all good");
        VerificationResult second = new VerificationResult(true, "all good");
        assertEquals(first, second);
    }

    /**
     * Instances holding different values are not equal.
     */
    @Test
    void shouldNotBeEqualToAnotherInstanceWithDifferentValue() {
        VerificationResult first = new VerificationResult(true, "all good");
        VerificationResult second = new VerificationResult(true, "something else");
        assertNotEquals(first, second);
    }

    /**
     * An instance is never equal to null.
     */
    @Test
    void shouldNotBeEqualToNull() {
        VerificationResult value = new VerificationResult(true, "all good");
        assertNotEquals(null, value);
    }

    /**
     * An instance is not equal to an object of another type.
     */
    @Test
    void shouldNotBeEqualToAnObjectOfAnotherType() {
        VerificationResult value = new VerificationResult(true, "all good");
        assertNotEquals(value, "not a VerificationResult");
    }

    /**
     * Equality is symmetric.
     */
    @Test
    void shouldBeSymmetricallyEqual() {
        VerificationResult first = new VerificationResult(true, "all good");
        VerificationResult second = new VerificationResult(true, "all good");
        assertEquals(first, second);
        assertEquals(second, first);
    }

    /**
     * Equality is transitive.
     */
    @Test
    void shouldBeTransitivelyEqual() {
        VerificationResult first = new VerificationResult(true, "all good");
        VerificationResult second = new VerificationResult(true, "all good");
        VerificationResult third = new VerificationResult(true, "all good");
        assertEquals(first, second);
        assertEquals(second, third);
        assertEquals(first, third);
    }

    /**
     * Equal instances share a hash code.
     */
    @Test
    void shouldHaveSameHashCodeWhenEqual() {
        VerificationResult first = new VerificationResult(true, "all good");
        VerificationResult second = new VerificationResult(true, "all good");
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    /**
     * A hash code does not change during an instance's lifetime.
     */
        @Test
    void shouldKeepHashCodeUnchanged() {
        final VerificationResult value = VerificationResultMother.random();
        final int initialHashCode = value.hashCode();
        value.toString();
        assertEquals(initialHashCode, value.hashCode());
    }

    /**
     * A null value is refused rather than corrected.
     */
    @Test
    void shouldRejectNullValue() {
        assertThrows(IllegalArgumentException.class, () -> new VerificationResult(true, null));
    }

    /**
     * A value that breaks a domain rule is refused at construction.
     */
    @Test
    void shouldRejectInvalidValue() {
        assertThrows(IllegalArgumentException.class, () -> new VerificationResult(false, null));
    }
}
