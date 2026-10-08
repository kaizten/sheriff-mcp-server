package com.kaizten.sheriff.infrastructure.catalog;

import com.kaizten.sheriff.domain.port.RuleCatalog;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a rule list written as Markdown — the shape a rule list arrives in
 * when a person maintains it, rather than a generator.
 *
 * <pre>
 * ## java (51 rules)
 * ### `JavaDocCommentsInMethod`
 * *format*
 * JavaDoc comment found in method '%s'.
 * **How to solve:** Remove the JavaDoc comment...
 * **Profiles:** `JAVA`, `JAVA_HEXAGONAL`
 * </pre>
 *
 * <p>Only the {@code ###} heading is required; a document with nothing but
 * headings and sentences still yields usable rules. Anything unrecognized is
 * ignored rather than rejected, because a rule list is documentation first and
 * refusing to read one over a stray line would be worse than reading it
 * approximately.
 */
public final class MarkdownRuleCatalog implements RuleCatalog {

    private static final String LANGUAGE_HEADING_REGEX = "^##\\s+([A-Za-z][\\w+#-]*)";
    private static final String RULE_HEADING_REGEX = "^###\\s+`?([^`\\n]+?)`?\\s*$";
    private static final String CATEGORY_REGEX = "^\\*([^*]+)\\*\\s*$";
    private static final String FIELD_REGEX = "^\\*\\*(.+?):?\\*\\*:?\\s*(.*)$";
    private static final String LINE_SEPARATOR = "\n";
    private static final Pattern LANGUAGE_HEADING = Pattern.compile(LANGUAGE_HEADING_REGEX);
    private static final Pattern RULE_HEADING = Pattern.compile(RULE_HEADING_REGEX);
    private static final Pattern CATEGORY = Pattern.compile(CATEGORY_REGEX);
    private static final Pattern FIELD = Pattern.compile(FIELD_REGEX);
    private static final String PLACEHOLDER_PREFIX = "_";
    private static final String FENCE = "```";
    private static final String EMPTY = "";

    private final Path path;

    /**
     * Wires the catalog to a file.
     *
     * @param path the Markdown rule list to read
     */
    public MarkdownRuleCatalog(Path path) {
        this.path = path;
    }

    /**
     * Every rule the Markdown file describes.
     *
     * <p>A fenced block is taken verbatim and never parsed as headings or
     * fields: it holds code, which is exactly the shape that would otherwise
     * be misread.
     *
     * <p>An unreadable file yields no rules rather than an error: the catalog is
     * documentation, and a missing one should degrade the prompt, not stop the
     * run.
     *
     * @return the rules in document order, empty when the file cannot be read
     */
    @Override
    public List<SheriffRule> allRules() {
        try {
            return parse(Files.readString(path));
        } catch (IOException exception) {
            return List.of();
        }
    }

    /**
     * Turns a Markdown rule list into rules.
     *
     * @param text the whole document
     * @return every rule it describes, in document order
     */
    List<SheriffRule> parse(String text) {
        List<SheriffRule> rules = new ArrayList<>();
        DraftRule draft = null;
        String language = EMPTY;
        boolean insideExample = false;
        for (String raw : text.split(LINE_SEPARATOR)) {
            String line = raw.strip();
            if (line.startsWith(FENCE)) {
                insideExample = !insideExample;
                continue;
            }
            if (insideExample) {
                if (draft != null) {
                    draft.exampleLine(raw);
                }
                continue;
            }
            Matcher heading = RULE_HEADING.matcher(line);
            if (heading.matches()) {
                rules = flush(rules, draft);
                draft = new DraftRule(heading.group(1).strip(), language);
                continue;
            }
            Matcher languageHeading = LANGUAGE_HEADING.matcher(line);
            if (languageHeading.find()) {
                rules = flush(rules, draft);
                draft = null;
                language = languageHeading.group(1).toLowerCase(Locale.ROOT);
                continue;
            }
            if (draft == null || line.isEmpty()) {
                continue;
            }
            absorb(draft, line);
        }
        return flush(rules, draft);
    }

    /**
     * Folds one body line into the rule being read.
     *
     * <p>A line that matches nothing recognizable becomes description text,
     * which is what keeps a hand-written list readable rather than rejected.
     *
     * @param draft the rule currently being built
     * @param line one stripped, non-empty line of its body
     */
    private static void absorb(DraftRule draft, String line) {
        Matcher field = FIELD.matcher(line);
        if (field.matches()) {
            draft.field(field.group(1).strip().toLowerCase(Locale.ROOT), field.group(2).strip());
            return;
        }
        Matcher category = CATEGORY.matcher(line);
        if (category.matches() && draft.acceptsCategory()) {
            draft.category(category.group(1));
            return;
        }
        if (!line.startsWith(PLACEHOLDER_PREFIX)) {
            draft.describe(line);
        }
    }

    /**
     * Closes the rule being read, if there is one, and keeps it.
     *
     * <p>Called wherever a heading ends the previous rule, so the caller does
     * not have to know whether one was open.
     *
     * @param rules the rules collected so far
     * @param draft the rule being built, {@code null} when none is open
     * @return the same list, with the finished rule appended when there was one
     */
    private static List<SheriffRule> flush(List<SheriffRule> rules, DraftRule draft) {
        if (draft != null) {
            rules.add(draft.toRule());
        }
        return rules;
    }
}
