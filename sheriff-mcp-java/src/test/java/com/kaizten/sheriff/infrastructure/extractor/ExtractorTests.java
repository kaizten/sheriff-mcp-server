package com.kaizten.sheriff.infrastructure.extractor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import com.kaizten.sheriff.infrastructure.catalog.MarkdownRuleCatalog;
import com.kaizten.sheriff.infrastructure.process.FakeProcessRunner;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the rule-catalog extractor.
 *
 * <p>Every fixture here is a real excerpt of what the Sheriff image actually
 * printed — javap constant dumps, the {@code fix -l} table, {@code TestFactory}
 * bytecode — so these pin the parsing against the real formats rather than an
 * invented one. Nothing runs Docker: reading the image is one seam away.
 */
class ExtractorTests {

    private static final String JAVAP = """
            Compiled from "JavaDocCommentsInMethodKaiztenSheriffErrorMessage.java"
            public class com.kaizten.analysis.error.message.types.java.format.JavaDocCommentsInMethodKaiztenSheriffErrorMessage extends com.kaizten.analysis.error.message.KaiztenSheriffErrorMessage {
              private static final java.lang.String ERROR_LINE_COMMENT = "JavaDoc comment found in method '%s' at lines %d-%d.";
              private static final java.lang.String HOWTOSOLVE_LINE_COMMENTS = "Remove JavaDoc comment found in method '%s'.";
            }
            Compiled from "BracesForStatementsKaiztenSheriffErrorMessage.java"
            public class com.kaizten.analysis.error.message.types.java.format.BracesForStatementsKaiztenSheriffErrorMessage extends com.kaizten.analysis.error.message.KaiztenSheriffErrorMessage {
              private static final java.lang.String DESCRIPTION = "Braces are not used in for statement '%s'.";
              private static final java.lang.String FIXER = "java/braces-for-statements/target/braces-for-statements.jar";
              private static final java.lang.String HOW_TO_SOLVE = "Add braces.";
            }
            Compiled from "KaiztenSheriffErrorMessage.java"
            public abstract class com.kaizten.analysis.error.message.KaiztenSheriffErrorMessage {
              private static final java.lang.String DESCRIPTION = "not a rule of its own";
            }
            Compiled from "JavaJavaDocCommentInMethodChecker.java"
            public class com.kaizten.analysis.checker.java.method.javadoc.JavaJavaDocCommentInMethodChecker extends com.kaizten.analysis.checker.java.base.JavaFileSheriffCheckerBase {
              private static final java.lang.String TAG_PARAM = "param";
              private static final java.lang.String ERROR_METHOD_MISSING_JAVADOC = "Method '%s' in file '%s' does not have JavaDoc comment";
              private static final java.lang.String HOWTOSOLVE_ADD_JAVADOC = "Add a JavaDoc comment to method '%s'.";
              private static final java.lang.String ERROR_PARAM_EMPTY = "Parameter '%s' has an empty description";
              private static final java.lang.String HOWTOSOLVE_PARAM_EMPTY = "Describe parameter '%s'.";
            }
            Compiled from "JavaMethodName.java"
            public class com.kaizten.analysis.checker.java.method.name.JavaMethodName extends com.kaizten.analysis.checker.java.base.JavaFileSheriffCheckerBase {
              private static final java.lang.String ERROR_METHOD_NAME_CONVENTION = "Method name '%s' does not follow Java naming conventions";
              private static final java.lang.String HOWTOSOLVE_METHOD_NAME_CONVENTION = "Rename method '%s'.";
              private static final java.lang.String METHOD_NAME_REGEX = "^[a-z][a-zA-Z0-9]*$";
            }
            """;

    private static final String FIXER_LIST = """
            SLF4J(I): Connected with provider
            Index                Reference Code                Fixer         Type
                0           BracesForStatements    java/braces-for-statements/target/braces-for-statements.jar    JAVA
                1         JavaDocCommentsInMethod                    NO AVAILABLE NO AVAILABLE
                2                  SomeOtherRule                    NO AVAILABLE NO AVAILABLE
              Available fixers:  1
                  Total fixers: 3
            """;

