package com.kaizten.sheriff.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.application.service.FixLoop;
import com.kaizten.sheriff.domain.BatchPolicy;
import com.kaizten.sheriff.domain.enumerate.StopReason;
import com.kaizten.sheriff.domain.fake.FakeAnalyzer;
import com.kaizten.sheriff.domain.fake.FakeFixer;
import com.kaizten.sheriff.domain.fake.FakeTestRunner;
import com.kaizten.sheriff.domain.fake.FakeVersionControl;
import com.kaizten.sheriff.domain.port.AutomaticRepair;
import com.kaizten.sheriff.domain.port.CodeAnalyzer;
import com.kaizten.sheriff.domain.port.TestRunner;
import com.kaizten.sheriff.domain.port.VersionControl;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.FixRequest;
import com.kaizten.sheriff.domain.valueobject.LoopSettings;
import com.kaizten.sheriff.domain.valueobject.RunSummary;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import com.kaizten.sheriff.domain.valueobject.VerificationResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link FixLoop}, using simple fake port implementations instead of
 * a mocking framework: the loop only ever talks to the port shapes, so a fake
 * that satisfies the shape is a more honest test than mocking the internals of
 * a concrete adapter the loop never imports.
 *
 * <p>Ported from the Python suite one assertion at a time — that suite is the
 * specification of this behavior, so a difference here is a bug in the port.
 */
class FixLoopTests {

    private static final String RULES_CONTEXT = "Sheriff is analyzing this code with the 'JAVA' profile.";

    private static FixLoop loop(CodeAnalyzer analyzer, FakeFixer fixer, VersionControl vc,
            TestRunner runner, Integer maxIterations, boolean gitSafety, int batch) {
        return new FixLoop(analyzer, fixer, vc, runner, RULES_CONTEXT, List.of(),
                new LoopSettings(maxIterations, gitSafety, batch));
    }

    @Nested
    @DisplayName("the accepted code reaching the prompt")
    class AcceptedCode {

        private static final SheriffRule RULE = new SheriffRule(
                "JavaValueObjectShouldKeepHashCodeUnchanged", "hashCode must stay stable", "", "java",
                "tests", "java/a/a.py", List.of(),
                "def method_source():\n    return 'assertEquals(before, after);'");

        private String promptFor(String referenceCode, List<SheriffRule> rules) {
            SheriffFinding finding = new SheriffFinding(
                    "A.java", "x", "y", referenceCode, SheriffFinding.ERROR, Map.of());
            FakeFixer fixer = new FakeFixer();
            new FixLoop(
                    new FakeAnalyzer(List.of(
                            new AnalysisResult(List.of(finding), false, ""),
                            new AnalysisResult(List.of(), false, ""))),
                    fixer,
                    new FakeVersionControl(List.of("A.java")),
                    new FakeTestRunner(),
                    RULES_CONTEXT,
                    rules,
                    new LoopSettings(2, false, 0)).execute();
            return fixer.requests().get(0).prompt();
        }

        @Test
        @DisplayName("a field nothing reads is a field that does not exist")
        void thePromptCarriesWhatSheriffWouldWrite() {
            String prompt = promptFor("JavaValueObjectShouldKeepHashCodeUnchanged", List.of(RULE));
            assertTrue(prompt.contains("assertEquals(before, after);"));
            assertTrue(prompt.contains("JavaValueObjectShouldKeepHashCodeUnchanged"));
        }

        @Test
        @DisplayName("most passes gain nothing, and must not gain an empty heading either")
        void thePromptIsUnchangedWhenNoRuleInThePassHasOne() {
            String prompt = promptFor("SomethingElse", List.of(RULE));
            assertFalse(prompt.contains("Sheriff ships the script"));
            assertTrue(prompt.contains("Errors to fix in this pass"));
            assertTrue(prompt.contains("Instructions:"));
        }

        @Test
        void noCatalogMeansNoSection() {
            assertFalse(promptFor("JavaValueObjectShouldKeepHashCodeUnchanged", List.of())
                    .contains("Sheriff ships the script"));
        }
    }

    private static FixLoop simple(CodeAnalyzer analyzer, FakeFixer fixer) {
        return loop(analyzer, fixer, new FakeVersionControl(), new FakeTestRunner(), 8, false, 0);
    }

    private static List<String> scopeOf(FixRequest request) {
        return new ArrayList<>(new TreeSet<>(request.allowedFiles()));
    }

    @Nested
    @DisplayName("running to a conclusion")
    class Running {

        @Test
        void reachesZeroErrors() {
            FakeFixer fixer = new FakeFixer();
            FixLoop loop = simple(new FakeAnalyzer(List.of(
                    FakeAnalyzer.result(2, "a.py"), FakeAnalyzer.result(0, "a.py"))), fixer);
            RunSummary summary = loop.execute();
            assertTrue(summary.ok());
            assertEquals(1, fixer.requests().size());
        }

