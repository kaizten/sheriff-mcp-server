package com.kaizten.sheriff.infrastructure.extractor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Writes the catalog out, twice: once for the agent to read and once for a
 * person.
 *
 * <p>The JSON is what {@code JsonRuleCatalog} loads. The Markdown is the same
 * content in the shape a rule list arrives in when a human maintains it, which
 * makes it both documentation and a worked example of the format
 * {@code MarkdownRuleCatalog} accepts — if those two ever drift apart, a rule
 * list handed over in that shape stops loading.
 */
public final class CatalogWriter {

    private static final String STAMP_PATTERN = "yyyy-MM-dd'T'HH:mm:ss'Z'";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern(STAMP_PATTERN);
    private static final String GENERAL = "general";
    private static final String NEWLINE = "\n";
    private static final String BLANK = "";
    private static final String IMAGE_FIELD = "image";
    private static final String IMAGE_ID_FIELD = "image_id";
    private static final String GENERATED_AT_FIELD = "generated_at";
    private static final String RULE_COUNT_FIELD = "rule_count";
    private static final String PROFILES_FIELD = "profiles";
    private static final String EXAMPLE_FIELD = "example";
    private static final String PROFILE_SOURCE_FIELD = "profile_source";
    private static final String UNMATCHED_CHECKS_FIELD = "unmatched_checks";

    /**
     * What the profile map came from when the image has no tree to ask.
     */
    public static final String BYTECODE_SOURCE =
            "TestFactory bytecode + class reference graph (approximate)";

    /**
     * What it came from when Sheriff could simply be asked.
     */
    public static final String TREE_SOURCE = "sheriff tree --export-profile";

    /**
     * What it came from when the tree answered and the bytecode filled the
     * codes the tree does not name.
     */
    public static final String BOTH_SOURCES =
            "sheriff tree --export-profile, with the bytecode for codes it does not name";
    private static final String RULES_FIELD = "rules";
    private static final String CODE_FIELD = "code";
    private static final String REFERENCE_CODE_FIELD = "reference_code";
    private static final String DESCRIPTION_FIELD = "description";
    private static final String HOW_TO_SOLVE_FIELD = "how_to_solve";
    private static final String LANGUAGE_FIELD = "language";
    private static final String CATEGORY_FIELD = "category";
    private static final String SOURCE_FIELD = "source";
    private static final String OWNER_FIELD = "owner";
    private static final String FIXER_FIELD = "fixer";
    private static final String TITLE = "# Sheriff rule catalog";
    private static final String GENERATED_NOTE =
            "Generated from `%s` on %s by the extractor — do not edit by hand, re-run it.";
    private static final String COUNT_NOTE =
            " rules: everything Sheriff can report, with the text it uses to report it. "
                    + "`%s`/`%d` are the placeholders Sheriff fills in with the offending identifier, "
                    + "line numbers, etc.";
    private static final String FIXER_NOTE =
            "A rule with a **fixer** is one Sheriff can repair by itself (`sheriff fix`); the rest are "
                    + "what this agent exists for. A rule marked *checker* has no reference code of its "
                    + "own — Sheriff reports it with an empty `referenceCode`, and the name here is this "
                    + "project's, taken from the checker that owns it.";
    private static final String PROFILE_NOTE =
            "**Profiles** say which `--test` profile actually runs a rule — sharing a language is not "
                    + "the same as being in force. Taken from Sheriff's own `tree --export-profile`, "
                    + "extended with the image's class references for the codes that tree does not name.";
    private static final String PROFILE_TABLE_HEADING = "| Profile | Rules |";
    private static final String PROFILE_TABLE_RULE = "|---|---|";
    private static final String PROFILE_TABLE_ROW = "| `%s` | %d |";
    private static final String LANGUAGE_HEADING = "## %s (%d rules)";
    private static final String RULE_HEADING = "### `%s`";
    private static final String MARKS = "*%s*";
    private static final String MARK_SEPARATOR = " · ";
    private static final String NO_MESSAGE = "_(no message text in the image)_";
    private static final String HOW_TO_SOLVE_LABEL = "**How to solve:** %s";
    private static final String PROFILES_LABEL = "**Profiles:** %s";
    private static final String QUOTED_PROFILE = "`%s`";
    private static final String PROFILE_SEPARATOR = ", ";
    private static final String FIXER_LABEL = "**Fixer:** `%s`";
    private static final String EXAMPLE_LABEL = "**Accepted code:**";
    private static final String FENCE_OPEN = "```python";
    private static final String FENCE = "```";

