package com.kaizten.sheriff.infrastructure.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for both rule-catalog readers, against real files on disk: dealing
 * with what is actually there — including files that are missing, truncated or
 * written by a person — is the whole job of these adapters.
 */
class RuleCatalogTests {

    private static final Path EXTRACTED_CATALOG = committed("rules_catalog.json");
    private static final Path EXTRACTED_MARKDOWN = committed("SHERIFF_RULES.md");

    @TempDir
    private Path directory;

    /**
     * Locates a real committed catalog file: the Python module's copy where the
     * two modules sit side by side, so the readers are checked against the
     * implementation this one was ported from, and this module's own copy
     * otherwise, so the check still runs when it is delivered on its own.
     *
     * @param name file name of the catalog, as both modules commit it
     * @return path to the copy to read
     */
    private static Path committed(String name) {
        Path sibling = Path.of("..", "sheriff-agent", name);
        return Files.exists(sibling) ? sibling : Path.of(name);
    }

    @Nested
    @DisplayName("the JSON catalog the extractor produces")
    class Json {

        private static final String CATALOG = """
                {"image":"kaizten/sheriff:latest","rules":[
                  {"code":"JavaDocCommentsInMethod","description":"JavaDoc comment found in method '%s'.",
                   "how_to_solve":"Remove it.","language":"java","category":"format","fixer":"",
                   "profiles":["JAVA","JAVA_HEXAGONAL"]},
                  {"code":"BracesForStatements","description":"No braces.","fixer":"java/braces.jar"},
                  {"description":"an entry with no code at all"}
                ]}
                """;

        private List<SheriffRule> read(String content) throws IOException {
            Path file = directory.resolve("rules.json");
            Files.writeString(file, content);
            return new JsonRuleCatalog(file).allRules();
        }

        @Test
        void readsEveryField() throws IOException {
            SheriffRule rule = read(CATALOG).get(0);
            assertEquals("JavaDocCommentsInMethod", rule.code());
            assertEquals("Remove it.", rule.howToSolve());
            assertEquals("java", rule.language());
            assertEquals("format", rule.category());
            assertEquals(List.of("JAVA", "JAVA_HEXAGONAL"), rule.profiles());
            assertFalse(rule.hasFixer());
        }

        @Test
        void missingFieldsDefaultInsteadOfFailing() throws IOException {
            SheriffRule rule = read(CATALOG).get(1);
            assertEquals("", rule.language());
            assertEquals(List.of(), rule.profiles());
            assertTrue(rule.hasFixer());
        }

        @Test
        void skipsEntriesWithNoCode() throws IOException {
            assertEquals(2, read(CATALOG).size());
        }

        @Test
        @DisplayName("no catalog is a supported state, not a misconfiguration")
        void aMissingOrCorruptFileYieldsNoRules() throws IOException {
            assertEquals(List.of(), new JsonRuleCatalog(directory.resolve("nope.json")).allRules());
            assertEquals(List.of(), read("{not json"));
        }

        @Test
        @DisplayName("the real catalog committed in this repository is readable")
        void readsTheRealExtractedCatalog() {
            Assumptions.assumeTrue(Files.exists(EXTRACTED_CATALOG), "the extracted catalog is not present");
            List<SheriffRule> rules = new JsonRuleCatalog(EXTRACTED_CATALOG).allRules();
            assertTrue(rules.size() > 400, "expected the whole catalog, got " + rules.size());
            assertTrue(rules.stream().anyMatch(rule -> rule.runsIn("JAVA")));
            assertTrue(rules.stream().allMatch(rule -> !rule.code().isEmpty()));
        }
    }

    @Nested
    @DisplayName("a rule list written as Markdown")
    class Markdown {

        private static final String DOCUMENT = """
                # Sheriff rule catalog

                Some preamble that is not a rule.

                ## java (2 rules)

                ### `JavaDocCommentsInMethod`

                *format · checker*

                JavaDoc comment found in method '%s'.

                **How to solve:** Remove it.

                **Profiles:** `JAVA`, `JAVA_HEXAGONAL`

                ### BracesForStatements

                No braces.

                **Fixer:** `java/braces.jar`

                ## typescript (1 rules)

                ### `TypeScriptThing`

                _(no message text in the image)_
                """;