        @Test
        void promptCarriesTheFindingDetailsAndTheAllowedFiles() {
            FakeFixer fixer = new FakeFixer();
            FixLoop loop = simple(new FakeAnalyzer(List.of(
                    FakeAnalyzer.result(1, "Calculator.java"), FakeAnalyzer.result(0, "a.py"))), fixer);
            RunSummary summary = loop.execute();
            FixRequest request = fixer.requests().get(0);
            assertTrue(request.prompt().contains("Calculator.java"));
            assertEquals(Set.of("Calculator.java"), request.allowedFiles());
            assertEquals("iteration-1", request.label());
        }

        @Test
        void stopsOnAnalyzerInfrastructureError() {
            FakeFixer fixer = new FakeFixer();
            FixLoop loop = simple(new FakeAnalyzer(List.of(AnalysisResult.failure("docker isn't responding"))), fixer);
            RunSummary summary = loop.execute();
            assertFalse(summary.ok());
            assertEquals(0, fixer.requests().size());
        }

        @Test
        void stopsOnStallWithoutAnotherFixCall() {
            FakeFixer fixer = new FakeFixer();
            FixLoop loop = simple(new FakeAnalyzer(List.of(
                    FakeAnalyzer.result(5, "a.py"), FakeAnalyzer.result(5, "a.py"))), fixer);
            RunSummary summary = loop.execute();
            assertFalse(summary.ok());
            assertEquals(1, fixer.requests().size());
        }

        @Test
        void exhaustsIterationsWhenImprovingButNotReachingZero() {
            FakeFixer fixer = new FakeFixer();
            FixLoop loop = loop(new FakeAnalyzer(List.of(
                    FakeAnalyzer.result(3, "a.py"), FakeAnalyzer.result(2, "a.py"), FakeAnalyzer.result(1, "a.py"),
                    FakeAnalyzer.result(1, "a.py"))),
                    fixer, new FakeVersionControl(), new FakeTestRunner(), 3, false, 0);
            RunSummary summary = loop.execute();
            assertFalse(summary.ok());
            assertEquals(3, fixer.requests().size());
        }

        @Test
        void stopsIfTheFixerFails() {
            FakeVersionControl vc = new FakeVersionControl();
            FixLoop loop = loop(new FakeAnalyzer(List.of(FakeAnalyzer.result(2, "a.py"))),
                    new FakeFixer(false, "boom"), vc, new FakeTestRunner(), 8, false, 0);
            RunSummary summary = loop.execute();
            assertFalse(summary.ok());
            assertEquals(List.of(), vc.commits());
        }

        @Test
        void stopsIfTheFixerGoesOutOfScope() {
            FakeVersionControl vc = new FakeVersionControl(List.of("not_allowed.py"));
            FixLoop loop = loop(new FakeAnalyzer(List.of(FakeAnalyzer.result(2, "a.py"))),
                    new FakeFixer(), vc, new FakeTestRunner(), 8, true, 0);
            RunSummary summary = loop.execute();
            assertFalse(summary.ok());
            assertEquals(List.of(), vc.commits());
        }

        @Test
        @DisplayName("a finding with no file leaves an empty scope, which must block every change")
        void emptyAllowedFilesStillBlocksAnyChange() {
            FakeVersionControl vc = new FakeVersionControl(List.of("anything.py"));
            FixLoop loop = loop(new FakeAnalyzer(List.of(FakeAnalyzer.result(1, ""))),
                    new FakeFixer(), vc, new FakeTestRunner(), 8, true, 0);
            RunSummary summary = loop.execute();
            assertFalse(summary.ok());
            assertEquals(List.of(), vc.commits());
        }

        @Test
        void commitsWhenEverythingIsWithinScope() {
            FakeVersionControl vc = new FakeVersionControl(List.of("a.py"));
            FixLoop loop = loop(new FakeAnalyzer(List.of(
                    FakeAnalyzer.result(2, "a.py"), FakeAnalyzer.result(0, "a.py"))),
                    new FakeFixer(), vc, new FakeTestRunner(), 8, true, 0);
            RunSummary summary = loop.execute();
            assertTrue(summary.ok());
            assertEquals(1, vc.commits().size());
            assertTrue(vc.branchCreated());
        }
    }

    @Nested
    @DisplayName("the repair pass")
    class Repair {

        private final List<AnalysisResult> cleanAfterOneFix = List.of(
                FakeAnalyzer.result(2, "a.py"), FakeAnalyzer.result(0, "a.py"), FakeAnalyzer.result(0, "a.py"));