    private final ObjectMapper json = new ObjectMapper();

    /**
     * The catalog as the agent reads it.
     *
     * @param entries the finished catalog
     * @param image the image it came from
     * @param imageId that image's exact identifier, may be empty
     * @return the JSON document
     * @throws IOException when it cannot be serialized
     */
    public String toJson(List<CatalogEntry> entries, String image, String imageId) throws IOException {
        return toJson(entries, image, imageId, BYTECODE_SOURCE, List.of());
    }

    /**
     * The catalog as the JSON document, recording where the profiles came from.
     *
     * @param entries the finished catalog
     * @param image the image it came from
     * @param imageId that image's identifier
     * @param profileSource what said which profile runs which rule
     * @param unmatchedChecks checks Sheriff lists that no rule code matched
     * @return the JSON document
     * @throws IOException when it cannot be serialized
     */
    public String toJson(
            List<CatalogEntry> entries,
            String image,
            String imageId,
            String profileSource,
            List<String> unmatchedChecks) throws IOException {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put(IMAGE_FIELD, image);
        document.put(IMAGE_ID_FIELD, imageId);
        document.put(GENERATED_AT_FIELD, ZonedDateTime.now(ZoneOffset.UTC).format(STAMP));
        document.put(RULE_COUNT_FIELD, entries.size());
        document.put(PROFILE_SOURCE_FIELD, profileSource);
        document.put(PROFILES_FIELD, CatalogBuilder.profileCounts(entries));
        document.put(UNMATCHED_CHECKS_FIELD, unmatchedChecks);
        List<Map<String, Object>> rules = new ArrayList<>();
        for (CatalogEntry entry : entries) {
            rules.add(describe(entry));
        }
        document.put(RULES_FIELD, rules);
        return json.writerWithDefaultPrettyPrinter().writeValueAsString(document) + NEWLINE;
    }

    /**
     * The catalog as a person reads it, grouped by language.
     *
     * @param entries the finished catalog
     * @param image the image it came from
     * @return the Markdown document
     */
    public String toMarkdown(List<CatalogEntry> entries, String image) {
        List<String> lines = new ArrayList<>(List.of(
                TITLE,
                BLANK,
                String.format(GENERATED_NOTE, image, ZonedDateTime.now(ZoneOffset.UTC).format(STAMP)),
                BLANK,
                entries.size() + COUNT_NOTE,
                BLANK,
                FIXER_NOTE,
                BLANK,
                PROFILE_NOTE,
                BLANK));
        Map<String, Integer> counts = CatalogBuilder.profileCounts(entries);
        if (!counts.isEmpty()) {
            lines.add(PROFILE_TABLE_HEADING);
            lines.add(PROFILE_TABLE_RULE);
            for (Map.Entry<String, Integer> count : counts.entrySet()) {
                lines.add(String.format(PROFILE_TABLE_ROW, count.getKey(), count.getValue()));
            }
            lines.add(BLANK);
        }
        for (Map.Entry<String, List<CatalogEntry>> language : byLanguage(entries).entrySet()) {
            lines.add(String.format(LANGUAGE_HEADING, language.getKey(), language.getValue().size()));
            lines.add(BLANK);
            for (CatalogEntry entry : language.getValue()) {
                lines.addAll(describeInMarkdown(entry));
            }
        }
        return String.join(NEWLINE, lines).stripTrailing() + NEWLINE;
    }

    /**
     * Writes both documents.
     *
     * @param entries the finished catalog
     * @param image the image it came from
     * @param imageId that image's identifier
     * @param jsonPath where the JSON goes
     * @param markdownPath where the Markdown goes, {@code null} to skip it
     * @throws IOException when either cannot be written
     */
    public void write(List<CatalogEntry> entries, String image, String imageId, Path jsonPath, Path markdownPath)
            throws IOException {
        write(entries, image, imageId, jsonPath, markdownPath, BYTECODE_SOURCE, List.of());
    }

