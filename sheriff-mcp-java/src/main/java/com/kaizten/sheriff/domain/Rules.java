package com.kaizten.sheriff.domain;

import com.kaizten.sheriff.domain.valueobject.RuleSelection;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Which of Sheriff's rules apply, and how to say so in a prompt.
 *
 * <p>Sheriff's rules are not a file in the analyzed repository — they are built
 * into the image, selected by the {@code --test} profile. The extractor pulls
 * them out into a catalog and a {@code RuleCatalog} adapter loads it; this
 * class is the pure part that decides what is relevant and renders it.
 *
 * <p>Everything here takes the profile and the rules as arguments rather than
 * reading configuration: this is domain code, and the domain does not get to
 * know where configuration comes from.
 */
public final class Rules {

    /**
     * How many of a profile's rules to put in one prompt. A profile's own
     * rules are worth a few thousand characters in every fixer prompt; a
     * language's entire rule set is not. Only bites on the broadest profiles
     * ({@code JAVA} runs about fifty rules, {@code JAVA_HEXAGONAL} over two
     * hundred), where a truncated list plus a pointer beats a wall of text.
     */
    public static final int DEFAULT_MAX_RULES_IN_PROMPT = 80;

    /**
     * What a source file of each language is called at the end.
     *
     * <p>The same facts the edit gate keys on, kept here because deciding
     * what belongs to a language is policy rather than I/O.
     */
    private static final String JAVA_LANGUAGE = "java";
    private static final String TYPESCRIPT_LANGUAGE = "typescript";
    private static final String VUE_LANGUAGE = "vuejs";
    private static final String PERL_LANGUAGE = "perl";
    private static final String PYTHON_LANGUAGE = "python";
    private static final String JAVA_SUFFIX = ".java";
    private static final String TYPESCRIPT_SUFFIX = ".ts";
    private static final String TYPESCRIPT_JSX_SUFFIX = ".tsx";
    private static final String VUE_SUFFIX = ".vue";
    private static final String JAVASCRIPT_SUFFIX = ".js";
    private static final String PERL_SCRIPT_SUFFIX = ".pl";
    private static final String PERL_MODULE_SUFFIX = ".pm";
    private static final String PYTHON_SUFFIX = ".py";
    private static final List<String> JAVA_SUFFIXES = List.of(JAVA_SUFFIX);
    private static final List<String> TYPESCRIPT_SUFFIXES = List.of(TYPESCRIPT_SUFFIX, TYPESCRIPT_JSX_SUFFIX);
    private static final List<String> VUE_SUFFIXES = List.of(VUE_SUFFIX, TYPESCRIPT_SUFFIX, JAVASCRIPT_SUFFIX);
    private static final List<String> PERL_SUFFIXES = List.of(PERL_SCRIPT_SUFFIX, PERL_MODULE_SUFFIX);
    private static final List<String> PYTHON_SUFFIXES = List.of(PYTHON_SUFFIX);
    private static final Map<String, List<String>> SOURCE_SUFFIXES = Map.of(
            JAVA_LANGUAGE, JAVA_SUFFIXES,
            TYPESCRIPT_LANGUAGE, TYPESCRIPT_SUFFIXES,
            VUE_LANGUAGE, VUE_SUFFIXES,
            PERL_LANGUAGE, PERL_SUFFIXES,
            PYTHON_LANGUAGE, PYTHON_SUFFIXES);

    /**
     * How many accepted-code examples one prompt carries.
     *
     * <p>Each runs to about 700 characters, so a dozen is already several
     * thousand. The errors are what the prompt is about; this is support for
     * them.
     */
    public static final int DEFAULT_MAX_EXAMPLES_IN_PROMPT = 12;

    private static final String ACCEPTED_CODE_HEADER =
            "For some of the errors above, Sheriff ships the script that repairs them, "
            + "and that script says exactly what it would write. These are those "
            + "generators, verbatim -- Python that builds Java, so read the f-strings as "
            + "the shape of the result. Where one applies, match it rather than inventing "
            + "your own phrasing of the same idea:";
    private static final int NO_CAP = 0;
    private static final String CODE_SUFFIX = ":";
    private static final String OMITTED_FORMAT =
            "(and %d more: %s. Ask for one with `agent --fixer <code>`.)";
    private static final String OMITTED_SEPARATOR = ", ";
    private static final String CODE_BLOCK_SEPARATOR = "\n";
    private static final String NOTHING = "";

