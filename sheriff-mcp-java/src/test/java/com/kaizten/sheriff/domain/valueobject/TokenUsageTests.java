package com.kaizten.sheriff.domain.valueobject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

/**
 * Tests for the TokenUsage value object.
 *
 * <p>Named after the behaviours Sheriff's value-object profile requires, to
 * check whether it recognises them by method name.
 */
class TokenUsageTests {

    /**
     * A value object must be final, or a record.
     */
    @Test
    void shouldBeDeclaredFinal() {
        assertTrue(TokenUsage.class.isRecord() || Modifier.isFinal(TokenUsage.class.getModifiers()));
    }

    /**
     * Every instance field must be private and final.
     */
    @Test
    void shouldHaveOnlyPrivateFinalInstanceFields() {
        for (Field field : TokenUsage.class.getDeclaredFields()) {
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
        TokenUsage result = TokenUsageMother.random();
        assertEquals(result, result);
    }

    /**
     * Two instances holding the same value are equal.
     */
    @Test
    void shouldBeEqualToAnotherInstanceWithSameValue() {
        TokenUsage first = new TokenUsage(List.of("m"), 1, 2, 3, 4, 5, 0.5);
        TokenUsage second = new TokenUsage(List.of("m"), 1, 2, 3, 4, 5, 0.5);
        assertEquals(first, second);
    }

    /**
     * Instances holding different values are not equal.
     */
    @Test
    void shouldNotBeEqualToAnotherInstanceWithDifferentValue() {
        TokenUsage first = TokenUsageMother.random();
        TokenUsage second = TokenUsageMother.differentTo(TokenUsageMother.random());
        assertNotEquals(first, second);
    }

    /**
     * An instance is never equal to null.
     */
    @Test
    void shouldNotBeEqualToNull() {
        TokenUsage result = TokenUsageMother.random();
        assertNotEquals(null, result);
    }

    /**
     * An instance is not equal to an object of another type.
     */
    @Test
    void shouldNotBeEqualToAnObjectOfAnotherType() {
        TokenUsage result = TokenUsageMother.random();
        assertNotEquals(result, "another type");
    }

    /**
     * Equality is symmetric.
     */
    @Test
    void shouldBeSymmetricallyEqual() {
        TokenUsage first = TokenUsageMother.random();
        TokenUsage second = TokenUsageMother.random();
        assertEquals(first, second);
        assertEquals(second, first);
    }

    /**
     * Equality is transitive.
     */
    @Test
    void shouldBeTransitivelyEqual() {
        TokenUsage first = TokenUsageMother.random();
        TokenUsage second = TokenUsageMother.random();
        TokenUsage third = TokenUsageMother.random();
        assertEquals(first, second);
        assertEquals(second, third);
        assertEquals(first, third);
    }

    /**
     * Equal instances share a hash code.
     */
    @Test
    void shouldHaveSameHashCodeWhenEqual() {
        TokenUsage first = TokenUsageMother.random();
        TokenUsage second = TokenUsageMother.random();
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    /**
     * A hash code does not change during an instance's lifetime.
     */
        @Test
    void shouldKeepHashCodeUnchanged() {
        final TokenUsage value = TokenUsageMother.random();
        final int initialHashCode = value.hashCode();
        value.toString();
        assertEquals(initialHashCode, value.hashCode());
    }

    /**
     * The total adds every kind of token, and a cost may be unknown.
     */
    @Test
    void shouldAddEveryTokenIntoTheTotal() {
        assertEquals(100, TokenUsageMother.random().totalTokens());
        assertEquals(null, new TokenUsage(List.of(), 1, 1, 1, 1, 1, null).costUsd());
        assertFalse(new TokenUsage(List.of(), 1, 1, 1, 1, 1, null).models().iterator().hasNext());
    }

    /**
     * Every count may be zero, and each one below zero is refused on its own.
     */
    @Test
    void shouldRefuseEachNegativeCount() {
        assertEquals(0, new TokenUsage(List.of(), 0, 0, 0, 0, 0, 0.0).totalTokens());
        assertThrows(IllegalArgumentException.class, () -> new TokenUsage(List.of(), -1, 0, 0, 0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new TokenUsage(List.of(), 0, -1, 0, 0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new TokenUsage(List.of(), 0, 0, -1, 0, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new TokenUsage(List.of(), 0, 0, 0, -1, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new TokenUsage(List.of(), 0, 0, 0, 0, -1, null));
        assertThrows(IllegalArgumentException.class, () -> new TokenUsage(List.of(), 0, 0, 0, 0, 0, -0.01));
    }

    /**
     * A null value is refused rather than corrected.
     */
    @Test
    void shouldRejectNullValue() {
        assertThrows(IllegalArgumentException.class, () -> TokenUsageMother.generateInvalidValue());
    }

    /**
     * A value that breaks a domain rule is refused at construction.
     */
    @Test
    void shouldRejectInvalidValue() {
        assertThrows(IllegalArgumentException.class, () -> new TokenUsage(List.of(), -1, 0, 0, 0, 0, null));
    }
}
