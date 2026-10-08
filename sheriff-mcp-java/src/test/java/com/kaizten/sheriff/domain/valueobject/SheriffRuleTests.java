package com.kaizten.sheriff.domain.valueobject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Tests for the SheriffRule value object.
 *
 * <p>Named and shaped after the behaviours Sheriff's value-object profile
 * requires. It checks the body of each test, not only its name, so every one
 * of these builds its own instances inside the method.
 */
class SheriffRuleTests {

    /**
     * A value object must be final, or a record.
     */
    @Test
    void shouldBeDeclaredFinal() {
        assertTrue(SheriffRule.class.isRecord() || Modifier.isFinal(SheriffRule.class.getModifiers()));
    }

    /**
     * Every instance field must be private and final.
     */
    @Test
    void shouldHaveOnlyPrivateFinalInstanceFields() {
        for (Field field : SheriffRule.class.getDeclaredFields()) {
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
        SheriffRule value = new SheriffRule("Code", "what", "how", "java", "format", "", List.of("JAVA"));
        assertEquals(value, value);
    }

    /**
     * Two instances holding the same value are equal.
     */
    @Test
    void shouldBeEqualToAnotherInstanceWithSameValue() {
        SheriffRule first = new SheriffRule("Code", "what", "how", "java", "format", "", List.of("JAVA"));
        SheriffRule second = new SheriffRule("Code", "what", "how", "java", "format", "", List.of("JAVA"));
        assertEquals(first, second);
    }

    /**
     * Instances holding different values are not equal.
     */
    @Test
    void shouldNotBeEqualToAnotherInstanceWithDifferentValue() {
        SheriffRule first = new SheriffRule("Code", "what", "how", "java", "format", "", List.of("JAVA"));
        SheriffRule second = new SheriffRule("Other", "what", "how", "java", "format", "", List.of("JAVA"));
        assertNotEquals(first, second);
    }

    /**
     * An instance is never equal to null.
     */
    @Test
    void shouldNotBeEqualToNull() {
        SheriffRule value = new SheriffRule("Code", "what", "how", "java", "format", "", List.of("JAVA"));
        assertNotEquals(null, value);
    }

    /**
     * An instance is not equal to an object of another type.
     */
    @Test
    void shouldNotBeEqualToAnObjectOfAnotherType() {
        SheriffRule value = new SheriffRule("Code", "what", "how", "java", "format", "", List.of("JAVA"));
        assertNotEquals(value, "not a SheriffRule");
    }

    /**
     * Equality is symmetric.
     */
    @Test
    void shouldBeSymmetricallyEqual() {
        SheriffRule first = new SheriffRule("Code", "what", "how", "java", "format", "", List.of("JAVA"));
        SheriffRule second = new SheriffRule("Code", "what", "how", "java", "format", "", List.of("JAVA"));
        assertEquals(first, second);
        assertEquals(second, first);
    }

    /**
     * Equality is transitive.
     */
    @Test
    void shouldBeTransitivelyEqual() {
        SheriffRule first = new SheriffRule("Code", "what", "how", "java", "format", "", List.of("JAVA"));
        SheriffRule second = new SheriffRule("Code", "what", "how", "java", "format", "", List.of("JAVA"));
        SheriffRule third = new SheriffRule("Code", "what", "how", "java", "format", "", List.of("JAVA"));
        assertEquals(first, second);
        assertEquals(second, third);
        assertEquals(first, third);
    }

    /**
     * Equal instances share a hash code.
     */
    @Test
    void shouldHaveSameHashCodeWhenEqual() {
        SheriffRule first = new SheriffRule("Code", "what", "how", "java", "format", "", List.of("JAVA"));
        SheriffRule second = new SheriffRule("Code", "what", "how", "java", "format", "", List.of("JAVA"));
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    /**
     * A hash code does not change during an instance's lifetime.
     */
        @Test
    void shouldKeepHashCodeUnchanged() {
        final SheriffRule value = SheriffRuleMother.random();
        final int initialHashCode = value.hashCode();
        value.toString();
        assertEquals(initialHashCode, value.hashCode());
    }

    /**
     * A null value is refused rather than corrected.
     */
    @Test
    void shouldRejectNullValue() {
        assertThrows(IllegalArgumentException.class, () -> new SheriffRule(null, "what", "how", "java", "format", "", List.of("JAVA")));
    }

    /**
     * A value that breaks a domain rule is refused at construction.
     */
    @Test
    void shouldRejectInvalidValue() {
        assertThrows(IllegalArgumentException.class, () -> new SheriffRule("Code", "what", "how", "java", "format", "", null));
    }
}