        @Test
        void verificationFailureThenSuccessfulRepair() {
            FakeFixer fixer = new FakeFixer();
            FakeTestRunner runner = new FakeTestRunner(List.of(
                    VerificationResult.failed("broke"), VerificationResult.passed("")));
            FixLoop loop = loop(new FakeAnalyzer(cleanAfterOneFix), fixer,
                    new FakeVersionControl(), runner, 8, false, 0);
            RunSummary summary = loop.execute();
            assertTrue(summary.ok());
            assertEquals(2, fixer.requests().size());
            assertEquals("repair", fixer.requests().get(1).label());
            assertNull(fixer.requests().get(1).allowedFiles());
        }

        @Test
        void verificationAndRepairBothFail() {
            FakeFixer fixer = new FakeFixer();
            FakeVersionControl vc = new FakeVersionControl(List.of("a.py"));
            FixLoop loop = loop(new FakeAnalyzer(cleanAfterOneFix), fixer, vc,
                    new FakeTestRunner(false), 8, true, 0);
            RunSummary summary = loop.execute();
            assertFalse(summary.ok());
            assertEquals(2, fixer.requests().size());
            assertEquals(1, vc.commits().size());
        }

        @Test
        void repairLeavesTheAnalyzerUnhappy() {
            FakeFixer fixer = new FakeFixer();
            FixLoop loop = loop(new FakeAnalyzer(List.of(
                    FakeAnalyzer.result(2, "a.py"), FakeAnalyzer.result(0, "a.py"), FakeAnalyzer.result(3, "a.py"))),
                    fixer, new FakeVersionControl(), new FakeTestRunner(false), 8, false, 0);
            RunSummary summary = loop.execute();
            assertFalse(summary.ok());
            assertEquals(2, fixer.requests().size());
        }

        @Test
        @DisplayName("Sheriff failing after the repair is not the same as the repair not working")
        void repairReanalysisHittingAnInfraError() {
            FakeFixer fixer = new FakeFixer();
            FixLoop loop = loop(new FakeAnalyzer(List.of(
                    FakeAnalyzer.result(2, "a.py"), FakeAnalyzer.result(0, "a.py"),
                    AnalysisResult.failure("docker down"))),
                    fixer, new FakeVersionControl(), new FakeTestRunner(false), 8, false, 0);
            RunSummary summary = loop.execute();
            assertFalse(summary.ok());
            assertEquals(2, fixer.requests().size());
        }

        @Test
        void theRepairRequestNeverCarriesAFileScope() {
            FakeFixer fixer = new FakeFixer();
            FakeTestRunner runner = new FakeTestRunner(List.of(
                    VerificationResult.failed("x"), VerificationResult.passed("")));
            FixLoop loop = loop(new FakeAnalyzer(List.of(
                    FakeAnalyzer.result(2, "Only.java"), FakeAnalyzer.result(0, "a.py"),
                    FakeAnalyzer.result(0, "a.py"))), fixer, new FakeVersionControl(), runner, 8, false, 0);
            RunSummary summary = loop.execute();
            assertEquals(Set.of("Only.java"), fixer.requests().get(0).allowedFiles());
            assertNull(fixer.requests().get(1).allowedFiles());
        }
    }

    @Nested
    @DisplayName("batching")
    class Batching {

        @Test
        void unboundedWhenBatchingIsOff() {
            FakeFixer fixer = new FakeFixer();
            FixLoop loop = simple(new FakeAnalyzer(List.of(
                    FakeAnalyzer.multiFileResult("A.java", "B.java", "C.java", "D.java"),
                    FakeAnalyzer.result(0, "a.py"))), fixer);
            RunSummary summary = loop.execute();
            assertEquals(Set.of("A.java", "B.java", "C.java", "D.java"), fixer.requests().get(0).allowedFiles());
        }

        @Test
        void capsThePassToTheConfiguredNumberOfFiles() {
            FakeFixer fixer = new FakeFixer();
            FixLoop loop = loop(new FakeAnalyzer(List.of(
                    FakeAnalyzer.multiFileResult("A.java", "B.java", "C.java", "D.java"),
                    FakeAnalyzer.result(0, "a.py"))), fixer, new FakeVersionControl(),
                    new FakeTestRunner(), 8, false, 2);
            RunSummary summary = loop.execute();
            assertEquals(Set.of("A.java", "B.java"), fixer.requests().get(0).allowedFiles());
        }

        @Test
        @DisplayName("one file's errors are never split across passes")
        void keepsAllOfOneFilesErrorsTogether() {
            FakeFixer fixer = new FakeFixer();
            FixLoop loop = loop(new FakeAnalyzer(List.of(
                    FakeAnalyzer.multiFileResult("A.java", "A.java", "B.java"), FakeAnalyzer.result(0, "a.py"))),
                    fixer, new FakeVersionControl(), new FakeTestRunner(), 8, false, 1);
            RunSummary summary = loop.execute();
            String prompt = fixer.requests().get(0).prompt();
            assertEquals(Set.of("A.java"), fixer.requests().get(0).allowedFiles());
            assertEquals(2, prompt.split("- A.java:", -1).length - 1);
        }

