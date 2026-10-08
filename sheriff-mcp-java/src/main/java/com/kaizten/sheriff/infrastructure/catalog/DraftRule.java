package com.kaizten.sheriff.infrastructure.catalog;

import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import java.util.ArrayList;
import java.util.List;

/**
 * A rule being read line by line, before it has all its parts.
 *
 * <p>Its own type rather than a mutable {@link SheriffRule}, because the value
 * object is immutable on purpose and a half-built rule is not a rule. Its own
 * file rather than a nested class, because Sheriff's hexagonal profile rejects
 * nested types — the same rule that moved the fix loop's run state out into
 * the open, and the same verdict: if a concept is worth a class, it is worth a
 * file.
 */
final class DraftRule {

    private static final String EMPTY = "";
    private static final String SPACE = " ";
    private static final String NEWLINE = "\n";
    private static final String BACKTICK = "`";
    private static final String CHECKER_MARK = "checker";
    private static final String HOW_PREFIX = "how";
    private static final String PROFILE_PREFIX = "profile";
    private static final String FIXER_PREFIX = "fixer";
    private static final String MARK_SEPARATOR = "[·|,]";
    private static final String PROFILE_SEPARATOR = ",";

    final String code;
    final String language;
    final List<String> description = new ArrayList<>();
    final List<String> example = new ArrayList<>();
    String howToSolve = EMPTY;
    String category = EMPTY;
    String fixer = EMPTY;
    List<String> profiles = new ArrayList<>();

    /**
     * Starts a draft with the two parts a heading already gives away.
     *
     * @param code the reference code, or the name given to a checker-owned rule
     * @param language the language the rule belongs to
     */
    DraftRule(String code, String language) {
        this.code = code;
        this.language = language;
    }

    /**
     * Whether the italic line under a heading should still be read as a category.
     *
     * @return true while nothing else has been read
     */
    boolean acceptsCategory() {
        return category.isEmpty() && description.isEmpty();
    }

    /**
     * Records the category, ignoring the marker that says a rule has no reference code.
     *
     * @param marks the italic line, possibly several marks
     */
    void category(String marks) {
        for (String mark : marks.split(MARK_SEPARATOR)) {
            if (!mark.strip().equals(CHECKER_MARK) && category.isEmpty()) {
                category = mark.strip();
            }
        }
    }

    /**
     * Adds a line to the description.
     *
     * @param line one line of prose
     */
    void describe(String line) {
        description.add(line);
    }

    /**
     * Adds a line to the code Sheriff accepts for this rule.
     *
     * <p>Kept exactly as written, indentation included: it is code, and the
     * whole point of carrying it is that it can be copied.
     *
     * @param line one line of the fenced block
     */
    void exampleLine(String line) {
        example.add(line);
    }

    /**
     * Records one of the bold fields under a heading.
     *
     * @param name the field name, lowercased
     * @param value what followed it
     */
    void field(String name, String value) {
        if (name.startsWith(HOW_PREFIX)) {
            howToSolve = value;
        } else if (name.startsWith(PROFILE_PREFIX)) {
            profiles = new ArrayList<>();
            for (String profile : value.split(PROFILE_SEPARATOR)) {
                String cleaned = profile.strip().replace(BACKTICK, EMPTY);
                if (!cleaned.isEmpty()) {
                    profiles.add(cleaned);
                }
            }
        } else if (name.startsWith(FIXER_PREFIX)) {
            fixer = value.replace(BACKTICK, EMPTY);
        }
    }

    /**
     * Freezes what has been read into a value object.
     *
     * @return the finished rule
     */
    SheriffRule toRule() {
        return new SheriffRule(
                code, String.join(SPACE, description).strip(), howToSolve, language, category, fixer, profiles,
                String.join(NEWLINE, example).stripTrailing());
    }
}