    private static final String FACTORY = """
            @@@SWITCHMAP
                  12: getstatic     #13     // Field com/kaizten/sheriff/core/enumerate/AnalysisTest.JAVA:Lcom/kaizten/sheriff/core/enumerate/AnalysisTest;
                  18: iconst_1
                  19: iastore
                  27: getstatic     #23     // Field com/kaizten/sheriff/core/enumerate/AnalysisTest.JAVA_DDD:Lcom/kaizten/sheriff/core/enumerate/AnalysisTest;
                  33: bipush        6
                  34: iastore
                  42: getstatic     #26     // Field com/kaizten/sheriff/core/enumerate/AnalysisTest.JAVA_POM:Lcom/kaizten/sheriff/core/enumerate/AnalysisTest;
                  48: bipush        9
                  49: iastore
            @@@CREATE
              public static com.kaizten.analysis.checker.base.SheriffChecker create(com.kaizten.sheriff.core.enumerate.AnalysisTest);
                Code:
                   8: tableswitch   { // 1 to 6
                                 1: 80
                                 6: 130
                           default: 210
                      }
                  80: new           #28                 // class com/kaizten/analysis/checker/java/JavaChecker
                  83: dup
                 130: new           #40                 // class com/kaizten/analysis/checker/java/hexagonal/domain/JavaDDDChecker
            """;

    private static final String GRAPH = """
            @@CLASS com/kaizten/analysis/checker/java/JavaChecker
            com/kaizten/analysis/checker/java/method/javadoc/JavaJavaDocCommentInMethodChecker
            com/kaizten/analysis/error/message/types/java/format/JavaDocCommentsInMethodKaiztenSheriffErrorMessage
            @@CLASS com/kaizten/analysis/checker/java/method/javadoc/JavaJavaDocCommentInMethodChecker
            com/kaizten/analysis/checker/java/JavaChecker
            @@CLASS com/kaizten/analysis/checker/java/hexagonal/domain/JavaDDDChecker
            com/kaizten/analysis/checker/java/method/name/JavaMethodName
            """;

    @Nested
    @DisplayName("rules Sheriff names with a reference code")
    class MessageClasses {

        private final Map<String, CatalogEntry> rules = ConstantsParser.messageClasses(JAVAP);

        @Test
        void oneRulePerMessageClass() {
            assertEquals(Set.of("JavaDocCommentsInMethod", "BracesForStatements"), rules.keySet());
        }

        @Test
        void readsDescriptionAndRemedy() {
            assertEquals("JavaDoc comment found in method '%s' at lines %d-%d.",
                    rules.get("JavaDocCommentsInMethod").rule().description());
            assertEquals("Remove JavaDoc comment found in method '%s'.",
                    rules.get("JavaDocCommentsInMethod").rule().howToSolve());
        }

        @Test
        void languageAndCategoryComeFromThePackage() {
            assertEquals("java", rules.get("JavaDocCommentsInMethod").rule().language());
            assertEquals("format", rules.get("JavaDocCommentsInMethod").rule().category());
        }

        @Test
        @DisplayName("a FIXER constant is a path, not message text")
        void theFixerPathIsNotPartOfTheDescription() {
            assertEquals("Braces are not used in for statement '%s'.",
                    rules.get("BracesForStatements").rule().description());
        }

        @Test
        @DisplayName("'SCRIPT' is a substring of 'DESCRIPTION' -- the bug that emptied 135 of 282 rules")
        void descriptionIsNotMistakenForAScriptConstant() {
            assertFalse(rules.get("BracesForStatements").rule().description().isEmpty());
            assertTrue(ConstantsParser.isFixerConstant("FIXER_SCRIPT"));
            assertTrue(ConstantsParser.isFixerConstant("SCRIPT_NAME"));
            assertFalse(ConstantsParser.isFixerConstant("DESCRIPTION"));
        }

        @Test
        void theAbstractBaseClassIsNotARule() {
            assertEquals(2, rules.size());
        }
    }

    @Nested
    @DisplayName("rules Sheriff reports with no reference code")
    class CheckerClasses {

        private final List<CatalogEntry> rules = ConstantsParser.checkerClasses(JAVAP, Set.of());

        @Test
        void oneRulePerErrorConstant() {
            assertEquals(
                    List.of("JavaJavaDocCommentInMethod.METHOD_MISSING_JAVADOC",
                            "JavaJavaDocCommentInMethod.PARAM_EMPTY",
                            "JavaMethodName.METHOD_NAME_CONVENTION"),
                    rules.stream().map(CatalogEntry::code).toList());
        }

