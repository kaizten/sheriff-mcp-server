package com.kaizten.sheriff.infrastructure.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The steps that follow a measure of how well the tests test a change: one
 * each, last, and with no alternative in it.
 */
class NextStepTest {

    private static final String CALL = "call the sheriff_fix tool with component 'app' and verify true";

    private static void isOneStepLast(String answer) {
        assertEquals(answer, NextStep.last(answer), "the step is the answer's last line");
        assertEquals(answer.indexOf(NextStep.MARK), answer.lastIndexOf(NextStep.MARK), "one step: " + answer);
        assertFalse(answer.contains(" or "), "an alternative is a choice a model makes differently: " + answer);
    }

    @Test
    @DisplayName("a clean change is measured through the repair with verify")
    void aCleanChangeIsMeasured() {
        String step = NextStep.afterCleanToMeasure(CALL);
        assertTrue(step.contains(NextStep.MARK + " " + CALL + ". It runs the project's tests"), step);
        isOneStepLast(step);
    }

    @Test
    @DisplayName("while an idea is tried out, a clean component is done, and the step says why its tests are left")
    void aCleanPrototypeIsDone() {
        String step = NextStep.afterCleanPrototype();
        assertTrue(step.contains(NextStep.MARK + " none, the component is done"), step);
        assertTrue(step.contains("SHERIFF_PROTOTYPE is 1"), step);
        isOneStepLast(step);
    }

    @Test
    @DisplayName("tests that reach the thresholds are done, with how well they test the change")
    void measuredTestsAreDone() {
        String step = NextStep.afterMeasuredTests("The tests run 3 of the 3 lines.");
        assertTrue(step.contains("The tests run 3 of the 3 lines."), step);
        assertTrue(step.endsWith("the component is done: it meets the standards and its tests pass."), step);
        isOneStepLast(step);
    }

    @Test
    @DisplayName("weak tests list what is left and come back through the repair with verify")
    void weakTestsComeBack() {
        String step = NextStep.afterWeakTests("The tests run 1 of the 2 lines.", "\n  - A.java: 3", CALL);
        assertTrue(step.contains("\n  - A.java: 3\n" + NextStep.MARK + " add tests"), step);
        assertTrue(step.endsWith("Then " + CALL + " again."), step);
        isOneStepLast(step);
    }

    @Test
    @DisplayName("a measure that could not run ends the work, and the user is told why")
    void anUnmeasuredChangeIsDone() {
        String step = NextStep.afterUnmeasuredTests("PIT wrote no report.");
        assertTrue(step.contains("could not be measured: PIT wrote no report."), step);
        assertTrue(step.contains("the component is done"), step);
        isOneStepLast(step);
    }
}
