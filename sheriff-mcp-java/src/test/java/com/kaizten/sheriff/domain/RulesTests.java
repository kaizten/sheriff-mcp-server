package com.kaizten.sheriff.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.valueobject.RuleSelection;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link Rules}: which rules apply to a profile, and how the fixer
 * gets told about them. The rules arrive as arguments, so nothing here loads a
 * catalog file — same reason the class takes the profile as an argument.
 */
class RulesTests {

    private static final SheriffRule JAVADOC = new SheriffRule(
            "JavaDocCommentsInMethod", "JavaDoc comment found in method '%s'.", "Remove it.",
            "java", "format", "", List.of("JAVA", "JAVA_HEXAGONAL"));
    private static final SheriffRule HEXAGONAL_ONLY = new SheriffRule(
            "JavaDomainDependsOnAdapter", "Domain depends on an adapter.", "",
            "java", "", "", List.of("JAVA_HEXAGONAL"));
    private static final SheriffRule UNMAPPED_JAVA = new SheriffRule(
            "JavaPomSomething", "Something about a pom.", "", "java", "", "", List.of());
    private static final SheriffRule TYPESCRIPT = new SheriffRule(
            "TypeScriptThing", "A TypeScript thing.", "", "typescript", "", "", List.of("TYPESCRIPT"));
    private static final List<SheriffRule> ALL = List.of(JAVADOC, HEXAGONAL_ONLY, UNMAPPED_JAVA, TYPESCRIPT);

    @Nested
    @DisplayName("the language a profile belongs to")
    class Language {

        @Test
        void stripsTheVariant() {
            assertEquals("java", Rules.languageForProfile("JAVA_HEXAGONAL_DOMAIN"));
            assertEquals("typescript", Rules.languageForProfile("TYPESCRIPT"));
            assertEquals("perl", Rules.languageForProfile("PERL_FORMAT"));
        }
    }

    @Nested
    @DisplayName("selecting the rules in force")
    class Selection {

        @Test
        void exactWhenTheCatalogKnowsTheProfile() {
            RuleSelection selection = Rules.selectRulesForProfile(ALL, "JAVA");
            assertTrue(selection.exact());
            assertEquals(List.of(JAVADOC), selection.rules());
        }

        @Test
        void aBroaderProfileGetsItsOwnExtraRules() {
            RuleSelection selection = Rules.selectRulesForProfile(ALL, "JAVA_HEXAGONAL");
            assertTrue(selection.exact());
            assertEquals(List.of(JAVADOC, HEXAGONAL_ONLY), selection.rules());
        }

        @Test
        @DisplayName("an unknown profile falls back to the language, and says so")
        void unknownProfileFallsBackToTheWholeLanguage() {
            RuleSelection selection = Rules.selectRulesForProfile(ALL, "JAVA_POM");
            assertFalse(selection.exact());
            assertEquals(List.of(JAVADOC, HEXAGONAL_ONLY, UNMAPPED_JAVA), selection.rules());
        }

        @Test
        void nothingAtAllForALanguageWithNoRules() {
            RuleSelection selection = Rules.selectRulesForProfile(ALL, "COBOL");
            assertTrue(selection.isEmpty());
            assertFalse(selection.exact());
        }
    }

    @Nested
    @DisplayName("searching the catalog")
    class Search {

        @Test
        void matchesCodeDescriptionAndCategory() {
            assertEquals(List.of(JAVADOC), Rules.searchRules(ALL, "javadoccomments"));
            assertEquals(List.of(HEXAGONAL_ONLY), Rules.searchRules(ALL, "adapter"));
            assertEquals(List.of(JAVADOC), Rules.searchRules(ALL, "format"));
        }

        @Test
        void emptyQueryReturnsEverything() {
            assertEquals(ALL, Rules.searchRules(ALL, ""));
        }

        @Test
        void noMatchIsEmptyNotAnError() {
            assertEquals(List.of(), Rules.searchRules(ALL, "zzzz"));
        }

        @Test
        @DisplayName("several words are matched one by one, not as a phrase")
        void everyWordHasToAppearSomewhere() {
            assertEquals(List.of(JAVADOC), Rules.searchRules(ALL, "javadoc method"));
        }

        @Test
        @DisplayName("the words need not be in the order the rule happens to use")
        void theOrderOfTheWordsDoesNotMatter() {
            assertEquals(List.of(JAVADOC), Rules.searchRules(ALL, "method javadoc"));
        }