        @Test
        void promptTotalReflectsTheBatchNotTheGrandTotal() {
            FakeFixer fixer = new FakeFixer();
            FixLoop loop = loop(new FakeAnalyzer(List.of(
                    FakeAnalyzer.multiFileResult("A.java", "B.java", "C.java"), FakeAnalyzer.result(0, "a.py"))),
                    fixer, new FakeVersionControl(), new FakeTestRunner(), 8, false, 1);
            RunSummary summary = loop.execute();
            assertTrue(fixer.requests().get(0).prompt().contains("Errors to fix in this pass (1 total)"));
        }

        @Test
        void filesLeftOutOfTheBatchAreNotInScope() {
            FakeVersionControl vc = new FakeVersionControl(List.of("B.java"));
            FixLoop loop = loop(new FakeAnalyzer(List.of(
                    FakeAnalyzer.multiFileResult("A.java", "B.java"), FakeAnalyzer.result(0, "a.py"))),
                    new FakeFixer(), vc, new FakeTestRunner(), 8, true, 1);
            RunSummary summary = loop.execute();
            assertFalse(summary.ok());
            assertEquals(List.of(), vc.commits());
        }
    }

    @Nested
    @DisplayName("parking files that don't improve")
    class Parking {

        private FixLoop parkingLoop(FakeAnalyzer analyzer, FakeFixer fixer, int batch, Integer maxIterations) {
            return loop(analyzer, fixer, new FakeVersionControl(), new FakeTestRunner(),
                    maxIterations, false, batch);
        }

        @Test
        void aStubbornFileDoesNotEndTheRun() {
            FakeFixer fixer = new FakeFixer();
            FixLoop loop = parkingLoop(new FakeAnalyzer(List.of(
                    FakeAnalyzer.multiFileResult("A.java", "B.java"),
                    FakeAnalyzer.multiFileResult("A.java", "B.java"),
                    FakeAnalyzer.multiFileResult("A.java"),
                    FakeAnalyzer.multiFileResult("A.java"))), fixer, 1, 8);
            RunSummary summary = loop.execute();
            assertFalse(summary.ok());
            assertEquals(List.of(List.of("A.java"), List.of("B.java")),
                    fixer.requests().stream().map(FixLoopTests::scopeOf).toList());
            assertEquals(StopReason.STALLED, summary.stoppedReason());
            assertEquals(List.of("A.java"), summary.parkedFiles());
        }

        @Test
        void aParkedFileIsNeverOfferedAgain() {
            FakeFixer fixer = new FakeFixer();
            FixLoop loop = parkingLoop(new FakeAnalyzer(List.of(
                    FakeAnalyzer.multiFileResult("A.java", "B.java", "C.java"),
                    FakeAnalyzer.multiFileResult("A.java", "B.java", "C.java"),
                    FakeAnalyzer.multiFileResult("A.java", "C.java"),
                    FakeAnalyzer.multiFileResult("A.java"))), fixer, 1, 8);
            RunSummary summary = loop.execute();
            assertEquals(List.of(List.of("A.java"), List.of("B.java"), List.of("C.java")),
                    fixer.requests().stream().map(FixLoopTests::scopeOf).toList());
        }

        @Test
        void reachingZeroAfterParkingStillSucceeds() {
            FixLoop loop = parkingLoop(new FakeAnalyzer(List.of(
                    FakeAnalyzer.multiFileResult("A.java", "B.java"),
                    FakeAnalyzer.multiFileResult("A.java", "B.java"),
                    FakeAnalyzer.multiFileResult("B.java"),
                    FakeAnalyzer.result(0, "a.py"))), new FakeFixer(), 1, 8);
            RunSummary summary = loop.execute();
            assertTrue(summary.ok());
            assertEquals(StopReason.SUCCESS, summary.stoppedReason());
        }

        @Test
        @DisplayName("without batching there is nothing to move on to, so one bad pass ends the run")
        void withoutBatchingOneBadPassStillEndsTheRun() {
            FakeFixer fixer = new FakeFixer();
            FixLoop loop = parkingLoop(new FakeAnalyzer(List.of(
                    FakeAnalyzer.multiFileResult("A.java", "B.java"),
                    FakeAnalyzer.multiFileResult("A.java", "B.java"))), fixer, 0, 8);
            RunSummary summary = loop.execute();
            assertFalse(summary.ok());
            assertEquals(1, fixer.requests().size());
            assertEquals(StopReason.STALLED, summary.stoppedReason());
            assertEquals(List.of("A.java", "B.java"), summary.parkedFiles());
        }