        private List<SheriffRule> read(String content) throws IOException {
            Path file = directory.resolve("RULES.md");
            Files.writeString(file, content);
            return new MarkdownRuleCatalog(file).allRules();
        }

        private static final String WITH_EXAMPLE = """
                ## java (1 rules)

                ### `JavaValueObjectShouldKeepHashCodeUnchanged`

                hashCode must stay stable.

                **Fixer:** `java/tests/a/a.py`

                **Accepted code:**

                ```python
                def method_source(name: str) -> str:
                    return (
                        f"### not a heading {name}"
                        f"**How to solve:** not a field"
                    )
                ```
                """;

        @Test
        @DisplayName("the accepted code survives the round trip through Markdown")
        void readsTheFencedExample() throws IOException {
            SheriffRule rule = read(WITH_EXAMPLE).get(0);
            assertTrue(rule.hasExample());
            assertTrue(rule.example().startsWith("def method_source("));
            assertTrue(rule.example().contains("not a heading"));
        }

        @Test
        @DisplayName("a fenced block is code, so a ### inside it is not a new rule")
        void aFenceProtectsItsContentsFromBeingParsed() throws IOException {
            List<SheriffRule> rules = read(WITH_EXAMPLE);
            assertEquals(1, rules.size());
            assertEquals("hashCode must stay stable.", rules.get(0).description());
            assertEquals("", rules.get(0).howToSolve());
        }

        @Test
        void readsOneRulePerHeading() throws IOException {
            assertEquals(
                    List.of("JavaDocCommentsInMethod", "BracesForStatements", "TypeScriptThing"),
                    read(DOCUMENT).stream().map(SheriffRule::code).toList());
        }

        @Test
        void readsTheFieldsUnderAHeading() throws IOException {
            SheriffRule rule = read(DOCUMENT).get(0);
            assertEquals("JavaDoc comment found in method '%s'.", rule.description());
            assertEquals("Remove it.", rule.howToSolve());
            assertEquals(List.of("JAVA", "JAVA_HEXAGONAL"), rule.profiles());
            assertEquals("format", rule.category());
        }

        @Test
        void languageComesFromTheSectionHeading() throws IOException {
            List<SheriffRule> rules = read(DOCUMENT);
            assertEquals("java", rules.get(0).language());
            assertEquals("typescript", rules.get(2).language());
        }

        @Test
        void backticksAroundTheCodeAreOptional() throws IOException {
            assertTrue(read(DOCUMENT).get(1).hasFixer());
        }

        @Test
        void placeholderTextIsNotADescription() throws IOException {
            assertEquals("", read(DOCUMENT).get(2).description());
        }

        @Test
        @DisplayName("the shape a hand-written list is likeliest to arrive in still works")
        void aBareListOfHeadingsIsEnough() throws IOException {
            List<SheriffRule> rules = read("### SomeRule\nDon't do the thing.\n");
            assertEquals(1, rules.size());
            assertEquals("SomeRule", rules.get(0).code());
            assertEquals("Don't do the thing.", rules.get(0).description());
        }

        @Test
        void aMissingFileYieldsNoRules() {
            assertEquals(List.of(), new MarkdownRuleCatalog(directory.resolve("nope.md")).allRules());
        }

        @Test
        @DisplayName("the Markdown the extractor generates is readable back, so the two cannot drift")
        void readsTheRealGeneratedMarkdown() {
            Assumptions.assumeTrue(Files.exists(EXTRACTED_MARKDOWN), "the generated rule list is not present");
            List<SheriffRule> rules = new MarkdownRuleCatalog(EXTRACTED_MARKDOWN).allRules();
            assertTrue(rules.size() > 400, "expected the whole catalog, got " + rules.size());
            SheriffRule javadoc = rules.stream()
                    .filter(rule -> rule.code().equals("JavaDocCommentsInMethod"))
                    .findFirst()
                    .orElseThrow();
            assertTrue(javadoc.runsIn("JAVA"));
            assertFalse(javadoc.howToSolve().isEmpty());
        }
    }
}