        @Test
        @DisplayName("each error is paired with the remedy that follows it, not one with a matching name")
        void pairsByDeclarationOrder() {
            assertEquals("Add a JavaDoc comment to method '%s'.", rules.get(0).rule().howToSolve());
            assertEquals("Describe parameter '%s'.", rules.get(1).rule().howToSolve());
        }

        @Test
        void theseHaveNoReferenceCodeBecauseSheriffReportsNone() {
            assertTrue(rules.stream().allMatch(rule -> rule.referenceCode().isEmpty()));
            assertTrue(rules.stream().allMatch(rule -> rule.source().equals(CatalogEntry.CHECKER_SOURCE)));
        }

        @Test
        void helperConstantsAreNotRules() {
            assertTrue(rules.stream().noneMatch(rule -> rule.rule().description().equals("param")));
            assertTrue(rules.stream().noneMatch(rule -> rule.rule().description().contains("[a-z]")));
        }

        @Test
        @DisplayName("a checker is one by package, not by name -- JavaMethodName is not called *Checker")
        void aCheckerNotNamedCheckerStillCounts() {
            assertTrue(rules.stream().anyMatch(rule -> rule.code().startsWith("JavaMethodName.")));
        }

        @Test
        void textAlreadyCarriedByAMessageClassIsSkipped() {
            List<CatalogEntry> deduplicated = ConstantsParser.checkerClasses(
                    JAVAP, Set.of("Method '%s' in file '%s' does not have JavaDoc comment"));
            assertTrue(deduplicated.stream().noneMatch(rule -> rule.code().endsWith("METHOD_MISSING_JAVADOC")));
        }
    }

    @Nested
    @DisplayName("the fixer table and the profile switch")
    class Sources {

        @Test
        void readsFixerPathsAndBlanks() {
            Map<String, String> fixers = FixerListParser.parse(FIXER_LIST);
            assertEquals("java/braces-for-statements/target/braces-for-statements.jar",
                    fixers.get("BracesForStatements"));
            assertEquals("", fixers.get("JavaDocCommentsInMethod"));
            assertEquals(3, fixers.size());
        }

        @Test
        void mapsEachProfileToItsRootChecker() {
            assertEquals(
                    Map.of("JAVA", "com/kaizten/analysis/checker/java/JavaChecker",
                            "JAVA_DDD", "com/kaizten/analysis/checker/java/hexagonal/domain/JavaDDDChecker"),
                    ProfileMapper.profileCheckers(FACTORY));
        }

        @Test
        @DisplayName("a profile that falls through to the default branch gets no checker invented for it")
        void aProfileWithNoCaseIsNotMapped() {
            assertFalse(ProfileMapper.profileCheckers(FACTORY).containsKey("JAVA_POM"));
            assertEquals(Map.of(), ProfileMapper.profileCheckers("garbage"));
        }

        @Test
        void followsReferencesTransitivelyAndSurvivesCycles() {
            Map<String, Set<String>> graph = ProfileMapper.referenceGraph(GRAPH);
            Set<String> reachable =
                    ProfileMapper.reachableFrom("com/kaizten/analysis/checker/java/JavaChecker", graph);
            assertTrue(reachable.contains(
                    "com/kaizten/analysis/checker/java/method/javadoc/JavaJavaDocCommentInMethodChecker"));
            assertFalse(reachable.contains("com/kaizten/analysis/checker/java/method/name/JavaMethodName"));
            assertEquals(Set.of("a", "b"), ProfileMapper.reachableFrom("a", Map.of("a", Set.of("b"), "b", Set.of("a"))));
        }
    }

    @Nested
    @DisplayName("reading the profiles out of Sheriff's own tree")
    class Tree {

        private static final String TREE = """
                [ {"displayName": "Java", "children": [
                    {"displayName": "Java format", "children": [
                      {"displayName": "Java javadoc comments in method", "children": []}]},
                    {"displayName": "Java to string method", "children": []}]},
                  {"displayName": "Java DDD", "children": [
                    {"displayName": "Java method name", "children": []}]} ]""";
        private static final String HELP = """
                  -o, --output
                    Possible Values: [NO_OUTPUT, JSON]
                * -t, --test
                    Possible Values: [JAVA, JAVA_DDD, VUEJS]
                """;