        @Test
        void findingsWithNoFileCannotBeParkedSoTheRunCuts() {
            AnalysisResult noFile = AnalysisResult.of(List.of(
                    new SheriffFinding("", "x", "y", "", SheriffFinding.ERROR, Map.of())));
            FixLoop loop = parkingLoop(new FakeAnalyzer(List.of(noFile, noFile)), new FakeFixer(), 1, 8);
            RunSummary summary = loop.execute();
            assertFalse(summary.ok());
            assertEquals(StopReason.STALLED, summary.stoppedReason());
            assertEquals(List.of(), summary.parkedFiles());
        }

        @Test
        void aCleanRunParksNothing() {
            FixLoop loop = parkingLoop(new FakeAnalyzer(List.of(
                    FakeAnalyzer.multiFileResult("A.java"), FakeAnalyzer.result(0, "a.py"))),
                    new FakeFixer(), 1, 8);
            RunSummary summary = loop.execute();
            assertTrue(summary.ok());
            assertEquals(List.of(), summary.parkedFiles());
        }

        @Test
        void parkedFilesAreReportedWhenTheIterationsRunOut() {
            FixLoop loop = parkingLoop(new FakeAnalyzer(List.of(
                    FakeAnalyzer.multiFileResult("A.java", "B.java", "B.java", "B.java"),
                    FakeAnalyzer.multiFileResult("A.java", "B.java", "B.java", "B.java"),
                    FakeAnalyzer.multiFileResult("A.java", "B.java", "B.java"),
                    FakeAnalyzer.multiFileResult("A.java", "B.java"),
                    FakeAnalyzer.multiFileResult("A.java"))), new FakeFixer(), 1, 4);
            RunSummary summary = loop.execute();
            assertFalse(summary.ok());
            assertEquals(StopReason.ITERATIONS_EXHAUSTED, summary.stoppedReason());
            assertEquals(List.of("A.java"), summary.parkedFiles());
        }
    }

    @Nested
    @DisplayName("sizing the iteration cap")
    class Sizing {

        @Test
        void dividesFilesByBatchSizeWithMargin() {
            assertEquals(5, BatchPolicy.estimateMaxIterations(12, 5));
        }

        @Test
        void roundsUpAPartialBatch() {
            assertEquals(5, BatchPolicy.estimateMaxIterations(11, 5));
        }

        @Test
        void fallsBackWhenBatchingIsDisabled() {
            assertEquals(8, BatchPolicy.estimateMaxIterations(300, 0));
        }

        @Test
        void fallsBackWhenThereAreNoFiles() {
            assertEquals(8, BatchPolicy.estimateMaxIterations(0, 5));
        }

        @Test
        void nullSizesTheCapFromTheFirstAnalysis() {
            FakeFixer fixer = new FakeFixer();
            FixLoop loop = loop(new FakeAnalyzer(List.of(
                    files(12), files(7), files(2), FakeAnalyzer.result(0, "a.py"))),
                    fixer, new FakeVersionControl(), new FakeTestRunner(), null, false, 5);
            RunSummary summary = loop.execute();
            assertTrue(summary.ok());
            assertEquals(3, fixer.requests().size());
        }

        @Test
        @DisplayName("a run that lowers the errors very slowly still stops, at three times the first estimate")
        void theAutoSizedCapStillStopsTheLoop() {
            List<AnalysisResult> results = new ArrayList<>();
            for (int n = 0; n < 20; n++) {
                results.add(files(20 - n));
            }
            FakeFixer fixer = new FakeFixer();
            FixLoop loop = loop(new FakeAnalyzer(results), fixer, new FakeVersionControl(),
                    new FakeTestRunner(), null, false, 5);
            RunSummary summary = loop.execute();
            assertFalse(summary.ok());
            assertEquals(StopReason.ITERATIONS_EXHAUSTED, summary.stoppedReason());
            assertEquals(18, fixer.requests().size());
            assertEquals(18, summary.maxIterations());
        }

        @Test
        @DisplayName("files that need a second pass get it: the first estimate's two spare passes ran out here")
        void aRunWhoseFilesNeedMorePassesGetsThem() {
            FakeFixer fixer = new FakeFixer();
            FixLoop loop = loop(new FakeAnalyzer(List.of(files(12), files(10), files(8), files(6), files(4),
                    files(2), FakeAnalyzer.result(0, "a.py"))), fixer, new FakeVersionControl(),
                    new FakeTestRunner(), null, false, 5);
            RunSummary summary = loop.execute();
            assertTrue(summary.ok(), summary.toString());
            assertEquals(6, fixer.requests().size(), "a fixed cap of 5 stopped this run with 2 files left");
        }

        @Test
        @DisplayName("a cap the user set is never extended, however well the passes go")
        void anExplicitCapIsNeverExtended() {
            FakeFixer fixer = new FakeFixer();
            FixLoop loop = loop(new FakeAnalyzer(List.of(files(20), files(15), files(10), files(10))), fixer,
                    new FakeVersionControl(), new FakeTestRunner(), 2, false, 5);
            RunSummary summary = loop.execute();
            assertFalse(summary.ok());
            assertEquals(2, fixer.requests().size());
            assertEquals(2, summary.maxIterations());
        }