    private static final String BASE_CONTEXT =
            "Sheriff is analyzing this code with the '%s' profile. Its rules are built "
                    + "into the tool itself, not into a file in this repository.";
    private static final String NO_CATALOG =
            " The only source of truth for what to fix is the 'description' and "
                    + "'howToSolve' of each error in the list below. Don't apply or invent any "
                    + "additional style rules that don't come from there.";
    private static final String EXACT_HEADER =
            " These are the %d rules that profile runs, extracted from the image itself:";
    private static final String SUPERSET_HEADER =
            " The catalog doesn't record which rules '%s' runs specifically, so the %d "
                    + "rules below are every rule Sheriff has for %s -- a superset. Some of "
                    + "them may not be in force here:";
    private static final String TRUNCATION_NOTE =
            "%n(and %d more -- see SHERIFF_RULES.md in the agent's directory for the full list)";
    private static final String NOT_A_TODO_LIST =
            "%nUse that list to avoid introducing NEW problems while you work. It is not a "
                    + "to-do list: fix only the errors reported below, and don't restructure "
                    + "anything to satisfy a rule that isn't among them.";
    private static final String PROFILE_SEPARATOR = "_";
    private static final String PROFILE_LIST_SEPARATOR = ",";
    private static final String JAVA_PROFILE = "JAVA";
    private static final String TYPESCRIPT_PROFILE = "TYPESCRIPT";
    private static final String VUE_PROFILE = "VUEJS";
    private static final String PERL_PROFILE = "PERL_FORMAT";
    private static final String PYTHON_PROFILE = "PYTHON";
    private static final Map<String, String> BASE_PROFILES = Map.of(
            JAVA_LANGUAGE, JAVA_PROFILE,
            TYPESCRIPT_LANGUAGE, TYPESCRIPT_PROFILE,
            VUE_LANGUAGE, VUE_PROFILE,
            PERL_LANGUAGE, PERL_PROFILE,
            PYTHON_LANGUAGE, PYTHON_PROFILE);
    private static final String RULE_BULLET = "- ";
    private static final String NEWLINE = "\n";
    private static final String EMPTY = "";
    private static final String UTILITY_CLASS_MESSAGE = "This is a utility class and cannot be instantiated.";
    private static final int NOT_FOUND = -1;
    private static final int FIRST = 0;
    private static final int NO_LIMIT = 0;

    /**
     * Prevents instantiation of this utility class.
     */
    private Rules() {
        throw new UnsupportedOperationException(UTILITY_CLASS_MESSAGE);
    }

    /**
     * The file extensions a language's sources carry.
     *
     * <p>Needed to tell "this component passed" from "this component has
     * nothing of that language in it", which Sheriff reports identically:
     * zero errors either way. That is a silent pass in a gate, and the
     * component being misnamed is the likelier of the two.
     *
     * @param language the language, as {@link #languageForProfile} returns it
     * @return the extensions, or an empty list for a language nobody mapped
     */
    public static List<String> sourceSuffixesFor(String language) {
        return SOURCE_SUFFIXES.getOrDefault(language.toLowerCase(Locale.ROOT), List.of());
    }

    /**
     * The language a profile belongs to. Profile names are
     * {@code <LANGUAGE>_<variant>}, so {@code JAVA_HEXAGONAL} is java. For a
     * list, the language of the first, which is its base profile.
     *
     * @param testType the profile name, or a comma-separated list of them
     * @return its language, lowercased
     */
    public static String languageForProfile(String testType) {
        String first = profilesIn(testType).get(FIRST);
        int separator = first.indexOf(PROFILE_SEPARATOR);
        String language = separator == NOT_FOUND ? first : first.substring(0, separator);
        return language.toLowerCase(Locale.ROOT);
    }

    /**
     * The profiles a comma-separated list names, in order and once each.
     *
     * <p>Sheriff takes one profile per run, so a list is how a project asks
     * for more than one: every caller that runs Sheriff runs it once per
     * profile here.
     *
     * @param testType one profile, or several separated by commas
     * @return those profiles; the text as it is when it names none, so that
     *     Sheriff refuses it rather than nothing being run
     */
    public static List<String> profilesIn(String testType) {
        List<String> profiles = Arrays.stream(testType.split(PROFILE_LIST_SEPARATOR))
                .map(String::strip).filter(profile -> !profile.isEmpty()).distinct().toList();
        return profiles.isEmpty() ? List.of(testType) : profiles;
    }

