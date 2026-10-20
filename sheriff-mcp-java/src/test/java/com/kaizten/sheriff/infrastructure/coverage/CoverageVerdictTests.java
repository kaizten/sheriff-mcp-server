package com.kaizten.sheriff.infrastructure.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What the reports say of the lines a change wrote, and how it reads.
 */
class CoverageVerdictTests {

    private static final CoverageThresholds DEFAULTS = CoverageThresholds.defaults();
    private static final String MUTATORS = "STRONGER";

    private static CoveredLine ran() {
        return new CoveredLine(true, 0, 0);
    }

    private static CoveredLine missed() {
        return new CoveredLine(false, 0, 0);
    }

    @Test
    @DisplayName("only the changed lines count: lines with no code and mutants of other lines are left out")
    void onlyTheChangeCounts() {
        Map<String, Set<Integer>> changes = Map.of("demo/Calc.java", Set.of(9, 10, 13, 14));
        Map<String, Map<Integer, CoveredLine>> lines = Map.of("demo/Calc.java",
                Map.of(4, missed(), 10, missed(), 13, new CoveredLine(true, 4, 3), 14, ran()));
        List<Mutant> mutants = List.of(new Mutant("demo/Calc.java", 13, "SURVIVED", "changed conditional boundary",
                false), new Mutant("demo/Calc.java", 14, "KILLED", "replaced return", true),
                new Mutant("demo/Calc.java", 14, "NON_VIABLE", "x", false),
                new Mutant("demo/Calc.java", 4, "SURVIVED", "not this change's", false),
                new Mutant("demo/Other.java", 13, "SURVIVED", "nor this", false));

        CoverageVerdict verdict = CoverageVerdict.of(changes, lines, mutants, DEFAULTS);

        assertEquals(new Tally(3, 2), verdict.lines());
        assertEquals(new Tally(4, 3), verdict.branches());
        assertEquals(new Tally(2, 1), verdict.mutants());
        assertEquals(Map.of("src/main/java/demo/Calc.java", List.of(10)), verdict.uncovered());
        assertEquals(List.of("src/main/java/demo/Calc.java:13 takes 3 of its 4 branches"), verdict.partial());
        assertEquals(List.of("src/main/java/demo/Calc.java:13 changed conditional boundary"), verdict.survivors());
        assertFalse(verdict.passes());
        assertEquals("The tests run 2 of the 3 lines of code this change wrote (66%, 100% required), take 3 of the 4 "
                + "branches of their conditions (75%, 100% required) and kill 1 of the 2 mutants PIT made of them "
                + "(50%, 80% required).", verdict.summary());
    }

    @Test
    @DisplayName("a line every test runs, with a condition taken one way only, is not tested: the branch counts")
    void aConditionTakenOneWayFails() {
        Map<String, Set<Integer>> changes = Map.of("demo/Calc.java", Set.of(20));
        Map<String, Map<Integer, CoveredLine>> lines = Map.of("demo/Calc.java", Map.of(20, new CoveredLine(true, 4,
                3)));

        CoverageVerdict verdict = CoverageVerdict.of(changes, lines, List.of(), DEFAULTS);

        assertEquals(100, verdict.lines().percent());
        assertFalse(verdict.passes());
        assertTrue(verdict.shortfall().contains("Conditions this change wrote that the tests take only some ways, "
                + "each side of every && and || counted:"), verdict.shortfall());
        assertTrue(CoverageVerdict.of(changes, lines, List.of(), new CoverageThresholds(100, 75, 80, MUTATORS))
                .passes());
    }

