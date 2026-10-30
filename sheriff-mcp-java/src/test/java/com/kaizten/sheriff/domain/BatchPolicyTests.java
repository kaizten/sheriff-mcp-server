package com.kaizten.sheriff.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for how the errors of a pass are grouped, chosen, set aside and
 * counted in passes.
 */
class BatchPolicyTests {

    private static SheriffFinding error(String file, String rule) {
        return new SheriffFinding(file, "d", "h", rule, SheriffFinding.ERROR, Map.of());
    }

    private static final List<SheriffFinding> ERRORS = List.of(
            error("A", "R1"), error("A", "R2"), error("B", "R2"), error("C", "R2"), error("C", "R3"));

    @Nested
    @DisplayName("choosing a pass's errors")
    class Choosing {

        @Test
        @DisplayName("by file, every error of the first files, whatever its rule")
        void byFileTakesEveryRuleOfTheFirstFiles() {
            assertEquals(List.of(error("A", "R1"), error("A", "R2"), error("B", "R2")),
                    BatchPolicy.selectBatch(ERRORS, BatchGrouping.FILE, 2, Set.of()));
        }

        @Test
        @DisplayName("by rule, the rule with the most errors, in at most the files allowed")
        void byRuleTakesTheBiggestRule() {
            assertEquals(List.of(error("A", "R2"), error("B", "R2")),
                    BatchPolicy.selectBatch(ERRORS, BatchGrouping.RULE, 2, Set.of()));
            assertEquals(List.of(error("A", "R2"), error("B", "R2"), error("C", "R2")),
                    BatchPolicy.selectBatch(ERRORS, BatchGrouping.RULE, 0, Set.of()));
        }

        @Test
        @DisplayName("by rule, the first by name among rules with as many errors")
        void byRuleBreaksATieByName() {
            assertEquals(List.of(error("B", "R1")),
                    BatchPolicy.selectBatch(List.of(error("A", "R3"), error("B", "R1")), BatchGrouping.RULE, 0,
                            Set.of()));
        }

        @Test
        @DisplayName("by rule, what is parked is a rule in a file: the file is still offered for its others")
        void byRuleParksARuleInAFile() {
            Set<String> parked = Set.of(BatchPolicy.parkingKey(error("A", "R2"), BatchGrouping.RULE),
                    BatchPolicy.parkingKey(error("B", "R2"), BatchGrouping.RULE));
            assertEquals(List.of(error("A", "R1")),
                    BatchPolicy.selectBatch(ERRORS, BatchGrouping.RULE, 0, parked));
            assertEquals(List.of(), BatchPolicy.selectBatch(List.of(error("A", "R2")), BatchGrouping.RULE, 0,
                    parked));
        }

        @Test
        @DisplayName("an error is parked under its file by file, and its rule in its file by rule")
        void parkingKeys() {
            assertEquals("A", BatchPolicy.parkingKey(error("A", "R1"), BatchGrouping.FILE));
            assertEquals("R1 in A", BatchPolicy.parkingKey(error("A", "R1"), BatchGrouping.RULE));
        }
    }

    @Nested
    @DisplayName("counting the passes")
    class Counting {

        @Test
        @DisplayName("by file, a pass per batch of files with errors, and two to spare")
        void byFileCountsFiles() {
            assertEquals(4, BatchPolicy.estimateMaxIterations(ERRORS, BatchGrouping.FILE, 2));
            assertEquals(BatchPolicy.FALLBACK_MAX_ITERATIONS,
                    BatchPolicy.estimateMaxIterations(List.of(error("", "R1")), BatchGrouping.FILE, 2));
        }

        @Test
        @DisplayName("by rule, a pass per batch of each rule's files, or per rule with no limit")
        void byRuleCountsEachRulesFiles() {
            assertEquals(6, BatchPolicy.estimateMaxIterations(ERRORS, BatchGrouping.RULE, 2));
            assertEquals(5, BatchPolicy.estimateMaxIterations(ERRORS, BatchGrouping.RULE, 0));
            assertEquals(BatchPolicy.FALLBACK_MAX_ITERATIONS,
                    BatchPolicy.estimateMaxIterations(List.of(), BatchGrouping.RULE, 2));
        }

        @Test
        @DisplayName("by rule, a cap grows to what is left, never past three times the first, never shrinks")
        void byRuleGrowsTheCap() {
            assertEquals(8, BatchPolicy.extendedMaxIterations(5, 2, ERRORS, BatchGrouping.RULE, 2, 5));
            assertEquals(6, BatchPolicy.extendedMaxIterations(5, 2, ERRORS, BatchGrouping.RULE, 2, 2));
            assertEquals(9, BatchPolicy.extendedMaxIterations(9, 0, ERRORS, BatchGrouping.RULE, 2, 5));
            assertEquals(5, BatchPolicy.extendedMaxIterations(5, 2, List.of(), BatchGrouping.RULE, 2, 5));
        }