        @Test
        @DisplayName("a word that appears nowhere rules the entry out, so the search still narrows")
        void oneAbsentWordIsEnoughToExclude() {
            assertEquals(List.of(), Rules.searchRules(ALL, "javadoc typescript"));
        }

        @Test
        @DisplayName("words spanning two fields match, which a phrase never could")
        void wordsMaySpanDifferentFields() {
            assertEquals(List.of(JAVADOC), Rules.searchRules(ALL, "javadoc format"));
        }
    }

    @Nested
    @DisplayName("the blurb that goes into the prompt")
    class Context {

        @Test
        void namesTheProfileItWasGiven() {
            assertTrue(Rules.sheriffRulesContext("JAVA_HEXAGONAL", List.of(), 80).contains("JAVA_HEXAGONAL"));
            assertTrue(Rules.sheriffRulesContext("TYPESCRIPT", List.of(), 80).contains("TYPESCRIPT"));
        }

        @Test
        @DisplayName("with no catalog, the findings are the only source of truth")
        void withoutACatalogItPointsToDescriptionAndHowToSolve() {
            String context = Rules.sheriffRulesContext("JAVA", List.of(), 80);
            assertTrue(context.contains("description"));
            assertTrue(context.contains("howToSolve"));
        }

        @Test
        void aProfileWithNoRulesInTheCatalogFallsBackToo() {
            assertTrue(Rules.sheriffRulesContext("COBOL", ALL, 80).contains("howToSolve"));
        }

        @Test
        void listsTheProfileRulesWhenThereIsACatalog() {
            String context = Rules.sheriffRulesContext("JAVA", ALL, 80);
            assertTrue(context.contains("JavaDocCommentsInMethod"));
            assertTrue(context.contains("Remove it."));
            assertFalse(context.contains("JavaDomainDependsOnAdapter"));
            assertFalse(context.contains("TypeScriptThing"));
        }

        @Test
        void saysSoWhenTheListIsOnlyASuperset() {
            String context = Rules.sheriffRulesContext("JAVA_POM", ALL, 80);
            assertTrue(context.contains("superset"));
            assertTrue(context.contains("JavaPomSomething"));
        }

        @Test
        @DisplayName("the risk of listing rules up front is a fixer treating them as a to-do list")
        void tellsTheFixerTheListIsNotAToDoList() {
            assertTrue(Rules.sheriffRulesContext("JAVA", ALL, 80).contains("not a to-do list"));
        }

        @Test
        void truncatesAHugeProfileAndSaysWhereTheRestIs() {
            String context = Rules.sheriffRulesContext("JAVA", manyRules(100), 10);
            assertTrue(context.contains("Rule0"));
            assertFalse(context.contains("Rule99"));
            assertTrue(context.contains("90 more"));
            assertTrue(context.contains("SHERIFF_RULES.md"));
        }

        @Test
        void noCapWhenMaxRulesIsZero() {
            String context = Rules.sheriffRulesContext("JAVA", manyRules(100), 0);
            assertTrue(context.contains("Rule99"));
            assertFalse(context.contains("more --"));
        }

        private List<SheriffRule> manyRules(int count) {
            List<SheriffRule> rules = new ArrayList<>();
            for (int n = 0; n < count; n++) {
                rules.add(new SheriffRule("Rule" + n, "x", "", "java", "", "", List.of("JAVA")));
            }
            return rules;
        }
    }

    @Nested
    @DisplayName("the accepted code that goes into a prompt")
    class AcceptedCode {

        private static List<SheriffRule> manyRules(int count) {
            List<SheriffRule> rules = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                rules.add(new SheriffRule("Rule" + index, "d", "", "java", "c", "", List.of(),
                        "example " + index));
            }
            return rules;
        }

        private static List<SheriffFinding> findingsFor(List<SheriffRule> rules) {
            List<SheriffFinding> findings = new ArrayList<>();
            for (SheriffRule rule : rules) {
                findings.add(new SheriffFinding("A.java", "x", "y", rule.code(),
                        SheriffFinding.ERROR, Map.of()));
            }
            return findings;
        }