        @Test
        void extendsTheCapToWhatTheFilesLeftNeed() {
            assertEquals(10, BatchPolicy.extendedMaxIterations(5, 4, 20, 5, 5));
        }

        @Test
        void neverShrinksTheCap() {
            assertEquals(7, BatchPolicy.extendedMaxIterations(7, 1, 2, 5, 7));
        }

        @Test
        @DisplayName("hundreds of files left are not a reason to run without end: three times the first estimate")
        void neverPassesTheCeiling() {
            assertEquals(30, BatchPolicy.extendedMaxIterations(10, 50, 400, 5, 10));
        }

        @Test
        void leavesTheCapAloneWithoutBatchesOrFiles() {
            assertEquals(8, BatchPolicy.extendedMaxIterations(8, 3, 300, 0, 8));
            assertEquals(8, BatchPolicy.extendedMaxIterations(8, 3, 0, 5, 8));
        }

        @Test
        void anExplicitCapSkipsAutoSizing() {
            FakeFixer fixer = new FakeFixer();
            FixLoop loop = loop(new FakeAnalyzer(List.of(files(50), files(50))), fixer,
                    new FakeVersionControl(), new FakeTestRunner(), 1, false, 5);
            RunSummary summary = loop.execute();
            assertFalse(summary.ok());
            assertEquals(1, fixer.requests().size());
        }

        @Test
        @DisplayName("already clean on the first check never needs a cap at all")
        void alreadyCleanNeedsNoCap() {
            FakeFixer fixer = new FakeFixer();
            FixLoop loop = loop(new FakeAnalyzer(List.of(FakeAnalyzer.result(0, "a.py"))), fixer,
                    new FakeVersionControl(), new FakeTestRunner(), null, false, 5);
            RunSummary summary = loop.execute();
            assertTrue(summary.ok());
            assertEquals(0, fixer.requests().size());
            assertNull(summary.maxIterations());
        }

        private AnalysisResult files(int count) {
            String[] names = new String[count];
            for (int i = 0; i < count; i++) {
                names[i] = "F" + i + ".java";
            }
            return FakeAnalyzer.multiFileResult(names);
        }
    }

    @Nested
    @DisplayName("what the run leaves behind")
    class Summary {

        @Test
        void success() {
            FixLoop loop = simple(new FakeAnalyzer(List.of(
                    FakeAnalyzer.result(2, "a.py"), FakeAnalyzer.result(0, "a.py"))), new FakeFixer());
            RunSummary summary = loop.execute();
            assertTrue(summary.ok());
            assertEquals(StopReason.SUCCESS, summary.stoppedReason());
            assertEquals(2, summary.iterationsUsed());
            assertFalse(summary.repairPassUsed());
        }

        @Test
        void successAfterRepair() {
            FakeTestRunner runner = new FakeTestRunner(List.of(
                    VerificationResult.failed("broke"), VerificationResult.passed("")));
            FixLoop loop = loop(new FakeAnalyzer(List.of(
                    FakeAnalyzer.result(2, "a.py"), FakeAnalyzer.result(0, "a.py"), FakeAnalyzer.result(0, "a.py"))),
                    new FakeFixer(), new FakeVersionControl(), runner, 8, false, 0);
            RunSummary summary = loop.execute();
            assertTrue(summary.ok());
            assertEquals(StopReason.SUCCESS_AFTER_REPAIR, summary.stoppedReason());
            assertTrue(summary.repairPassUsed());
        }

        @Test
        void repairFailed() {
            FixLoop loop = loop(new FakeAnalyzer(List.of(
                    FakeAnalyzer.result(2, "a.py"), FakeAnalyzer.result(0, "a.py"), FakeAnalyzer.result(0, "a.py"))),
                    new FakeFixer(), new FakeVersionControl(), new FakeTestRunner(false), 8, false, 0);
            RunSummary summary = loop.execute();
            assertFalse(summary.ok());
            assertEquals(StopReason.REPAIR_FAILED, summary.stoppedReason());
            assertTrue(summary.repairPassUsed());
        }

        @Test
        void infraError() {
            FixLoop loop = simple(new FakeAnalyzer(List.of(AnalysisResult.failure("docker down"))), new FakeFixer());
            RunSummary summary = loop.execute();
            assertFalse(summary.ok());
            assertEquals(StopReason.INFRA_ERROR, summary.stoppedReason());
        }

        @Test
        void stalled() {
            FixLoop loop = simple(new FakeAnalyzer(List.of(
                    FakeAnalyzer.result(5, "a.py"), FakeAnalyzer.result(5, "a.py"))), new FakeFixer());
            RunSummary summary = loop.execute();
            assertEquals(StopReason.STALLED, summary.stoppedReason());
        }