    /**
     * The profiles to run for the ones asked for: each with the base profile
     * of its language before it.
     *
     * <p>An architecture profile does not include its language's base rules.
     * Measured on the image of 21 September: a file with five style faults
     * draws five errors under {@code JAVA} wherever it is, and none under
     * {@code JAVA_HEXAGONAL} in {@code infrastructure/} or outside the
     * layers. So a project that declared its architecture would silently lose
     * its JavaDoc, braces and comment checks there. Declaring an architecture
     * adds to the base profile; it never replaces it.
     *
     * @param testType one profile, or several separated by commas
     * @return the list to run, comma-separated, base profiles first
     */
    public static String withBaseProfiles(String testType) {
        List<String> profiles = new ArrayList<>();
        for (String profile : profilesIn(testType)) {
            String base = BASE_PROFILES.get(languageForProfile(profile));
            if (base != null && !profiles.contains(base)) {
                profiles.add(base);
            }
            if (!profiles.contains(profile)) {
                profiles.add(profile);
            }
        }
        return String.join(PROFILE_LIST_SEPARATOR, profiles);
    }

    /**
     * The rules in force for a profile, and whether that is exact.
     *
     * <p>Each profile of a list is resolved by itself: the rules the catalog
     * records against it, or, when it records none, every rule of that
     * profile's language. The selection is exact only when every profile was
     * found. It used to be exact as soon as one was, and a profile asked for
     * with its base added ({@code JAVA,JAVA_HEXAGONAL_REST}) got the base's
     * rules alone, presented as the rules in force, since Sheriff's export
     * covers 12 of its 36 profiles.
     *
     * @param rules the whole catalog
     * @param testType the profile in use, or several separated by commas
     * @return the selection, in catalog order, with its exactness flag
     */
    public static RuleSelection selectRulesForProfile(List<SheriffRule> rules, String testType) {
        List<SheriffRule> chosen = new ArrayList<>();
        boolean exact = true;
        for (String profile : profilesIn(testType)) {
            List<SheriffRule> recorded = rules.stream().filter(rule -> rule.runsIn(profile)).toList();
            if (recorded.isEmpty()) {
                exact = false;
                String language = languageForProfile(profile);
                recorded = rules.stream().filter(rule -> language.equals(rule.language())).toList();
            }
            chosen.addAll(recorded);
        }
        return new RuleSelection(rules.stream().filter(chosen::contains).toList(), exact);
    }

    /**
     * The single rule with this code, matched case-insensitively.
     *
     * <p>Sheriff writes a code as {@code JavaValueObjectShouldKeepHashCodeUnchanged},
     * but people type it out of a terminal, so the match is forgiving about
     * case and surrounding whitespace and about nothing else. Searching is
     * what the other method is for.
     *
     * @param rules the whole catalog
     * @param code the code to look up
     * @return that rule, or empty when the catalog has no such code
     */
    public static Optional<SheriffRule> findRule(List<SheriffRule> rules, String code) {
        if (code == null) {
            return Optional.empty();
        }
        String wanted = code.strip().toLowerCase(Locale.ROOT);
        return rules.stream().filter(rule -> rule.code().toLowerCase(Locale.ROOT).equals(wanted)).findFirst();
    }

    /**
     * Substring search across the catalog.
     *
     * @param rules the whole catalog
     * @param query what to look for, empty for everything
     * @return the matching rules, in catalog order
     */
    public static List<SheriffRule> searchRules(List<SheriffRule> rules, String query) {
        if (query == null || query.isEmpty()) {
            return List.copyOf(rules);
        }
        return rules.stream().filter(rule -> rule.matches(query)).toList();
    }

    /**
     * What Sheriff would write for the errors in this pass, when it knows.
     *
     * <p>The rules blurb says what every rule forbids; this says what one
     * rule accepts, and only for the handful actually being fixed. Kept to
     * the batch on purpose — the catalog has 40 of these and a prompt
     * carrying all of them would drown the errors it is meant to be about.
     *
     * <p>Capped for the same reason the rules listing is. Measured against a
     * real analysis: a value object missing its tests draws 11 findings at
     * once, and all 11 carry an example, which came to 7.6 KB in a single
     * prompt. Past the cap the codes are named instead, so nothing is
     * hidden — {@code --fixer <code>} prints any of them in full.
     *
     * @param findings the errors this pass will fix
     * @param rules the whole catalog, empty when there is none
     * @return that text, empty when no error in the pass has an example
     */
    public static String acceptedCodeContext(List<SheriffFinding> findings, List<SheriffRule> rules) {
        return acceptedCodeContext(findings, rules, DEFAULT_MAX_EXAMPLES_IN_PROMPT);
    }