    @Test
    @DisplayName("it passes when every share reaches what the project asks, and the shortfall lists what is left")
    void passesAtTheThresholds() {
        Map<String, Set<Integer>> changes = Map.of("demo/Calc.java", Set.of(1, 2));
        Map<String, Map<Integer, CoveredLine>> lines = Map.of("demo/Calc.java", Map.of(1, ran(), 2, missed()));
        List<Mutant> mutants = List.of(new Mutant("demo/Calc.java", 1, "KILLED", "a", true),
                new Mutant("demo/Calc.java", 1, "KILLED", "b", true),
                new Mutant("demo/Calc.java", 1, "SURVIVED", "c", false));

        assertTrue(CoverageVerdict.of(changes, lines, mutants, new CoverageThresholds(50, 100, 66, MUTATORS)).passes());
        assertFalse(CoverageVerdict.of(changes, lines, mutants, new CoverageThresholds(51, 100, 66, MUTATORS)).passes());
        assertFalse(CoverageVerdict.of(changes, lines, mutants, new CoverageThresholds(50, 100, 67, MUTATORS)).passes());
        assertEquals(String.format("%nLines of code this change wrote that no test runs:"
                + "%n  - src/main/java/demo/Calc.java: 2"
                + "%nChanges PIT made to those lines that every test still passed with:"
                + "%n  - src/main/java/demo/Calc.java:1 c"),
                CoverageVerdict.of(changes, lines, mutants, DEFAULTS).shortfall());
    }

    @Test
    @DisplayName("a change with no line of code passes, and says so")
    void noCodePasses() {
        CoverageVerdict verdict = CoverageVerdict.of(Map.of("demo/A.java", Set.of(1)), Map.of(), List.of(), DEFAULTS);
        assertTrue(verdict.measured());
        assertTrue(verdict.passes());
        assertEquals("This change wrote no line of code the tests could run.", verdict.summary());
    }

    @Test
    @DisplayName("a mutant the JVM could not load does not count; one the tests ran and passed with survived")
    void whatAMutantCountsAs() {
        assertFalse(new Mutant("A.java", 1, "NON_VIABLE", "x", false).counts());
        assertTrue(new Mutant("A.java", 1, "NO_COVERAGE", "x", false).counts());
        assertTrue(new Mutant("A.java", 1, "SURVIVED", "x", false).survived());
        assertFalse(new Mutant("A.java", 1, "NO_COVERAGE", "x", false).survived());
        assertEquals(100, Tally.none().percent(), "all of none");
        assertFalse(Tally.none().any());
        assertEquals(66, new Tally(3, 2).percent(), "rounded down");
    }

    @Test
    @DisplayName("a long list stops at twenty and counts the rest")
    void longListsAreCut() {
        List<Mutant> mutants = new ArrayList<>();
        for (int line = 1; line <= 25; line++) {
            mutants.add(new Mutant("A.java", line, "SURVIVED", "m", false));
        }
        Set<Integer> changed = new TreeSet<>();
        mutants.forEach(mutant -> changed.add(mutant.line()));
        String shortfall = CoverageVerdict.of(Map.of("A.java", changed), Map.of(), mutants, DEFAULTS).shortfall();
        assertTrue(shortfall.contains("src/main/java/A.java:20 m"), shortfall);
        assertFalse(shortfall.contains("src/main/java/A.java:21 m"), shortfall);
        assertTrue(shortfall.endsWith("... and 5 more"), shortfall);
    }

    @Test
    @DisplayName("nothing to measure and could not measure both pass, and only the second applies")
    void nothingAndUnmeasured() {
        assertFalse(CoverageVerdict.nothingToMeasure().applies());
        assertTrue(CoverageVerdict.nothingToMeasure().passes());
        CoverageVerdict unmeasured = CoverageVerdict.unmeasured("no network");
        assertTrue(unmeasured.applies());
        assertFalse(unmeasured.measured());
        assertTrue(unmeasured.passes());
        assertEquals("no network", unmeasured.problem());
    }

    @Test
    @DisplayName("thresholds a project declares are read as shares from 0 to 100, and anything else is the default")
    void thresholdsAreShares() {
        assertEquals(new CoverageThresholds(100, 100, 80,
                "STRONGER,REMOVE_CONDITIONALS,EXPERIMENTAL_ARGUMENT_PROPAGATION"), DEFAULTS);
        assertEquals(new CoverageThresholds(90, 85, 60, "ALL"), CoverageThresholds.declared(Optional.of(" 90 "),
                Optional.of("85"), Optional.of("60%"), Optional.of("ALL")));
        assertEquals(DEFAULTS, CoverageThresholds.declared(Optional.of("101"), Optional.of("-1"),
                Optional.of("many"), Optional.of("ALL; rm -rf /")));
        assertEquals("DEFAULTS,REMOVE_CONDITIONALS", CoverageThresholds.declared(Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.of("DEFAULTS, REMOVE_CONDITIONALS")).mutators());
    }