        @Test
        @DisplayName("by file, a cap grows as it always has, from the files with errors")
        void byFileGrowsTheCapFromFiles() {
            assertEquals(6, BatchPolicy.extendedMaxIterations(5, 2, ERRORS, BatchGrouping.FILE, 2, 5));
            assertEquals(5, BatchPolicy.extendedMaxIterations(5, 2, ERRORS, BatchGrouping.FILE, 0, 5));
        }
    }

    @Nested
    @DisplayName("capping a pass's errors")
    class CappingErrors {

        @Test
        @DisplayName("whole files while their errors fit")
        void takesWholeFilesWhileTheyFit() {
            assertEquals(List.of(error("A", "R1"), error("A", "R2"), error("B", "R2")),
                    BatchPolicy.selectBatch(ERRORS, BatchGrouping.FILE, 0, 3, Set.of()));
        }

        @Test
        @DisplayName("a file whose errors alone are more gives its first ones")
        void splitsAFileThatAloneIsMore() {
            assertEquals(List.of(error("A", "R1")), BatchPolicy.selectBatch(ERRORS, BatchGrouping.FILE, 0, 1, Set.of()));
        }

        @Test
        @DisplayName("a file that does not fit is passed over for a later one that does")
        void passesOverAFileThatDoesNotFit() {
            List<SheriffFinding> errors = List.of(error("A", "R1"), error("B", "R1"), error("B", "R2"),
                    error("B", "R3"), error("C", "R1"));
            assertEquals(List.of(error("A", "R1"), error("C", "R1")),
                    BatchPolicy.selectBatch(errors, BatchGrouping.FILE, 0, 2, Set.of()));
        }

        @Test
        @DisplayName("no cap, or one the pass is within, changes nothing")
        void noCapChangesNothing() {
            assertEquals(BatchPolicy.selectBatch(ERRORS, BatchGrouping.FILE, 2, Set.of()),
                    BatchPolicy.selectBatch(ERRORS, BatchGrouping.FILE, 2, 0, Set.of()));
            assertEquals(BatchPolicy.selectBatch(ERRORS, BatchGrouping.FILE, 2, Set.of()),
                    BatchPolicy.selectBatch(ERRORS, BatchGrouping.FILE, 2, 3, Set.of()));
        }

        @Test
        @DisplayName("by rule, the cap applies to the rule's errors too")
        void appliesByRule() {
            assertEquals(List.of(error("A", "R2"), error("B", "R2")),
                    BatchPolicy.selectBatch(ERRORS, BatchGrouping.RULE, 0, 2, Set.of()));
        }

        @Test
        @DisplayName("the passes are the more of what the files and the errors need, and two to spare")
        void countsThePassesTheErrorsNeed() {
            assertEquals(5, BatchPolicy.estimateMaxIterations(ERRORS, BatchGrouping.FILE, 5, 2));
            assertEquals(3, BatchPolicy.estimateMaxIterations(ERRORS, BatchGrouping.FILE, 5, 0));
            assertEquals(3, BatchPolicy.estimateMaxIterations(ERRORS, BatchGrouping.FILE, 0, 5));
            assertEquals(5, BatchPolicy.estimateMaxIterations(ERRORS, BatchGrouping.RULE, 0, 5));
        }

        @Test
        @DisplayName("a cap grows from the errors left, never past three times the first")
        void growsTheCapFromTheErrorsLeft() {
            assertEquals(9, BatchPolicy.extendedMaxIterations(5, 2, ERRORS, BatchGrouping.FILE, 0, 1, 5));
            assertEquals(6, BatchPolicy.extendedMaxIterations(5, 2, ERRORS, BatchGrouping.FILE, 0, 1, 2));
            assertEquals(5, BatchPolicy.extendedMaxIterations(5, 2, ERRORS, BatchGrouping.FILE, 0, 0, 5));
        }
    }

    @Nested
    @DisplayName("naming a grouping")
    class Naming {

        @Test
        @DisplayName("a setting names it in any case, and is named back in lower case")
        void namesAGrouping() {
            assertEquals(BatchGrouping.RULE, BatchGrouping.named(" Rule "));
            assertEquals(BatchGrouping.FILE, BatchGrouping.named("file"));
            assertEquals("rule", BatchGrouping.RULE.setting());
        }

        @Test
        @DisplayName("a name that is neither is refused")
        void refusesAnUnknownName() {
            assertThrows(IllegalArgumentException.class, () -> BatchGrouping.named("folder"));
        }
    }
}