        private final List<String> names = TestTreeMapper.profileNames(HELP);

        @Test
        @DisplayName("--test is not the first enum in the usage, so it is picked by contents")
        void readsTheProfileNamesOutOfTheUsageText() {
            assertEquals(List.of("JAVA", "JAVA_DDD", "VUEJS"), names);
            assertFalse(names.contains("JSON"));
        }

        @Test
        void noEnumNamingProfilesIsAnEmptyList() {
            assertTrue(TestTreeMapper.profileNames("nothing here").isEmpty());
        }

        @Test
        void collectsTheLeavesOfEachProfile() {
            assertEquals(List.of("Java javadoc comments in method", "Java to string method"),
                    TestTreeMapper.checksByProfile(TREE, names).get("JAVA"));
        }

        @Test
        @DisplayName("a profile Sheriff names that is not a --test value is reported, not dropped")
        void reportsAProfileItCannotPlace() {
            String tree = "[ {\"displayName\": \"Klingon\", \"children\": []} ]";
            assertTrue(TestTreeMapper.checksByProfile(tree, names).isEmpty());
            assertEquals(List.of("Klingon"), TestTreeMapper.unplacedProfiles(tree, names));
        }

        @Test
        void aDisplayNameMatchesItsEnumThroughPunctuation() {
            String tree = "[ {\"displayName\": \"Vue.js\", \"children\": []} ]";
            assertTrue(TestTreeMapper.checksByProfile(tree, names).containsKey("VUEJS"));
        }

        @Test
        void malformedJsonIsNoProfilesRatherThanACrash() {
            assertTrue(TestTreeMapper.checksByProfile("not json at all", names).isEmpty());
        }

        @Test
        void aCodeMatchesTheCheckItIsNamedAfter() {
            assertTrue(TestTreeMapper.matches("SortedImport", "Java sorted import"));
            assertTrue(TestTreeMapper.matches("JavaAsteriskImport", "Java asterisk import"));
            assertTrue(TestTreeMapper.matches("AsteriskImport", "Java asterisk import"));
        }

        @Test
        @DisplayName("one check emits several messages, so both halves belong to it")
        void oneCheckCanOwnSeveralMessages() {
            assertTrue(TestTreeMapper.matches("SentencePositionFirstLine", "Java sentence position"));
            assertTrue(TestTreeMapper.matches("SentencePositionLastLine", "Java sentence position"));
        }

        @Test
        @DisplayName("JavaDoc starts with Java as part of a word, not as a prefix")
        void javadocIsNotALanguagePrefix() {
            assertTrue(TestTreeMapper.matches(
                    "JavaDocCommentInConstructorIsEmpty", "Java javadoc comment in constructor"));
        }

        @Test
        void aConstantSuffixOnTheCodeIsIgnored() {
            assertTrue(TestTreeMapper.matches("JavaAsteriskImport.ASTERISK_IMPORT", "Java asterisk import"));
        }

        @Test
        void unrelatedNamesDoNotMatch() {
            assertFalse(TestTreeMapper.matches("SortedImport", "Java to string method"));
            assertFalse(TestTreeMapper.matches("JavaNestedTypes", "Java switch sentence"));
        }

        @Test
        @DisplayName("the tree wins over the bytecode when the image offers it")
        void theTreeIsPreferredOverTheApproximation() {
            List<CatalogEntry> entries = CatalogBuilder.build(JAVAP, FIXER_LIST, FACTORY, GRAPH, TREE, names, "", "");
            CatalogEntry rule = entries.stream()
                    .filter(entry -> entry.code().equals("JavaDocCommentsInMethod"))
                    .findFirst().orElseThrow();
            assertEquals(List.of("JAVA"), rule.rule().profiles());
        }

        @Test
        @DisplayName("a check with no rule code is reported, since a silent drop looks like the rule not existing")
        void reportsAnUnmatchedCheck() {
            List<CatalogEntry> entries = CatalogBuilder.build(JAVAP, FIXER_LIST, FACTORY, GRAPH, TREE, names, "", "");
            assertTrue(TestTreeMapper.unmatchedChecks(entries, TREE, names).contains("JAVA: Java to string method"));
        }
    }