    @Test
    @DisplayName("a mutant no test ran counts against the share, but is not listed: its line is, as one no test runs")
    void aMutantWithNoCoverageIsCountedNotListed() {
        CoverageVerdict verdict = CoverageVerdict.of(Map.of("A.java", Set.of(1)), Map.of("A.java", Map.of(1,
                missed())), List.of(new Mutant("A.java", 1, "NO_COVERAGE", "m", false)), DEFAULTS);
        assertEquals(new Tally(1, 0), verdict.mutants());
        assertEquals(List.of(), verdict.survivors());
    }

    @Test
    @DisplayName("nothing to measure is not measured; each share alone below its threshold fails the change")
    void eachShareCounts() {
        assertFalse(CoverageVerdict.nothingToMeasure().measured());
        CoverageThresholds held = new CoverageThresholds(50, 50, 50, MUTATORS);
        Tally half = new Tally(2, 1);
        Tally less = new Tally(3, 1);
        assertTrue(verdict(half, half, half, held).passes());
        assertFalse(verdict(less, half, half, held).passes());
        assertFalse(verdict(half, less, half, held).passes());
        assertFalse(verdict(half, half, less, held).passes());
    }

    @Test
    @DisplayName("mutants with no line of code still make a summary of numbers")
    void mutantsAloneMakeASummary() {
        assertTrue(verdict(Tally.none(), Tally.none(), new Tally(2, 1), DEFAULTS).summary().startsWith(
                "The tests run 0 of the 0 lines"));
    }

    @Test
    @DisplayName("each list of the shortfall stands alone, under its own heading")
    void eachListStandsAlone() {
        CoverageVerdict onlyPartial = new CoverageVerdict(true, "", new Tally(1, 1), new Tally(2, 1), Tally.none(),
                Map.of(), List.of("A.java:1 takes 1 of its 2 branches"), List.of(), DEFAULTS);
        assertEquals(String.format("%nConditions this change wrote that the tests take only some ways, each side of "
                + "every && and || counted:%n  - A.java:1 takes 1 of its 2 branches"), onlyPartial.shortfall());
        CoverageVerdict onlySurvivors = new CoverageVerdict(true, "", new Tally(1, 1), Tally.none(), new Tally(1, 0),
                Map.of(), List.of(), List.of("A.java:1 m"), DEFAULTS);
        assertEquals(String.format("%nChanges PIT made to those lines that every test still passed with:"
                + "%n  - A.java:1 m"), onlySurvivors.shortfall());
        assertEquals("", verdict(Tally.none(), Tally.none(), Tally.none(), DEFAULTS).shortfall());
    }

    @Test
    @DisplayName("twenty entries are all shown; the twenty-first is counted")
    void twentyAreShown() {
        List<String> twenty = new ArrayList<>();
        for (int line = 1; line <= 20; line++) {
            twenty.add("A.java:" + line + " m");
        }
        CoverageVerdict verdict = new CoverageVerdict(true, "", Tally.none(), Tally.none(), new Tally(20, 0), Map.of(),
                List.of(), twenty, DEFAULTS);
        assertFalse(verdict.shortfall().contains("more"), verdict.shortfall());
    }

    @Test
    @DisplayName("a declared share of 0 or 100 is taken as it is")
    void theEndsOfAShareAreShares() {
        assertEquals(new CoverageThresholds(0, 100, 80, "STRONGER,REMOVE_CONDITIONALS,EXPERIMENTAL_ARGUMENT_PROPAGATION"),
                CoverageThresholds.declared(Optional.of("0"), Optional.of("100"), Optional.empty(), Optional.empty()));
    }

    private static CoverageVerdict verdict(Tally lines, Tally branches, Tally mutants, CoverageThresholds held) {
        return new CoverageVerdict(true, "", lines, branches, mutants, Map.of(), List.of(), List.of(), held);
    }
}
