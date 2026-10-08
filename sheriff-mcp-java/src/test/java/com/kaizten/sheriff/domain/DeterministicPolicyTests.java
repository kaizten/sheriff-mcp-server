package com.kaizten.sheriff.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for which findings Sheriff can repair without a model.
 *
 * <p>The two exclusions are the point: a finding with no reference code cannot
 * be handed to {@code --fixers}, and a code with no fixer has nothing to hand
 * it to. Both are common enough that getting them wrong would mean spending a
 * Docker run to repair nothing.
 */
class DeterministicPolicyTests {

    private static final List<SheriffRule> CATALOG = List.of(
            new SheriffRule("SortedImport", "unsorted", "", "java", "", "java/sort-imports.py", List.of("JAVA")),
            new SheriffRule("EmptyLinesInMethod", "blank line", "", "java", "", "java/empty.py", List.of("JAVA")),
            new SheriffRule("JavaDocCommentsInMethod", "no javadoc", "", "java", "", "", List.of("JAVA")));

    private static SheriffFinding finding(String referenceCode) {
        return new SheriffFinding("A.java", "x", "y", referenceCode, SheriffFinding.ERROR, Map.of());
    }

    @Test
    void picksTheCodesThatHaveAFixer() {
        List<SheriffFinding> findings = List.of(finding("SortedImport"), finding("EmptyLinesInMethod"));
        assertEquals(List.of("SortedImport", "EmptyLinesInMethod"),
                DeterministicPolicy.fixableCodes(findings, CATALOG));
    }

    @Test
    @DisplayName("a rule with no fixer is left for the AI, however often it appears")
    void skipsCodesWithNoFixer() {
        List<SheriffFinding> findings = List.of(finding("JavaDocCommentsInMethod"), finding("SortedImport"));
        assertEquals(List.of("SortedImport"), DeterministicPolicy.fixableCodes(findings, CATALOG));
    }

    @Test
    @DisplayName("findings with no reference code cannot be handed to --fixers at all")
    void skipsFindingsWithNoCode() {
        assertEquals(List.of(), DeterministicPolicy.fixableCodes(List.of(finding("")), CATALOG));
    }

    @Test
    void deduplicatesWithoutLosingOrder() {
        List<SheriffFinding> findings = List.of(
                finding("EmptyLinesInMethod"), finding("SortedImport"), finding("EmptyLinesInMethod"));
        assertEquals(List.of("EmptyLinesInMethod", "SortedImport"),
                DeterministicPolicy.fixableCodes(findings, CATALOG));
    }

    @Test
    @DisplayName("counting says whether the pass is worth a Docker run at all")
    void countsHowManyFindingsThePassWouldTakeOn() {
        List<SheriffFinding> findings = List.of(
                finding("EmptyLinesInMethod"), finding("EmptyLinesInMethod"),
                finding("JavaDocCommentsInMethod"), finding(""));
        assertEquals(2, DeterministicPolicy.countFixable(findings, CATALOG));
        assertEquals(0, DeterministicPolicy.countFixable(List.of(finding("")), CATALOG));
    }

    @Test
    void anEmptyCatalogMeansNothingIsFixableForFree() {
        assertEquals(List.of(), DeterministicPolicy.fixableCodes(List.of(finding("SortedImport")), List.of()));
    }
}