        @Test
        void fixerFailed() {
            FixLoop loop = simple(new FakeAnalyzer(List.of(FakeAnalyzer.result(2, "a.py"))),
                    new FakeFixer(false, "boom"));
            RunSummary summary = loop.execute();
            assertEquals(StopReason.FIXER_FAILED, summary.stoppedReason());
        }

        @Test
        void outOfScope() {
            FixLoop loop = loop(new FakeAnalyzer(List.of(FakeAnalyzer.result(2, "a.py"))), new FakeFixer(),
                    new FakeVersionControl(List.of("not_allowed.py")), new FakeTestRunner(), 8, true, 0);
            RunSummary summary = loop.execute();
            assertEquals(StopReason.OUT_OF_SCOPE, summary.stoppedReason());
        }

        @Test
        @DisplayName("a clean rename stays in scope when the old name was the one Sheriff flagged")
        void renameStaysInScopeWhenTheOldNameWasAllowed() {
            FixLoop loop = loop(
                    new FakeAnalyzer(List.of(FakeAnalyzer.result(2, "a.py"), FakeAnalyzer.result(0, "a.py"))),
                    new FakeFixer(),
                    new FakeVersionControl(List.of("A.PY"), Map.of("A.PY", "a.py")),
                    new FakeTestRunner(), 8, true, 0);
            RunSummary summary = loop.execute();
            assertTrue(summary.ok());
        }

        @Test
        @DisplayName("a rename of a file that was never allowed is still out of scope")
        void renameOfAnUnrelatedFileIsStillOutOfScope() {
            FixLoop loop = loop(new FakeAnalyzer(List.of(FakeAnalyzer.result(2, "a.py"))), new FakeFixer(),
                    new FakeVersionControl(List.of("New.py"), Map.of("New.py", "unrelated.py")),
                    new FakeTestRunner(), 8, true, 0);
            RunSummary summary = loop.execute();
            assertEquals(StopReason.OUT_OF_SCOPE, summary.stoppedReason());
        }

        @Test
        @DisplayName("a last pass that reaches 0 errors is a success, verified, not an exhausted cap")
        void theLastPassTheCapAllowsCanReachZero() {
            FakeFixer fixer = new FakeFixer();
            FixLoop loop = loop(new FakeAnalyzer(List.of(
                    FakeAnalyzer.result(2, "a.py"), FakeAnalyzer.result(1, "a.py"), FakeAnalyzer.result(0, "a.py"))),
                    fixer, new FakeVersionControl(), new FakeTestRunner(), 2, false, 0);
            RunSummary summary = loop.execute();
            assertTrue(summary.ok());
            assertEquals(StopReason.SUCCESS, summary.stoppedReason());
            assertEquals(2, summary.iterationsUsed());
            assertEquals(2, fixer.requests().size());
        }

        @Test
        @DisplayName("when the last pass reaches 0 but the tests fail, the repair pass still gets its turn")
        void theRepairPassRunsAfterTheLastPassToo() {
            FakeFixer fixer = new FakeFixer();
            FixLoop loop = loop(new FakeAnalyzer(List.of(
                    FakeAnalyzer.result(1, "a.py"), FakeAnalyzer.result(0, "a.py"), FakeAnalyzer.result(0, "a.py"))),
                    fixer, new FakeVersionControl(), new FakeTestRunner(List.of(
                            VerificationResult.failed("expected 25.0 but was 0.0"), VerificationResult.passed("ok"))),
                    1, false, 0);
            RunSummary summary = loop.execute();
            assertEquals(StopReason.SUCCESS_AFTER_REPAIR, summary.stoppedReason());
            assertEquals(2, fixer.requests().size());
        }

        @Test
        @DisplayName("exhausted iterations report the real cap, not an off-by-one")
        void iterationsExhausted() {
            FixLoop loop = loop(new FakeAnalyzer(List.of(
                    FakeAnalyzer.result(3, "a.py"), FakeAnalyzer.result(2, "a.py"), FakeAnalyzer.result(1, "a.py"),
                    FakeAnalyzer.result(1, "a.py"))),
                    new FakeFixer(), new FakeVersionControl(), new FakeTestRunner(), 3, false, 0);
            RunSummary summary = loop.execute();
            assertEquals(StopReason.ITERATIONS_EXHAUSTED, summary.stoppedReason());
            assertEquals(3, summary.iterationsUsed());
            assertEquals(3, summary.maxIterations());
        }
    }

    @Nested
    @DisplayName("Sheriff's own fixers, before the first iteration")
    class AutomaticRepairFirst {

        private static final AnalysisResult CLEAN = new AnalysisResult(List.of(), false, "");

        private FixLoop loopWith(FakeVersionControl vc, AutomaticRepair repair, boolean gitSafety) {
            return new FixLoop(new FakeAnalyzer(List.of(CLEAN)), new FakeFixer(), vc, new FakeTestRunner(), repair,
                    RULES_CONTEXT, List.of(), new LoopSettings(2, gitSafety, 0));
        }