    /**
     * Writes both documents, recording where the profiles came from.
     *
     * @param entries the finished catalog
     * @param image the image it came from
     * @param imageId that image's identifier
     * @param jsonPath where the catalog goes
     * @param markdownPath where the readable version goes, {@code null} to skip
     * @param profileSource what said which profile runs which rule
     * @param unmatchedChecks checks Sheriff lists that no rule code matched
     * @throws IOException when a document cannot be written
     */
    public void write(
            List<CatalogEntry> entries,
            String image,
            String imageId,
            Path jsonPath,
            Path markdownPath,
            String profileSource,
            List<String> unmatchedChecks) throws IOException {
        Files.writeString(jsonPath, toJson(entries, image, imageId, profileSource, unmatchedChecks));
        if (markdownPath != null) {
            Files.writeString(markdownPath, toMarkdown(entries, image));
        }
    }

    /**
     * One rule as the JSON document carries it.
     *
     * @param entry the catalog entry to describe
     * @return its fields, in the order the document uses
     */
    private static Map<String, Object> describe(CatalogEntry entry) {
        SheriffRule rule = entry.rule();
        Map<String, Object> described = new LinkedHashMap<>();
        described.put(CODE_FIELD, rule.code());
        described.put(REFERENCE_CODE_FIELD, entry.referenceCode());
        described.put(DESCRIPTION_FIELD, rule.description());
        described.put(HOW_TO_SOLVE_FIELD, rule.howToSolve());
        described.put(LANGUAGE_FIELD, rule.language());
        described.put(CATEGORY_FIELD, rule.category());
        described.put(SOURCE_FIELD, entry.source());
        described.put(OWNER_FIELD, entry.owner());
        described.put(FIXER_FIELD, rule.fixer());
        described.put(EXAMPLE_FIELD, rule.example());
        described.put(PROFILES_FIELD, rule.profiles());
        return described;
    }

    /**
     * One rule as the Markdown document carries it: a section of its own, with
     * only the parts the image actually had something to say about.
     *
     * @param entry the catalog entry to describe
     * @return the lines of that section
     */
    private static List<String> describeInMarkdown(CatalogEntry entry) {
        SheriffRule rule = entry.rule();
        List<String> lines = new ArrayList<>(List.of(String.format(RULE_HEADING, rule.code()), BLANK));
        List<String> marks = new ArrayList<>();
        if (!rule.category().isEmpty()) {
            marks.add(rule.category());
        }
        if (entry.referenceCode().isEmpty()) {
            marks.add(CatalogEntry.CHECKER_SOURCE);
        }
        if (!marks.isEmpty()) {
            lines.add(String.format(MARKS, String.join(MARK_SEPARATOR, marks)));
            lines.add(BLANK);
        }
        lines.add(rule.description().isEmpty() ? NO_MESSAGE : rule.description());
        lines.add(BLANK);
        if (!rule.howToSolve().isEmpty()) {
            lines.add(String.format(HOW_TO_SOLVE_LABEL, rule.howToSolve()));
            lines.add(BLANK);
        }
        if (!rule.profiles().isEmpty()) {
            List<String> quoted = new ArrayList<>();
            for (String profile : rule.profiles()) {
                quoted.add(String.format(QUOTED_PROFILE, profile));
            }
            lines.add(String.format(PROFILES_LABEL, String.join(PROFILE_SEPARATOR, quoted)));
            lines.add(BLANK);
        }
        if (rule.hasFixer()) {
            lines.add(String.format(FIXER_LABEL, rule.fixer()));
            lines.add(BLANK);
        }
        if (rule.hasExample()) {
            lines.add(EXAMPLE_LABEL);
            lines.add(BLANK);
            lines.add(FENCE_OPEN);
            rule.example().lines().forEach(lines::add);
            lines.add(FENCE);
            lines.add(BLANK);
        }
        return lines;
    }

    /**
     * The catalog grouped by language and sorted by code inside each group,
     * which is the order the Markdown document is written in.
     *
     * @param entries the finished catalog
     * @return those groups, languages in alphabetical order
     */
    private static Map<String, List<CatalogEntry>> byLanguage(List<CatalogEntry> entries) {
        Map<String, List<CatalogEntry>> grouped = new TreeMap<>();
        for (CatalogEntry entry : entries) {
            String language = entry.rule().language().isEmpty() ? GENERAL : entry.rule().language();
            grouped.computeIfAbsent(language, key -> new ArrayList<>()).add(entry);
        }
        for (List<CatalogEntry> rules : grouped.values()) {
            rules.sort((left, right) -> left.code().compareTo(right.code()));
        }
        return grouped;
    }
}