    /**
     * The same, with an explicit cap on how many examples it carries.
     *
     * @param findings the errors this pass will fix
     * @param rules the whole catalog, empty when there is none
     * @param maxExamples how many to show, zero or less for all of them
     * @return that text, empty when no error in the pass has an example
     */
    public static String acceptedCodeContext(
            List<SheriffFinding> findings, List<SheriffRule> rules, int maxExamples) {
        if (findings.isEmpty() || rules.isEmpty()) {
            return NOTHING;
        }
        Map<String, SheriffRule> byCode = new LinkedHashMap<>();
        for (SheriffRule rule : rules) {
            if (rule.hasExample()) {
                byCode.put(rule.code(), rule);
            }
        }
        List<SheriffRule> wanted = new ArrayList<>();
        for (SheriffFinding finding : findings) {
            SheriffRule rule = byCode.get(finding.referenceCode());
            if (rule != null && !wanted.contains(rule)) {
                wanted.add(rule);
            }
        }
        if (wanted.isEmpty()) {
            return NOTHING;
        }
        List<SheriffRule> shown = maxExamples > NO_CAP && wanted.size() > maxExamples
                ? wanted.subList(0, maxExamples)
                : wanted;
        List<String> parts = new ArrayList<>(List.of(ACCEPTED_CODE_HEADER, NOTHING));
        for (SheriffRule rule : shown) {
            parts.add(rule.code() + CODE_SUFFIX);
            parts.add(rule.example());
            parts.add(NOTHING);
        }
        if (shown.size() < wanted.size()) {
            List<String> omitted = wanted.subList(shown.size(), wanted.size()).stream()
                    .map(SheriffRule::code)
                    .toList();
            parts.add(String.format(OMITTED_FORMAT, wanted.size() - shown.size(),
                    String.join(OMITTED_SEPARATOR, omitted)));
        }
        return String.join(CODE_BLOCK_SEPARATOR, parts).stripTrailing();
    }

    /**
     * The rules blurb that goes into every fixer prompt.
     *
     * <p>With no catalog this is the original text: the errors below are the
     * only source of truth. With one, the prompt can finally state the rules up
     * front, which is what makes it possible to fix one error without breaking
     * a different rule nobody mentioned.
     *
     * @param testType the profile in use
     * @param rules the whole catalog, empty when there is none
     * @param maxRules how many rules to list, zero or less for all of them
     * @return the text to put in the prompt
     */
    public static String sheriffRulesContext(String testType, List<SheriffRule> rules, int maxRules) {
        String context = String.format(BASE_CONTEXT, testType);
        if (rules.isEmpty()) {
            return context + NO_CATALOG;
        }
        RuleSelection selection = selectRulesForProfile(rules, testType);
        if (selection.isEmpty()) {
            return context + NO_CATALOG;
        }
        List<SheriffRule> selected = selection.rules();
        List<SheriffRule> shown = maxRules > NO_LIMIT && selected.size() > maxRules
                ? selected.subList(0, maxRules)
                : selected;
        List<String> parts = new ArrayList<>();
        parts.add(context);
        parts.add(header(selection, testType));
        parts.add(EMPTY);
        parts.add(listing(shown));
        if (shown.size() < selected.size()) {
            parts.add(String.format(TRUNCATION_NOTE, selected.size() - shown.size()));
        }
        parts.add(String.format(NOT_A_TODO_LIST));
        return String.join(CODE_BLOCK_SEPARATOR, parts);
    }

    /**
     * The line introducing the rule listing, which says whether the selection
     * is the profile's own rules or a language-wide superset.
     *
     * @param selection the rules in force and how they were arrived at
     * @param testType the profile in use
     * @return the header line
     */
    private static String header(RuleSelection selection, String testType) {
        if (selection.exact()) {
            return String.format(EXACT_HEADER, selection.rules().size());
        }
        return String.format(SUPERSET_HEADER, testType, selection.rules().size(), languageForProfile(testType));
    }

    /**
     * The rules themselves, one bullet per rule.
     *
     * @param rules the rules to render
     * @return the bulleted listing, empty when there are no rules
     */
    private static String listing(List<SheriffRule> rules) {
        return rules.stream()
                .map(rule -> RULE_BULLET + rule.describe())
                .reduce((a, b) -> a + NEWLINE + b)
                .orElse(EMPTY);
    }
}