    @Nested
    @DisplayName("the code Sheriff writes, read out of its own fixers")
    class Examples {

        private static final String DUMP = """
                @@FIXER a/a.py
                import os

                def method_source(
                    class_name: str,
                ) -> str:
                    return (
                        f"assertEquals({class_name}, x);"
                    )


                def other():
                    pass

                @@FIXER b/b.py
                def repair(path):
                    return path
                """;

        @Test
        void keepsTheWholeFunctionVerbatim() {
            String example = FixerExampleParser.parse(DUMP).get("a/a.py");
            assertTrue(example.startsWith("def method_source("));
            assertTrue(example.contains("assertEquals"));
        }

        @Test
        @DisplayName("a signature closing with ') -> str:' in column zero is still the function")
        void aMultiLineSignatureIsPartOfTheFunction() {
            assertTrue(FixerExampleParser.parse(DUMP).get("a/a.py").contains(") -> str:"));
        }

        @Test
        void stopsAtTheNextTopLevelDefinition() {
            assertFalse(FixerExampleParser.parse(DUMP).get("a/a.py").contains("def other"));
        }

        @Test
        @DisplayName("sorting imports has no shape to show, so it carries no example")
        void aScriptThatTransformsRatherThanWritesHasNoExample() {
            assertFalse(FixerExampleParser.parse(DUMP).containsKey("b/b.py"));
        }

        @Test
        void anEmptyDumpYieldsNothing() {
            assertTrue(FixerExampleParser.parse("").isEmpty());
        }

        @Test
        @DisplayName("filling in a profile must not empty the example, or any other field")
        void rebuildingAnEntryKeepsEveryField() {
            CatalogEntry entry = new CatalogEntry(
                    new SheriffRule("Code", "what", "how", "java", "format", "", List.of()),
                    "Code", "message class", "owner");
            CatalogEntry filled = entry
                    .withFixer("java/a/a.py")
                    .withExample("def method_source(): pass")
                    .withProfiles(List.of("JAVA"));
            assertEquals("java/a/a.py", filled.rule().fixer());
            assertEquals("def method_source(): pass", filled.rule().example());
            assertEquals(List.of("JAVA"), filled.rule().profiles());
        }
    }


    private static final String MESSAGE_LINKS = """
            public class com.kaizten.analysis.test.java.method.javadoc.JavaJavaDocCommentInMethodChecker {
              public java.util.List check(java.io.File);
                Code:
                 112: ldc           #176                // String Method \\'%s\\' in file \\'%s\\' does not have JavaDoc comment
                 138: ldc           #178                // String Add a JavaDoc comment to the method \\'%s\\'.
                 174: invokestatic  #180                // Method com/kaizten/analysis/error/message/types/JavaJavaDocCommentInMethodMessage6KaiztenSheriffErrorMessage.of:(Ljava/lang/String;Ljava/lang/String;)V

              private void other();
                Code:
                  10: ldc           #200                // String Import \\'%s\\' uses asterisk imports.
                  20: ldc           #202                // String Replace it with explicit imports.
                  30: invokestatic  #204                // Method com/kaizten/analysis/error/message/types/JavaAsteriskImportMessage1KaiztenSheriffErrorMessage.of:(Ljava/lang/String;Ljava/lang/String;)V
            }
            """;

    @Nested
    @DisplayName("pairing a message class with the checker text")
    class MessageLinks {

        private final Map<String, CatalogEntry> links = MessageLinkParser.parse(MESSAGE_LINKS);

        @Test
        void findsEveryMessageClassInTheDump() {
            assertEquals(Set.of("JavaJavaDocCommentInMethodMessage6", "JavaAsteriskImportMessage1"), links.keySet());
        }

        @Test
        void readsTheTwoStringsBeforeTheCallAsDescriptionThenHowToSolve() {
            SheriffRule rule = links.get("JavaJavaDocCommentInMethodMessage6").rule();
            assertEquals("Method '%s' in file '%s' does not have JavaDoc comment", rule.description());
            assertEquals("Add a JavaDoc comment to the method '%s'.", rule.howToSolve());
        }

        @Test
        void undoesTheEscapingJavapApplies() {
            assertFalse(links.get("JavaAsteriskImportMessage1").rule().description().contains("\\'"));
        }

