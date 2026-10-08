package com.kaizten.sheriff.domain.enumerate;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the StopReason enumerate.
 *
 * <p>The bodies below are Sheriff's own: each one is what its fixer script
 * writes for the rule it belongs to, so the shape is the tool's answer rather
 * than a guess at one.
 */
class StopReasonTests {

    @Test
    void shouldReturnAllConstantsInDeclarationOrder() {
        assertArrayEquals(
                new StopReason[]{
                        StopReason.SUCCESS,
                        StopReason.SUCCESS_AFTER_REPAIR,
                        StopReason.STALLED,
                        StopReason.FIXER_FAILED,
                        StopReason.OUT_OF_SCOPE,
                        StopReason.INFRA_ERROR,
                        StopReason.REPAIR_FAILED,
                        StopReason.ITERATIONS_EXHAUSTED
                },
                StopReason.values());
    }
    @Test
    void shouldReturnEachConstantForItsName() {
        for (StopReason stopReason : StopReason.values()) {
            assertSame(stopReason, StopReason.valueOf(stopReason.name()));
        }
    }
    @Test
    void shouldThrowIllegalArgumentExceptionForUnknownName() {
        assertThrows(IllegalArgumentException.class, () -> StopReason.valueOf("UNKNOWN_THIS_IS_A_RaNDOM_CHAIN"));
    }
    @Test
    void shouldThrowNullPointerExceptionForNullName() {
        assertThrows(NullPointerException.class, () -> StopReason.valueOf(null));
    }
    @Test
    void shouldReturnExpectedConstantWhenFromStringReceivesValidName() {
        for (StopReason stopReason : StopReason.values()) {
            assertSame(stopReason, StopReason.fromString(stopReason.name()));
        }
    }
    @Test
    void shouldThrowIllegalArgumentExceptionWhenFromStringReceivesUnknownName() {
        assertThrows(IllegalArgumentException.class, () -> StopReason.fromString("UNKNOWN_THIS_IS_not_A_VALID_ENUMERATE"));
    }
    @Test
    void shouldThrowIllegalArgumentExceptionWhenFromStringReceivesEmptyString() {
        assertThrows(IllegalArgumentException.class, () -> StopReason.fromString(""));
    }
    @Test
    void shouldThrowNullPointerExceptionWhenFromStringReceivesNull() {
        assertThrows(NullPointerException.class, () -> StopReason.fromString(null));
    }
    @Test
    void shouldRejectDifferentCaseWhenFromStringIsCaseSensitive() {
        final String differentCaseName = StopReason.values()[0].name().toLowerCase();
        assertThrows(IllegalArgumentException.class, () -> StopReason.fromString(differentCaseName));
    }
    @Test
    void shouldReturnExpectedIndexWhenIndexOfReceivesValidName() {
        for (StopReason stopReason : StopReason.values()) {
            assertEquals(stopReason.ordinal(), StopReason.indexOf(stopReason.name()));
        }
    }
    @Test
    void shouldReturnMinusOneWhenIndexOfReceivesUnknownName() {
        assertEquals(-1, StopReason.indexOf("UNKNOWN"));
    }
    @Test
    void shouldReturnMinusOneWhenIndexOfReceivesEmptyString() {
        assertEquals(-1, StopReason.indexOf(""));
    }
    @Test
    void shouldReturnMinusOneWhenIndexOfReceivesNull() {
        assertEquals(-1, StopReason.indexOf(null));
    }
    @Test
    void shouldReturnMinusOneWhenIndexOfReceivesNameWithDifferentCase() {
        assertEquals(-1, StopReason.indexOf("success"));
    }
    @Test
    void shouldReturnTrueWhenIsValidReceivesValidName() {
        for (StopReason value : StopReason.values()) {
            assertTrue(StopReason.isValid(value.name()));
        }
    }
    @Test
    void shouldReturnFalseWhenIsValidReceivesUnknownName() {
        assertFalse(StopReason.isValid("UNKNOWN"));
    }
    @Test
    void shouldReturnFalseWhenIsValidReceivesNameWithDifferentCase() {
        assertFalse(StopReason.isValid("success"));
    }
    @Test
    void shouldReturnFalseWhenIsValidReceivesEmptyString() {
        assertFalse(StopReason.isValid(""));
    }
    @Test
    void shouldReturnFalseWhenIsValidReceivesNull() {
        assertFalse(StopReason.isValid(null));
    }
    @Test
    void shouldReturnExistingConstantWhenRandomIsCalled() {
        assertNotNull(StopReason.random());
    }
    @Test
    void shouldAlwaysReturnExistingConstantWhenRandomIsCalledRepeatedly() {
        for (int iteration = 0; iteration < 100; iteration++) {
            assertTrue(Arrays.asList(StopReason.values()).contains(StopReason.random()));
        }
    }
}
