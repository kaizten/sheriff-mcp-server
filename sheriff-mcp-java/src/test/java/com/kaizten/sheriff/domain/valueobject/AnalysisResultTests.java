package com.kaizten.sheriff.domain.valueobject;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the AnalysisResult value object.
 *
 * <p>Named and shaped after the behaviours Sheriff's value-object profile
 * requires. It checks the body of each test, not only its name, so every one
 * of these builds its own instances inside the method.
 */
class AnalysisResultTests {

    /**
     * A value object must be final, or a record.
     */
    @Test
    void shouldBeDeclaredFinal() {
        assertTrue(AnalysisResult.class.isRecord() || Modifier.isFinal(AnalysisResult.class.getModifiers()));
    }

    /**
     * Every instance field must be private and final.
     */
    @Test
    void shouldHaveOnlyPrivateFinalInstanceFields() {
        for (Field field : AnalysisResult.class.getDeclaredFields()) {
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
        AnalysisResult value = new AnalysisResult(List.of(), false, "");
        assertEquals(value, value);
    }

    /**
     * Two instances holding the same value are equal.
     */
    @Test
    void shouldBeEqualToAnotherInstanceWithSameValue() {
        AnalysisResult first = new AnalysisResult(List.of(), false, "");
        AnalysisResult second = new AnalysisResult(List.of(), false, "");
        assertEquals(first, second);
    }

    /**
     * Instances holding different values are not equal.
     */
    @Test
    void shouldNotBeEqualToAnotherInstanceWithDifferentValue() {
        AnalysisResult first = new AnalysisResult(List.of(), false, "");
        AnalysisResult second = new AnalysisResult(List.of(), true, "docker down");
        assertNotEquals(first, second);
    }

    /**
     * An instance is never equal to null.
     */
    @Test
    void shouldNotBeEqualToNull() {
        AnalysisResult value = new AnalysisResult(List.of(), false, "");
        assertNotEquals(null, value);
    }

    /**
     * An instance is not equal to an object of another type.
     */
    @Test
    void shouldNotBeEqualToAnObjectOfAnotherType() {
        AnalysisResult value = new AnalysisResult(List.of(), false, "");
        assertNotEquals(value, "not a AnalysisResult");
    }

    /**
     * Equality is symmetric.
     */
    @Test
    void shouldBeSymmetricallyEqual() {
        AnalysisResult first = new AnalysisResult(List.of(), false, "");
        AnalysisResult second = new AnalysisResult(List.of(), false, "");
        assertEquals(first, second);
        assertEquals(second, first);
    }

    /**
     * Equality is transitive.
     */
    @Test
    void shouldBeTransitivelyEqual() {
        AnalysisResult first = new AnalysisResult(List.of(), false, "");
        AnalysisResult second = new AnalysisResult(List.of(), false, "");
        AnalysisResult third = new AnalysisResult(List.of(), false, "");
        assertEquals(first, second);
        assertEquals(second, third);
        assertEquals(first, third);
    }

    /**
     * Equal instances share a hash code.
     */
    @Test
    void shouldHaveSameHashCodeWhenEqual() {
        AnalysisResult first = new AnalysisResult(List.of(), false, "");
        AnalysisResult second = new AnalysisResult(List.of(), false, "");
        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }

    /**
     * A hash code does not change during an instance's lifetime.
     */
        @Test
    void shouldKeepHashCodeUnchanged() {
        final AnalysisResult value = AnalysisResultMother.random();
        final int initialHashCode = value.hashCode();
        value.toString();
        assertEquals(initialHashCode, value.hashCode());
    }

    /**
     * A null value is refused rather than corrected.
     */
    @Test
    void shouldRejectNullValue() {
        assertThrows(IllegalArgumentException.class, () -> new AnalysisResult(null, false, ""));
    }

    /**
     * A value that breaks a domain rule is refused at construction.
     */
    @Test
    void shouldRejectInvalidValue() {
        assertThrows(IllegalArgumentException.class, () -> new AnalysisResult(List.of(), false, null));
    }
    @Test
    void shouldReturnImmutableCollection() {
        final AnalysisResult value = AnalysisResultMother.random();
        assertThrows(UnsupportedOperationException.class, () -> value.errors().clear());
        assertThrows(UnsupportedOperationException.class, () -> value.filesWithErrors().clear());
        assertThrows(UnsupportedOperationException.class, () -> value.findings().clear());
        assertThrows(UnsupportedOperationException.class, () -> value.warnings().clear());
    }

    @Test
    @DisplayName("analyses under several profiles merge into one: each finding and file once, a failure wins")
    void severalProfilesMergeIntoOneResult() {
        SheriffFinding shared = new SheriffFinding("A.java", "no JavaDoc", "add one", "JavaDoc", "ERROR", Map.of());
        SheriffFinding layer = new SheriffFinding("A.java", "wrong folder", "move it", "Layer", "ERROR", Map.of());
        AnalysisResult base = AnalysisResult.of(List.of(shared), List.of(new TrackedFile("A.java", "h")));
        AnalysisResult architecture = AnalysisResult.of(List.of(shared, layer), List.of(new TrackedFile("A.java", "h")));

        AnalysisResult merged = AnalysisResult.merged(List.of(base, architecture));

        assertEquals(List.of(shared, layer), merged.findings());
        assertEquals(List.of(new TrackedFile("A.java", "h")), merged.trackedFiles());
        assertTrue(AnalysisResult.merged(List.of(base, AnalysisResult.failure("no docker"))).error());
    }
}
