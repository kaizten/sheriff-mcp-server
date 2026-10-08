package com.kaizten.sheriff.domain.valueobject;

import java.util.List;
import java.util.Locale;

/**
 * One of Sheriff's rules, as a catalog entry rather than as something that
 * already went wrong.
 *
 * <p>A {@link SheriffFinding} is "you broke this, here, now"; a rule is "this
 * is a thing Sheriff checks for", available before a line of code is written.
 * {@code description} and {@code howToSolve} are the templates Sheriff fills
 * in when the rule is broken, so they still carry their {@code %s}/{@code %d}
 * placeholders.
 *
 * @param code the reference code, or the name this project gave a rule whose
 *     message lives inside a checker and has no code of its own
 * @param description the message template
 * @param howToSolve the remedy template
 * @param language java, typescript, vuejs, perl, repository or general
 * @param category the finer grouping inside a language, may be empty
 * @param fixer the path of Sheriff's own fixer, empty when it has none
 * @param profiles the {@code --test} profiles that run this rule; empty means
 *     the catalog does not know, never "no profile runs it"
 */
public record SheriffRule(
        String code,
        String description,
        String howToSolve,
        String language,
        String category,
        String fixer,
        List<String> profiles,
        String example) {

    private static final String ERROR_EXAMPLE_NOT_DEFINED = "Example is not defined";
    private static final String DESCRIBE_FORMAT = "%s: %s";
    private static final String ERROR_CODE_NOT_DEFINED = "Code is not defined";
    private static final String ERROR_DESCRIPTION_NOT_DEFINED = "Description is not defined";
    private static final String ERROR_HOW_TO_SOLVE_NOT_DEFINED = "How to solve is not defined";
    private static final String ERROR_LANGUAGE_NOT_DEFINED = "Language is not defined";
    private static final String ERROR_CATEGORY_NOT_DEFINED = "Category is not defined";
    private static final String ERROR_FIXER_NOT_DEFINED = "Fixer is not defined";
    private static final String ERROR_PROFILES_NOT_DEFINED = "Profiles are not defined";
    private static final String HOW_TO_SOLVE_FORMAT = "%s%n    -> %s";
    private static final String FIELD_SEPARATOR = " ";
    private static final String WHITESPACE = "\\s+";

    /**
     * Checks the values it is given instead of quietly correcting them.
     *
     * <p>A value object holds a checked value: turning an absent one into an
     * empty string makes "nothing was reported" and "we lost it on the way
     * here" look identical afterwards, which is how a parsing bug survives a
     * green test run.
     *
     * @throws IllegalArgumentException when a required value is not defined
     */
    public SheriffRule {
        require(code != null, ERROR_CODE_NOT_DEFINED);
        require(description != null, ERROR_DESCRIPTION_NOT_DEFINED);
        require(howToSolve != null, ERROR_HOW_TO_SOLVE_NOT_DEFINED);
        require(language != null, ERROR_LANGUAGE_NOT_DEFINED);
        require(category != null, ERROR_CATEGORY_NOT_DEFINED);
        require(fixer != null, ERROR_FIXER_NOT_DEFINED);
        require(profiles != null, ERROR_PROFILES_NOT_DEFINED);
        require(example != null, ERROR_EXAMPLE_NOT_DEFINED);
        profiles = List.copyOf(profiles);
    }

    /**
     * A rule the catalog has no example for.
     *
     * <p>Most rules are like this: an example exists only where the rule has
     * a fixer that writes code rather than rearranging what is there.
     *
     * @param code the reference code
     * @param description what Sheriff reports when the rule is broken
     * @param howToSolve what it suggests
     * @param language the language it applies to
     * @param category the family it belongs to
     * @param fixer the script that repairs it, empty when there is none
     * @param profiles the test profiles that run it
     */
    public SheriffRule(
            String code,
            String description,
            String howToSolve,
            String language,
            String category,
            String fixer,
            List<String> profiles) {
        this(code, description, howToSolve, language, category, fixer, profiles, "");
    }

    /**
     * Whether the catalog knows what code this rule accepts.
     *
     * @return {@code true} when it carries an example
     */
    public boolean hasExample() {
        return !example.isEmpty();
    }

    /**
     * Refuses a value the domain does not accept.
     *
     * @param valid whether the value passed its check
     * @param message what to report when it did not
     * @throws IllegalArgumentException when {@code valid} is false
     */
    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Whether Sheriff can repair this one itself.
     *
     * @return {@code true} when the rule has a built-in fixer
     */
    public boolean hasFixer() {
        return !fixer.isEmpty();
    }

    /**
     * Whether a given profile runs this rule.
     *
     * @param profile the profile name, for example {@code JAVA}
     * @return {@code true} when the catalog records it for that profile
     */
    public boolean runsIn(String profile) {
        return profiles.contains(profile);
    }

    /**
     * Search over everything a reader would search by, word by word.
     *
     * <p>Every word of the query has to appear somewhere in the rule, and
     * not in any particular order. Matching the query as one phrase is what
     * this replaces, and it failed silently: "sorted imports" found nothing
     * while {@code SortedImport} sat in the catalog, and "javadoc method"
     * found nothing among the seven rules about JavaDoc on methods. An empty
     * answer reads as "the catalog has nothing about this" when what actually
     * happened is that the words were in a different order from the text.
     *
     * @param query what to look for, case-insensitively
     * @return {@code true} when every word of it appears somewhere searchable
     */
    public boolean matches(String query) {
        String haystack = String.join(FIELD_SEPARATOR, code, description, howToSolve, category, language)
                .toLowerCase(Locale.ROOT);
        String trimmed = query.toLowerCase(Locale.ROOT).strip();
        if (trimmed.isEmpty()) {
            return false;
        }
        for (String term : trimmed.split(WHITESPACE)) {
            if (!haystack.contains(term)) {
                return false;
            }
        }
        return true;
    }

    /**
     * One entry for a prompt or the console.
     *
     * @return the code, its message template, and the remedy when there is one
     */
    public String describe() {
        String line = description.isEmpty() ? code : String.format(DESCRIBE_FORMAT, code, description);
        if (howToSolve.isEmpty()) {
            return line;
        }
        return String.format(HOW_TO_SOLVE_FORMAT, line, howToSolve);
    }

}
