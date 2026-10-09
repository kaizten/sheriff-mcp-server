package com.kaizten.sheriff.infrastructure.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reading which fixers Sheriff could not apply, against what the image of 21
 * September 2026 prints when its BracesForStatements fixer is missing.
 */
class FixerFailuresTests {

    private static final String REAL_OUTPUT = """
            Fixing errors for component '[comp]' in context 'KaiztenContext={...}'. 17 errors found.
            Could not apply fixer for error 'BracesForStatements' in file '/data/comp/src/main/java/demo/A.java'
            Error: Fixer script does not exist: '/sheriff-fixers/java/braces-for-statements/target/braces-for-statements.jar'
            Stack trace:
            java.lang.IllegalArgumentException: Fixer script does not exist: '...'
            \tat com.kaizten.analysis.fixer.KaiztenSheriffFixerExecutor.checkIfScriptIsValid(KaiztenSheriffFixerExecutor.java:46)
            Skipped 13 errors in file: /data/comp/src/main/java/demo/A.java
            Failed to fix 1 errors in file: /data/comp/src/main/java/demo/A.java
            Could not apply fixer for error 'BracesForStatements' in file '/data/comp/src/main/java/demo/B.java'
            Error: Fixer script does not exist: '/sheriff-fixers/java/braces-for-statements/target/braces-for-statements.jar'
            Fix results:
            \tFixedRuns:   0
            \tFailedRuns:  2
            """;

    @Test
    @DisplayName("the codes of the fixers that failed, so they are not offered again")
    void theCodesOfTheFailedFixers() {
        assertEquals(Set.of("BracesForStatements"), FixerFailures.codesOf(FixerFailures.in(REAL_OUTPUT)));
        assertEquals(Set.of("Bare"), FixerFailures.codesOf(List.of("Bare")));
    }

    @Test
    @DisplayName("a fixer that could not run is named once, with Sheriff's own reason")
    void namesEachFixerOnceWithItsReason() {
        assertEquals(List.of("BracesForStatements: Fixer script does not exist: "
                + "'/sheriff-fixers/java/braces-for-statements/target/braces-for-statements.jar'"),
                FixerFailures.in(REAL_OUTPUT));
    }

    @Test
    void aRunWhoseFixersAllRanReportsNothing() {
        assertEquals(List.of(), FixerFailures.in("Fix results:\n\tFixedRuns:   3\n\tFailedRuns:  0\n"));
        assertEquals(List.of(), FixerFailures.in(""));
    }

    @Test
    void aFixerWithNoReasonIsStillNamed() {
        assertEquals(List.of("SortedImport: "), FixerFailures.in("Could not apply fixer for error 'SortedImport' in file 'x'"));
    }

    @Test
    @DisplayName("the deterministic report says which fixers were not applied")
    void theReportSaysSo() {
        DeterministicFixReport report = DeterministicFixReport.applied(
                52, List.of("BracesForStatements"), FixerFailures.in(REAL_OUTPUT));
        assertTrue(report.describe().contains("could not apply 1 of them: BracesForStatements: Fixer script"),
                report.describe());
    }
}
