package com.kaizten.sheriff.infrastructure.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which {@code fix} runs one analysis calls for, and what each is kept to.
 */
class FixPlanTests {

    private static SheriffFinding finding(String file, String code, boolean fixer) {
        return new SheriffFinding(file, "d of " + code + " in " + file, "h", code, "ERROR",
                fixer ? Map.of("script", "s") : Map.of());
    }

    private static final SheriffFinding SORT_A = finding("app/A.java", "SortedImport", true);
    private static final SheriffFinding TABS_A = finding("app/A.java", "FourSpaceIndentation", true);
    private static final SheriffFinding TABS_B = finding("app/B.java", "FourSpaceIndentation", true);
    private static final SheriffFinding MAGIC_C = finding("app/C.java", "JavaMagicNumber", false);

    @Test
    @DisplayName("a planned fix holds its rules and files, and two with the same are the same")
    void aPlannedFixHoldsItsRulesAndFiles() {
        PlannedFix whole = new PlannedFix(List.of(), List.of());
        PlannedFix kept = new PlannedFix(List.of("SortedImport"), List.of("app/A.java"));

        assertEquals(List.of(), whole.codes(), "no rule named is Sheriff's default set");
        assertEquals(List.of(), whole.files(), "no file named is the whole component");
        assertEquals(List.of("SortedImport"), kept.codes());
        assertEquals(List.of("app/A.java"), kept.files());
        assertEquals(new PlannedFix(List.of("SortedImport"), List.of("app/A.java")), kept);
    }

    @Test
    @DisplayName("with nothing withheld the plan is one fix of the default set, even with nothing recorded")
    void nothingWithheldIsTheDefaultSet() {
        List<PlannedFix> whole = List.of(new PlannedFix(List.of(), List.of()));

        assertEquals(whole, FixPlan.of(List.of(SORT_A, MAGIC_C), finding -> false, Optional.empty(),
                new FixerAttempts()));
        assertEquals(whole, FixPlan.of(List.of(), finding -> false, Optional.empty(), new FixerAttempts()));
    }

    @Test
    @DisplayName("rules named are the plan as given, on the whole component")
    void namedRulesAreGivenAsTheyAre() {
        assertEquals(List.of(new PlannedFix(List.of("SortedImport", "Other"), List.of())),
                FixPlan.of(List.of(SORT_A), finding -> false, Optional.of(List.of("SortedImport", "Other")),
                        new FixerAttempts()));
    }

    @Test
    @DisplayName("a finding withheld: every fixer for the other files, then the other rules for its file")
    void aWithheldFindingKeepsItsRuleOutOfItsFile() {
        List<PlannedFix> plan = FixPlan.of(List.of(SORT_A, TABS_A, TABS_B, MAGIC_C), TABS_A::equals,
                Optional.empty(), new FixerAttempts());

        assertEquals(List.of(new PlannedFix(List.of("SortedImport", "FourSpaceIndentation"), List.of("app/B.java")),
                new PlannedFix(List.of("SortedImport"), List.of("app/A.java"))), plan,
                "a file with no fixable finding is in no run, and a rule with no script is never named");
    }

    @Test
    @DisplayName("with everything withheld, or only rules with no fixer beside it, there is nothing to run")
    void nothingToRun() {
        assertEquals(List.of(), FixPlan.of(List.of(TABS_A, TABS_B), finding -> true, Optional.empty(),
                new FixerAttempts()));
        assertEquals(List.of(), FixPlan.of(List.of(TABS_A, MAGIC_C), TABS_A::equals, Optional.empty(),
                new FixerAttempts()));
        assertEquals(List.of(), FixPlan.of(List.of(TABS_A), TABS_A::equals,
                Optional.of(List.of("FourSpaceIndentation")), new FixerAttempts()));
    }

    @Test
    @DisplayName("a finding with a fixer and no rule names nothing, as an empty rule would fail the run")
    void aFindingWithNoRuleNamesNothing() {
        SheriffFinding nameless = finding("app/B.java", "", true);

        assertEquals(List.of(new PlannedFix(List.of("SortedImport"), List.of("app/A.java"))),
                FixPlan.of(List.of(SORT_A, TABS_A, nameless), TABS_A::equals, Optional.empty(),
                        new FixerAttempts()));
    }

    @Test
    @DisplayName("the plan is a function, not an object")
    void isNotInstantiable() throws NoSuchMethodException {
        Constructor<FixPlan> constructor = FixPlan.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class, constructor::newInstance);
        assertEquals(UnsupportedOperationException.class, thrown.getCause().getClass());
    }
}