        @Test
        void namesTheCheckerThatRaisesIt() {
            assertEquals("com/kaizten/analysis/test/java/method/javadoc/JavaJavaDocCommentInMethodChecker",
                    links.get("JavaJavaDocCommentInMethodMessage6").owner());
        }

        @Test
        void takesLanguageAndCategoryFromTheCheckerPackage() {
            SheriffRule rule = links.get("JavaJavaDocCommentInMethodMessage6").rule();
            assertEquals("java", rule.language());
            assertEquals("method.javadoc", rule.category());
        }

        @Test
        void doesNotPairAStringFromOneMethodWithACallInTheNext() {
            String dump = """
                    public class com.kaizten.analysis.test.java.X {
                      void a();
                        Code:
                          1: ldc           #1                  // String Orphan text.
                      void b();
                        Code:
                          9: invokestatic  #2                  // Method com/kaizten/analysis/error/message/types/XMessage1KaiztenSheriffErrorMessage.of:(Ljava/lang/String;Ljava/lang/String;)V
                    }
                    """;
            assertTrue(MessageLinkParser.parse(dump).isEmpty());
        }

        @Test
        void aRepeatedLoadDoesNotDisplaceTheDescription() {
            String dump = """
                    public class com.kaizten.analysis.test.java.sentence.JavaSwitchSentenceChecker {
                      private static java.util.List check();
                        Code:
                         103: ldc           #73                 // String Hardcoded string in switch case label.
                         130: ldc           #87                 // String Use constants or enums instead.
                         141: ldc           #87                 // String Use constants or enums instead.
                         143: invokestatic  #95                 // Method com/kaizten/analysis/error/message/types/JavaSwitchSentenceMessage1KaiztenSheriffErrorMessage.of:(Ljava/lang/String;Ljava/lang/String;)V
                    }
                    """;
            SheriffRule rule = MessageLinkParser.parse(dump).get("JavaSwitchSentenceMessage1").rule();
            assertEquals("Hardcoded string in switch case label.", rule.description());
            assertEquals("Use constants or enums instead.", rule.howToSolve());
        }

        @Test
        void oneStringServesAsBothDescriptionAndRemedy() {
            String dump = """
                    public class com.kaizten.analysis.test.java.X {
                      void check();
                        Code:
                          27: ldc           #26                 // String Specify a concrete type.
                          39: ldc           #26                 // String Specify a concrete type.
                          41: invokestatic  #34                 // Method com/kaizten/analysis/error/message/types/XMessage1KaiztenSheriffErrorMessage.of:(Ljava/lang/String;Ljava/lang/String;)V
                    }
                    """;
            SheriffRule rule = MessageLinkParser.parse(dump).get("XMessage1").rule();
            assertEquals("Specify a concrete type.", rule.description());
            assertEquals("Specify a concrete type.", rule.howToSolve());
        }

        @Test
        void anEmptyDumpIsNoLinksRatherThanAFailure() {
            assertTrue(MessageLinkParser.parse("").isEmpty());
        }