        @Test
        @DisplayName("they run inside the run's branch, never on the branch that was checked out")
        void theyRunAfterTheBranchExists() {
            FakeVersionControl vc = new FakeVersionControl();
            List<Boolean> branchExisted = new ArrayList<>();
            loopWith(vc, () -> {
                branchExisted.add(vc.branchCreated());
                return "Sheriff's own fixers were given 1 rule(s) to repair";
            }, true).execute();
            assertEquals(List.of(true), branchExisted);
        }

        @Test
        @DisplayName("what they change is committed on its own, before any model's work")
        void theirChangesGetTheirOwnCommit() {
            FakeVersionControl vc = new FakeVersionControl();
            loopWith(vc, () -> "Sheriff's own fixers were given 1 rule(s) to repair", true).execute();
            assertEquals(1, vc.commits().size());
            assertTrue(vc.commits().get(0).contains("no model"));
        }

        @Test
        void nothingToSayMeansNothingToCommit() {
            FakeVersionControl vc = new FakeVersionControl();
            loopWith(vc, () -> "", true).execute();
            assertEquals(List.of(), vc.commits());
        }

        @Test
        @DisplayName("without git safety they still run, and nothing is committed")
        void theyRunWithoutGitSafetyToo() {
            FakeVersionControl vc = new FakeVersionControl();
            List<String> ran = new ArrayList<>();
            loopWith(vc, () -> {
                ran.add("ran");
                return "applied";
            }, false).execute();
            assertEquals(List.of("ran"), ran);
            assertEquals(List.of(), vc.commits());
        }

        @Test
        @DisplayName("the summary names the branch the run left the repository on")
        void theSummaryNamesTheBranch() {
            RunSummary summary = loopWith(new FakeVersionControl(), () -> "", true).execute();
            assertEquals("sheriff-agent/fake", summary.workingBranch());
            RunSummary withoutGit = loopWith(new FakeVersionControl(), () -> "", false).execute();
            assertEquals("", withoutGit.workingBranch());
        }
    }

    @Nested
    @DisplayName("the project's tests, whatever way the run stops")
    class TestsAtTheEnd {

        private RunSummary stalledWith(FakeTestRunner runner) {
            return loop(new FakeAnalyzer(List.of(FakeAnalyzer.result(5, "a.py"), FakeAnalyzer.result(5, "a.py"))),
                    new FakeFixer(), new FakeVersionControl(), runner, 8, false, 0).execute();
        }

        @Test
        @DisplayName("a run cut short says when it leaves tests failing")
        void aStalledRunReportsFailingTests() {
            RunSummary summary = stalledWith(new FakeTestRunner(false));
            assertEquals(StopReason.STALLED, summary.stoppedReason());
            assertEquals(Boolean.FALSE, summary.testsPassed());
        }

        @Test
        void aStalledRunWithPassingTestsSaysSoToo() {
            assertEquals(Boolean.TRUE, stalledWith(new FakeTestRunner(true)).testsPassed());
        }

        @Test
        @DisplayName("a successful run is not tested twice")
        void successIsNotTestedAgain() {
            List<Boolean> runs = new ArrayList<>();
            TestRunner counting = () -> {
                runs.add(true);
                return VerificationResult.passed("ok");
            };
            RunSummary summary = loop(new FakeAnalyzer(List.of(new AnalysisResult(List.of(), false, ""))),
                    new FakeFixer(), new FakeVersionControl(), counting, 2, false, 0).execute();
            assertEquals(Boolean.TRUE, summary.testsPassed());
            assertEquals(1, runs.size());
        }

        @Test
        @DisplayName("with no tests to run, a clean run succeeds without claiming they passed")
        void noTestsToRunAreNotReportedAsPassing() {
            TestRunner none = () -> VerificationResult.notRun("no build tool");
            RunSummary summary = loop(new FakeAnalyzer(List.of(new AnalysisResult(List.of(), false, ""))),
                    new FakeFixer(), new FakeVersionControl(), none, 2, false, 0).execute();
            assertTrue(summary.ok());
            assertEquals(StopReason.SUCCESS, summary.stoppedReason());
            assertNull(summary.testsPassed());
        }

        @Test
        void aStalledRunWithNoTestsToRunSaysNothingAboutThem() {
            RunSummary summary = loop(new FakeAnalyzer(List.of(FakeAnalyzer.result(5, "a.py"),
                    FakeAnalyzer.result(5, "a.py"))), new FakeFixer(), new FakeVersionControl(),
                    () -> VerificationResult.notRun("no build tool"), 8, false, 0).execute();
            assertEquals(StopReason.STALLED, summary.stoppedReason());
            assertNull(summary.testsPassed());
        }
    }
}
