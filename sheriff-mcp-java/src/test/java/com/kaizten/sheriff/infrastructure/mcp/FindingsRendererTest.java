package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The bounded, per-file rendering {@link FindingsRenderer} answers with, and
 * the two guarantees it makes: a file's errors are never split, and the
 * total is never hidden even when the detail is.
 */
final class FindingsRendererTest {

    private static final String FIX_TOOL = "sheriff_fix";

    private static SheriffFinding finding(String file, String description, String referenceCode) {
        return new SheriffFinding(file, description, "rename it", referenceCode, SheriffFinding.ERROR, Map.of());
    }

    private static SheriffRule rule(String code, boolean hasFixer) {
        return new SheriffRule(code, "template", "", "java", "", hasFixer ? "fixer.py" : "", List.of("JAVA"));
    }

    @Test
    void rendersEachFileOnceWithEveryFindingUnderIt() {
        List<SheriffFinding> errors = List.of(
                finding("A.java", "first problem", "RULE_A"),
                finding("A.java", "second problem", "RULE_A"),
                finding("B.java", "third problem", "RULE_B"));
        FindingsRenderer renderer = new FindingsRenderer(List.of());

        String rendered = renderer.render(errors, FIX_TOOL);

        assertTrue(rendered.contains("A.java"));
        assertTrue(rendered.contains("first problem"));
        assertTrue(rendered.contains("second problem"));
        assertTrue(rendered.contains("B.java"));
        assertTrue(rendered.contains("third problem"));
    }

    @Test
    void namesTheRuleAndOffersTheFixerWhenTheCatalogHasOne() {
        List<SheriffFinding> errors = List.of(finding("A.java", "first problem", "RULE_A"));
        FindingsRenderer renderer = new FindingsRenderer(List.of(rule("RULE_A", true)));

        String rendered = renderer.render(errors, FIX_TOOL);

        assertTrue(rendered.contains("RULE_A"));
        assertTrue(rendered.contains("[RULE_A: " + FIX_TOOL + " can repair this one]"), rendered);
        assertEquals(Set.of("RULE_A"), renderer.repairableRules(errors, Set.of()));
    }

    @Test
    void aFixerThatJustFailedIsNotOfferedAgain() {
        List<SheriffFinding> errors = List.of(
                finding("A.java", "first problem", "RULE_A"),
                finding("A.java", "second problem", "RULE_B"));
        FindingsRenderer renderer = new FindingsRenderer(List.of(rule("RULE_A", true), rule("RULE_B", true)));

        String rendered = renderer.render(errors, FIX_TOOL, Set.of("RULE_A"));

        assertTrue(rendered.contains("[RULE_A: its fixer did not repair it, edit it by hand]"), rendered);
        assertTrue(rendered.contains("[RULE_B: " + FIX_TOOL + " can repair this one]"), rendered);
        assertEquals(Set.of("RULE_B"), renderer.repairableRules(errors, Set.of("RULE_A")));
        assertFalse(rendered.contains("reference_code"), "the answer names one step, not a choice of codes");
    }

    @Test
    void marksARecoveredRuleAsRecoveredRatherThanAsSheriffsOwnCode() {
        List<SheriffFinding> errors = List.of(finding("A.java", "template", ""));
        FindingsRenderer renderer = new FindingsRenderer(List.of(rule("RULE_A", false)));

        String rendered = renderer.render(errors, FIX_TOOL);

        assertTrue(rendered.contains("recovered from the message"));
    }

    @Test
    void aFilesErrorsAreNeverSplitAcrossTheBudget() {
        List<SheriffFinding> errors = new ArrayList<>();
        for (int index = 0; index < FindingsRenderer.MAX_FINDINGS_RENDERED + 5; index++) {
            errors.add(finding("A.java", "problem " + index, ""));
        }
        errors.add(finding("B.java", "a single problem", ""));
        FindingsRenderer renderer = new FindingsRenderer(List.of());

        String rendered = renderer.render(errors, FIX_TOOL);

        assertTrue(rendered.contains("A.java"));
        assertTrue(rendered.contains("problem 0"));
        assertTrue(rendered.contains("problem " + (FindingsRenderer.MAX_FINDINGS_RENDERED + 4)));
        assertFalse(rendered.contains("a single problem"));
        assertTrue(rendered.contains("B.java: 1 error(s)"));
    }

    @Test
    @DisplayName("a file's errors run from its last line up, as an edit moves every line below it")
    void listsAFilesErrorsFromTheBottomUp() {
        List<SheriffFinding> errors = List.of(
                finding("A.java", "Line comment found at line 5.", "RULE_A"),
                finding("A.java", "Method 'a' does not have JavaDoc comment", "RULE_B"),
                finding("A.java", "Comment found inside method 'b' at lines 30-31.", "RULE_A"),
                finding("A.java", "Hardcoded number '1' found in sentence 'x < 1' (line 12).", "RULE_C"));

        List<String> order = FindingsRenderer.bottomUp(errors).stream().map(SheriffFinding::description).toList();

        assertEquals(List.of("Comment found inside method 'b' at lines 30-31.",
                "Hardcoded number '1' found in sentence 'x < 1' (line 12).", "Line comment found at line 5.",
                "Method 'a' does not have JavaDoc comment"), order);
    }

    @Test
    @DisplayName("the folder every file shares is written once, and the advice of a rule once per answer")
    void writesSharedFolderAndAdviceOnce() {
        List<SheriffFinding> errors = List.of(
                new SheriffFinding("app/src/main/java/demo/A.java", "Line comment found at line 9.", "Remove it.",
                        "RULE_A", SheriffFinding.ERROR, java.util.Map.of()),
                new SheriffFinding("app/src/main/java/demo/A.java", "Line comment found at line 4.", "Remove it.",
                        "RULE_A", SheriffFinding.ERROR, java.util.Map.of()),
                new SheriffFinding("app/src/main/java/demo/sub/B.java", "Comment found inside method 'b' at lines 2-2.",
                        "Remove the comment.", "JavaDocCommentsInMethod", SheriffFinding.ERROR, java.util.Map.of()));

        String rendered = new FindingsRenderer(List.of()).render(errors, FIX_TOOL);

        assertTrue(rendered.contains("Paths below are under app/src/main/java/demo/"), rendered);
        assertTrue(rendered.contains("\nA.java") && rendered.contains("\nsub/B.java"), rendered);
        assertEquals(1, rendered.split("how: Remove it.", -1).length - 1, "advice once per rule: " + rendered);
        assertFalse(rendered.contains("how: Remove the comment."), "a rule with an accepted shape needs no advice");
    }

    @Test
    @DisplayName("an analysis answers with the counts: the repair after it lists the errors")
    void summarisesByFileAndRule() {
        List<SheriffFinding> errors = List.of(finding("app/src/A.java", "one", "RULE_A"),
                finding("app/src/A.java", "two", "RULE_B"), finding("app/src/B.java", "three", "RULE_A"));

        String summary = new FindingsRenderer(List.of()).summary(errors);

        assertTrue(summary.contains("  A.java: 2") && summary.contains("  B.java: 1"), summary);
        assertTrue(summary.contains("By rule: RULE_A 2, RULE_B 1"), summary);
        assertFalse(summary.contains("one"), "no error is spelled out: " + summary);
    }
}