        @Test
        void aCodeWithNoTextOfItsOwnGetsTheCheckerText() {
            List<CatalogEntry> entries =
                    CatalogBuilder.build(JAVAP, FIXER_LIST, "", "", "", List.of(), "", MESSAGE_LINKS);
            CatalogEntry entry = entries.stream()
                    .filter(candidate -> candidate.code().equals("JavaAsteriskImportMessage1"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("Import '%s' uses asterisk imports.", entry.rule().description());
        }
    }

    @Nested
    @DisplayName("the catalog as a whole, from the bytecode fallback")
    class Catalog {

        private final List<CatalogEntry> entries = CatalogBuilder.build(JAVAP, FIXER_LIST, FACTORY, GRAPH, "", List.of(), "", "");

        private CatalogEntry byCode(String code) {
            return entries.stream().filter(entry -> entry.code().equals(code)).findFirst().orElseThrow();
        }

        @Test
        void mergesBothSources() {
            assertEquals("JavaDoc comment found in method '%s' at lines %d-%d.",
                    byCode("JavaDocCommentsInMethod").rule().description());
            assertEquals("Method name '%s' does not follow Java naming conventions",
                    byCode("JavaMethodName.METHOD_NAME_CONVENTION").rule().description());
        }

        @Test
        @DisplayName("a code fix -l knows but nothing describes still appears, or it looks like it does not exist")
        void aReferenceCodeWithNoMessageClassStillAppears() {
            assertEquals("", byCode("SomeOtherRule").rule().description());
        }

        @Test
        void attachesTheFixerByReferenceCode() {
            assertTrue(byCode("BracesForStatements").rule().hasFixer());
            assertFalse(byCode("JavaDocCommentsInMethod").rule().hasFixer());
        }

        @Test
        void attributesRulesToTheProfilesThatReachThem() {
            assertEquals(List.of("JAVA"), byCode("JavaDocCommentsInMethod").rule().profiles());
            assertEquals(List.of("JAVA_DDD"), byCode("JavaMethodName.METHOD_NAME_CONVENTION").rule().profiles());
            assertEquals(Map.of("JAVA", 3, "JAVA_DDD", 1), CatalogBuilder.profileCounts(entries));
        }

        @Test
        void worksWithoutTheProfileDumps() {
            List<CatalogEntry> unmapped = CatalogBuilder.build(JAVAP, FIXER_LIST, "", "", "", List.of(), "", "");
            assertTrue(unmapped.stream().allMatch(entry -> entry.rule().profiles().isEmpty()));
        }
    }

    @Nested
    @DisplayName("writing it out")
    class Writing {

        @TempDir
        private Path directory;

        private final CatalogWriter writer = new CatalogWriter();
        private final List<CatalogEntry> entries = CatalogBuilder.build(JAVAP, FIXER_LIST, FACTORY, GRAPH, "", List.of(), "", "");

        @Test
        void theJsonCarriesEverythingTheReaderNeeds() throws IOException {
            String document = writer.toJson(entries, "kaizten/sheriff:latest", "sha256:abc");
            assertTrue(document.contains("\"rule_count\" : " + entries.size()));
            assertTrue(document.contains("sha256:abc"));
            assertTrue(document.contains("JavaDocCommentsInMethod"));
        }

        @Test
        @DisplayName("the Markdown it writes is readable back by the reader, so the two cannot drift")
        void theMarkdownRoundTrips() throws IOException {
            Path document = directory.resolve("SHERIFF_RULES.md");
            Files.writeString(document, writer.toMarkdown(entries, "kaizten/sheriff:latest"));
            List<com.kaizten.sheriff.domain.valueobject.SheriffRule> readBack =
                    new MarkdownRuleCatalog(document).allRules();
            assertEquals(entries.size(), readBack.size());
            com.kaizten.sheriff.domain.valueobject.SheriffRule javadoc = readBack.stream()
                    .filter(rule -> rule.code().equals("JavaDocCommentsInMethod"))
                    .findFirst()
                    .orElseThrow();
            assertEquals("Remove JavaDoc comment found in method '%s'.", javadoc.howToSolve());
            assertEquals(List.of("JAVA"), javadoc.profiles());
        }

        @Test
        void writesBothDocuments() throws IOException {
            Path json = directory.resolve("rules_catalog.json");
            Path markdown = directory.resolve("SHERIFF_RULES.md");
            writer.write(entries, "img", "", json, markdown);
            assertTrue(Files.readString(json).contains("JavaDocCommentsInMethod"));
            assertTrue(Files.readString(markdown).startsWith("# Sheriff rule catalog"));
        }
    }

    @Nested
    @DisplayName("reading the image")
    class Reading {

        @Test
        void eachReadRunsTheImage() {
            FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "output", ""));
            ImageReader reader = new ImageReader(runner, "kaizten/sheriff:latest", Path.of("."));
            assertEquals("output", reader.constants());
            assertEquals("output", reader.fixerList());
            assertTrue(runner.commands().get(0).contains("--entrypoint"));
            assertTrue(runner.commands().get(1).contains("fix"));
        }

        @Test
        @DisplayName("a failure to read the image says why instead of producing an empty catalog")
        void aFailedReadIsAnError() {
            ImageReader reader = new ImageReader(
                    FakeProcessRunner.always(ProcessOutcome.unavailable("'docker' is not installed")),
                    "kaizten/sheriff:latest", Path.of("."));
            IllegalStateException thrown = assertThrows(IllegalStateException.class, reader::constants);
            assertTrue(thrown.getMessage().contains("docker"));
        }
    }
}
