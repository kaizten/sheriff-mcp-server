package com.kaizten.sheriff.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.enumerate.StopReason;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.FixRequest;
import com.kaizten.sheriff.domain.valueobject.LoopSettings;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Tests for the domain's value objects. */
class ValueObjectsTests {

    @Nested
    @DisplayName("a finding: a rule already broken")
    class Findings {

        private final SheriffFinding finding = new SheriffFinding(
                "Calculator.java", "Method 'add' does not have JavaDoc comment", "Add a JavaDoc comment.",
                "SomeRule", SheriffFinding.ERROR, Map.of("timestamp", "now"));

        @Test
        void exposesItsFields() {
            assertEquals("Calculator.java", finding.file());
            assertEquals("SomeRule", finding.referenceCode());
            assertFalse(finding.isWarning());
        }

        @Test
        void warningsAreDistinguishedFromErrors() {
            assertTrue(new SheriffFinding("a", "b", "c", "", SheriffFinding.WARNING, Map.of()).isWarning());
        }

        @Test
        @DisplayName("a missing type means an error, not an unknown")
        void defaultsToError() {
            assertFalse(new SheriffFinding("a", "b", "c", "", null, Map.of()).isWarning());
        }

        @Test
        void describesItselfInOneLine() {
            assertTrue(finding.describe().startsWith("Calculator.java: Method 'add'"));
            assertTrue(finding.describe().contains("-> Add a JavaDoc comment."));
        }

        @Test
        @DisplayName("with no description it falls back to the raw entry rather than lying")
        void fallsBackToTheRawEntry() {
            SheriffFinding bare = new SheriffFinding("a", "", "", "", null, Map.of("odd", "shape"));
            assertTrue(bare.describe().contains("odd"));
        }

        @Test
        void theRawEntryCannotBeMutatedAfterwards() {
            assertThrows(UnsupportedOperationException.class, () -> finding.raw().put("x", "y"));
        }
    }

    @Nested
    @DisplayName("an analysis result")
    class Results {

        private final AnalysisResult mixed = AnalysisResult.of(List.of(
                new SheriffFinding("a.java", "x", "y", "", SheriffFinding.ERROR, Map.of()),
                new SheriffFinding("b.java", "x", "y", "", SheriffFinding.WARNING, Map.of()),
                new SheriffFinding("a.java", "x", "y", "", SheriffFinding.ERROR, Map.of())));

        @Test
        @DisplayName("only errors count towards the total the loop stops on")
        void separatesErrorsFromWarnings() {
            assertEquals(2, mixed.errors().size());
            assertEquals(1, mixed.warnings().size());
            assertEquals(2, mixed.total());
        }

        @Test
        void listsTheDistinctFilesWithErrors() {
            assertEquals(Set.of("a.java"), mixed.filesWithErrors());
        }

        @Test
        @DisplayName("a failure to run is not the same as finding nothing")
        void failureIsNotEmptiness() {
            AnalysisResult failure = AnalysisResult.failure("docker down");
            assertTrue(failure.error());
            assertEquals(0, failure.total());
            assertEquals("docker down", failure.message());
            assertFalse(AnalysisResult.of(List.of()).error());
        }
    }

    @Nested
    @DisplayName("a fix request")
    class Requests {

        @Test
        void aScopedRequestNamesTheFilesItMayTouch() {
            FixRequest request = FixRequest.scoped("p", "iteration-1", Set.of("a.java"));
            assertTrue(request.isScoped());
            assertEquals(Set.of("a.java"), request.allowedFiles());
        }

        @Test
        @DisplayName("the repair pass carries no scope at all, which is not the same as an empty one")
        void anUnrestrictedRequestHasNoScope() {
            FixRequest request = FixRequest.unrestricted("p", "repair");
            assertFalse(request.isScoped());
            assertEquals(null, request.allowedFiles());
        }
    }

    @Nested
    @DisplayName("a rule: something Sheriff checks for")
    class Rules {

        private final SheriffRule rule = new SheriffRule(
                "JavaDocCommentsInMethod", "JavaDoc comment found in method '%s'.", "Remove it.",
                "java", "format", "", List.of("JAVA", "JAVA_HEXAGONAL"));

        @Test
        void describeIncludesHowToSolveWhenThereIsOne() {
            assertTrue(rule.describe().contains("JavaDocCommentsInMethod: JavaDoc comment"));
            assertTrue(rule.describe().contains("-> Remove it."));
        }

        @Test
        void describeFallsBackToTheCodeAlone() {
            assertEquals("Bare", new SheriffRule("Bare", "", "", "", "", "", List.of()).describe());
        }

        @Test
        void knowsWhetherSheriffCanFixItItself() {
            assertFalse(rule.hasFixer());
            assertTrue(new SheriffRule("X", "", "", "", "", "java/x.jar", List.of()).hasFixer());
        }

        @Test
        void matchesIsCaseInsensitive() {
            assertTrue(rule.matches("JAVADOC"));
            assertFalse(rule.matches("typescript"));
        }

        @Test
        void knowsWhichProfilesRunIt() {
            assertTrue(rule.runsIn("JAVA"));
            assertFalse(rule.runsIn("TYPESCRIPT"));
        }
    }

    @Nested
    @DisplayName("loop settings and stop reasons")
    class Settings {

        @Test
        void defaultsBatchAtFiveFilesWithGitSafetyOn() {
            LoopSettings defaults = LoopSettings.defaults();
            assertEquals(null, defaults.maxIterations());
            assertTrue(defaults.useGitSafety());
            assertTrue(defaults.batches());
            assertFalse(new LoopSettings(null, true, 0).batches());
        }

        @Test
        @DisplayName("the wire names are what --json-report has always emitted")
        void stopReasonsKeepTheirWireNames() {
            assertEquals("success_after_repair", StopReason.SUCCESS_AFTER_REPAIR.wireName());
            assertEquals("iterations_exhausted", StopReason.ITERATIONS_EXHAUSTED.wireName());
            assertEquals("out_of_scope", StopReason.OUT_OF_SCOPE.wireName());
        }
    }
}
