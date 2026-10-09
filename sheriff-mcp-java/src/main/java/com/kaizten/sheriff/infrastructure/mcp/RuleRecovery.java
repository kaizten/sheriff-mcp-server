package com.kaizten.sheriff.infrastructure.mcp;

import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recovers the rule behind a finding that arrived with no {@code
 * referenceCode} of its own, by matching its rendered message back to the
 * rule's own message template.
 *
 * <p>Every rule's {@code description} in the extracted catalog is the
 * template Sheriff fills in -- {@code "Method '%s' in file '%s' does not
 * have JavaDoc comment"} -- so a rendered message can be matched back to the
 * template that produced it. That is an exact operation on a string Sheriff
 * itself formatted, not a guess: the {@code %s}/{@code %d}/{@code %f}/{@code
 * %n} are the holes. The port of {@code Catalog.recover} in the Python
 * server this one replaced, which is in this repository's git history.
 */
public final class RuleRecovery {

    private static final String PLACEHOLDER_PATTERN = "%[sdfn]";
    private static final Pattern PLACEHOLDER = Pattern.compile(PLACEHOLDER_PATTERN);
    private static final String NEWLINE_PLACEHOLDER = "%n";
    private static final String INTEGER_PLACEHOLDER = "%d";
    private static final String FLOAT_PLACEHOLDER = "%f";
    private static final String NEWLINE_PATTERN = "\\r?\\n";
    private static final String NUMBER_PATTERN = "-?\\d+";
    private static final String ANYTHING_PATTERN = ".*?";
    private static final String EMPTY = "";

    private final List<CompiledTemplate> templates;

    /**
     * Compiles every rule's description into a matcher, once, so recovering
     * a rule from a message does not re-derive every pattern on every call.
     *
     * @param rules the whole catalog
     */
    public RuleRecovery(List<SheriffRule> rules) {
        List<CompiledTemplate> compiled = new ArrayList<>();
        for (SheriffRule rule : rules) {
            Pattern pattern = templatePattern(rule.description());
            if (pattern != null) {
                compiled.add(new CompiledTemplate(pattern, rule));
            }
        }
        this.templates = List.copyOf(compiled);
    }

    /**
     * The rule whose message template produced this description, when
     * exactly one does.
     *
     * <p>The longest matching description wins, so a specific message is
     * not claimed by a vaguer template that also happens to match it.
     *
     * @param description the finding's rendered message
     * @return that rule, empty when nothing matches
     */
    public Optional<SheriffRule> recover(String description) {
        String text = description == null ? EMPTY : description.strip();
        if (text.isEmpty()) {
            return Optional.empty();
        }
        SheriffRule best = null;
        int bestLength = -1;
        for (CompiledTemplate template : templates) {
            Matcher matcher = template.pattern().matcher(text);
            if (matcher.matches() && template.rule().description().length() > bestLength) {
                best = template.rule();
                bestLength = template.rule().description().length();
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * A regular expression for one of Sheriff's message templates.
     *
     * @param template the rule's description, still carrying its placeholders
     * @return that pattern, or {@code null} for a template with no
     *     placeholders and nothing to match on, and for one that is nothing
     *     but a placeholder -- {@code %s} alone would match every message
     *     there is
     */
    private static Pattern templatePattern(String template) {
        String text = template == null ? EMPTY : template.strip();
        if (text.isEmpty() || PLACEHOLDER.matcher(text).matches()) {
            return null;
        }
        StringBuilder regex = new StringBuilder();
        Matcher placeholders = PLACEHOLDER.matcher(text);
        int last = 0;
        while (placeholders.find()) {
            regex.append(Pattern.quote(text.substring(last, placeholders.start())));
            regex.append(placeholderPattern(placeholders.group()));
            last = placeholders.end();
        }
        regex.append(Pattern.quote(text.substring(last)));
        return Pattern.compile(regex.toString(), Pattern.DOTALL);
    }

    /**
     * The regular expression fragment one placeholder expands to.
     *
     * @param placeholder the placeholder, such as {@code %s} or {@code %d}
     * @return the fragment to splice into the template's pattern
     */
    private static String placeholderPattern(String placeholder) {
        if (NEWLINE_PLACEHOLDER.equals(placeholder)) {
            return NEWLINE_PATTERN;
        }
        if (INTEGER_PLACEHOLDER.equals(placeholder) || FLOAT_PLACEHOLDER.equals(placeholder)) {
            return NUMBER_PATTERN;
        }
        return ANYTHING_PATTERN;
    }
}