        @Test
        @DisplayName("a value object missing its tests draws 11 examples at once, 7.6 KB in one prompt")
        void stopsAtTheCapAndNamesWhatItLeftOut() {
            List<SheriffRule> rules = manyRules(10);
            String context = Rules.acceptedCodeContext(findingsFor(rules), rules, 3);
            assertTrue(context.contains("example 2"));
            assertFalse(context.contains("example 3"));
            assertTrue(context.contains("and 7 more"));
            assertTrue(context.contains("Rule9"));
            assertTrue(context.contains("--fixer"));
        }

        @Test
        void aCapOfZeroMeansNoCap() {
            List<SheriffRule> rules = manyRules(10);
            String context = Rules.acceptedCodeContext(findingsFor(rules), rules, 0);
            assertTrue(context.contains("example 9"));
            assertFalse(context.contains("more:"));
        }

        @Test
        void underTheCapNothingIsAnnouncedAsMissing() {
            List<SheriffRule> rules = manyRules(2);
            assertFalse(Rules.acceptedCodeContext(findingsFor(rules), rules, 5).contains("more:"));
        }

        @Test
        void saysNothingWhenNoErrorInThePassHasOne() {
            List<SheriffFinding> findings = List.of(new SheriffFinding(
                    "A.java", "x", "y", "SomethingElse", SheriffFinding.ERROR, Map.of()));
            assertEquals("", Rules.acceptedCodeContext(findings, manyRules(3)));
        }

        @Test
        void saysNothingWithoutACatalog() {
            assertEquals("", Rules.acceptedCodeContext(findingsFor(manyRules(2)), List.of()));
        }

        @Test
        @DisplayName("five files breaking one rule do not need five copies of the snippet")
        void aRuleBrokenTwiceIsShownOnce() {
            List<SheriffRule> rules = manyRules(1);
            List<SheriffFinding> repeated = new ArrayList<>();
            for (int index = 0; index < 5; index++) {
                repeated.addAll(findingsFor(rules));
            }
            String context = Rules.acceptedCodeContext(repeated, rules);
            assertEquals(1, context.split("example 0", -1).length - 1);
        }
    }

    @Nested
    @DisplayName("lists of profiles")
    class ProfileLists {

        @Test
        @DisplayName("an architecture profile always runs beside its language's base one, which it does not include")
        void architectureProfilesComeWithTheirBase() {
            assertEquals("JAVA", Rules.withBaseProfiles("JAVA"));
            assertEquals("JAVA,JAVA_HEXAGONAL", Rules.withBaseProfiles("JAVA_HEXAGONAL"));
            assertEquals("JAVA,JAVA_HEXAGONAL,JAVA_DDD", Rules.withBaseProfiles("JAVA_HEXAGONAL, JAVA_DDD"));
            assertEquals("JAVA,JAVA_HEXAGONAL", Rules.withBaseProfiles("JAVA,JAVA_HEXAGONAL"));
            assertEquals("TYPESCRIPT,TYPESCRIPT_HEXAGONAL", Rules.withBaseProfiles("TYPESCRIPT_HEXAGONAL"));
            assertEquals("SOMETHING_ELSE", Rules.withBaseProfiles("SOMETHING_ELSE"));
        }

        @Test
        @DisplayName("a list is read in order, once each, and one that names nothing is left for Sheriff to refuse")
        void listsAreSplitInOrder() {
            assertEquals(List.of("JAVA", "JAVA_HEXAGONAL"), Rules.profilesIn(" JAVA ,,JAVA_HEXAGONAL,JAVA"));
            assertEquals(List.of(" "), Rules.profilesIn(" "));
            assertEquals("java", Rules.languageForProfile("JAVA,JAVA_HEXAGONAL"));
        }

        @Test
        @DisplayName("the rules of a list are those of every profile in it")
        void selectsTheRulesOfEveryProfile() {
            SheriffRule base = new SheriffRule("A", "d", "", "java", "", "", List.of("JAVA"));
            SheriffRule layers = new SheriffRule("B", "d", "", "java", "", "", List.of("JAVA_HEXAGONAL"));
            SheriffRule ddd = new SheriffRule("C", "d", "", "java", "", "", List.of("JAVA_DDD"));
            RuleSelection selection = Rules.selectRulesForProfile(List.of(base, layers, ddd), "JAVA,JAVA_HEXAGONAL");
            assertEquals(List.of(base, layers), selection.rules());
            assertTrue(selection.exact());
        }
    }
}
