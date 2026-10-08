package com.kaizten.sheriff.infrastructure.extractor;

import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads rules out of a {@code javap -p -constants} dump of the Sheriff image.
 *
 * <p>Two kinds of class carry rule text, and both are needed. A
 * {@code <Code>KaiztenSheriffErrorMessage} class holds the text of a rule
 * Sheriff names with a reference code. A checker holds, inline, the text of a
 * rule Sheriff reports with an <em>empty</em> reference code — and that is not
 * an edge case: every finding this project's own Java fixtures produce is of
 * that kind, so a catalog built from reference codes alone misses exactly the
 * rules already seen firing.
 *
 * <p>Constant names fall into five families across the whole jar, checked
 * rather than assumed: {@code DESCRIPTION*}, {@code ERROR*} and {@code HOW*}
 * are text; {@code FIXER*} and {@code SCRIPT*} are the path of a fixer script.
 * The families are matched on the leading word, never as a substring —
 * "SCRIPT" appears inside "DESCRIPTION", and a substring test silently emptied
 * the description of 135 of 282 rules while still looking like it worked.
 */
public final class ConstantsParser {

    private static final String CLASS_LINE_PATTERN =
            "^(?:public |final |abstract )*(?:class|interface) (\\S+)";
    private static final String CONSTANT_LINE_PATTERN =
            "static final java\\.lang\\.String (\\w+) = \"(.*)\";\\s*$";
    private static final Pattern CLASS_LINE = Pattern.compile(CLASS_LINE_PATTERN);
    private static final Pattern CONSTANT_LINE = Pattern.compile(CONSTANT_LINE_PATTERN);
    private static final String MESSAGE_SUFFIX = "KaiztenSheriffErrorMessage";
    private static final String CHECKER_SUFFIX = "Checker";
    /**
     * The checker packages as they appear inside a class name. Two, because
     * the checkers moved from {@code analysis.checker} to {@code analysis.test}
     * in the image published on 2026-09-16.
     */
    private static final String TEST_MARKER = ".test.";
    private static final String CHECKER_PACKAGE_MARKER = ".checker.";
    private static final String DEFAULT_MARKER = ".";
    private static final List<String> CHECKER_MARKERS = List.of(TEST_MARKER, CHECKER_PACKAGE_MARKER);
    private static final String TYPES_MARKER = ".types.";
    private static final String GENERAL = "general";
    private static final String VARIANT_SEPARATOR = " / ";
    private static final String NAME_SEPARATOR = "_";
    private static final String PACKAGE_SEPARATOR = ".";
    private static final String PACKAGE_SPLIT_PATTERN = "\\.";
    private static final String PATH_SEPARATOR = "/";
    private static final String LINE_SEPARATOR = "\n";
    private static final String EMPTY_TEXT = "";
    private static final int NOT_FOUND = -1;
    private static final String DESCRIPTION_FAMILY = "DESCRIPTION";
    private static final String ERROR_FAMILY = "ERROR";
    private static final String HOW_FAMILY = "HOW";
    private static final String HOW_TO_SOLVE_FAMILY = "HOWTOSOLVE";
    private static final String FIXER_FAMILY = "FIXER";
    private static final String SCRIPT_FAMILY = "SCRIPT";
    private static final Set<String> TEXT_FAMILIES =
            Set.of(DESCRIPTION_FAMILY, ERROR_FAMILY, HOW_FAMILY, HOW_TO_SOLVE_FAMILY);
    private static final Set<String> FIXER_FAMILIES = Set.of(FIXER_FAMILY, SCRIPT_FAMILY);
    private static final String JAVA_LANGUAGE = "java";
    private static final String TYPESCRIPT_LANGUAGE = "typescript";
    private static final String VUEJS_LANGUAGE = "vuejs";
    private static final String PERL_LANGUAGE = "perl";
    private static final String REPOSITORY_LANGUAGE = "repository";
    private static final Set<String> KNOWN_LANGUAGES = Set.of(
            JAVA_LANGUAGE, TYPESCRIPT_LANGUAGE, VUEJS_LANGUAGE, PERL_LANGUAGE, REPOSITORY_LANGUAGE);
    private static final String NOT_INSTANTIABLE = "This is a utility class and cannot be instantiated.";

    /**
     * Refuses instantiation: this class is a namespace for parsing functions,
     * with no state of its own to build.
     */
    private ConstantsParser() {
        throw new UnsupportedOperationException(NOT_INSTANTIABLE);
    }

    /**
     * The rules Sheriff names with a reference code.
     *
     * <p>A rule with several message constants — the same rule phrased for a
     * line comment and for a block comment, say — gets them joined rather than
     * one arbitrarily picked: they are all things that rule can say.
     *
     * @param dump the javap output
     * @return those rules, keyed by reference code
     */
    public static Map<String, CatalogEntry> messageClasses(String dump) {
        Map<String, CatalogEntry> rules = new LinkedHashMap<>();
        for (ParsedClass parsed : classesIn(dump)) {
            String simple = simpleNameOf(parsed.name());
            if (!simple.endsWith(MESSAGE_SUFFIX) || simple.equals(MESSAGE_SUFFIX)) {
                continue;
            }
            String code = simple.substring(0, simple.length() - MESSAGE_SUFFIX.length());
            String[] classified = classify(packageOf(parsed.name()), TYPES_MARKER);
            rules.put(code, new CatalogEntry(
                    new SheriffRule(code, join(parsed.descriptions()), join(parsed.howToSolve()),
                            classified[0], classified[1], EMPTY_TEXT, List.of()),
                    code, CatalogEntry.MESSAGE_CLASS_SOURCE, slashed(parsed.name())));
        }
        return rules;
    }

    /**
     * The rules whose text lives inline in a checker, one per {@code ERROR_*}
     * constant.
     *
     * <p>A checker declares its constants in pairs — each error followed by the
     * remedy that goes with it — so they are paired by order rather than by
     * name: {@code ERROR_JAVADOC_EMPTY}'s partner is
     * {@code HOWTOSOLVE_ADD_DESCRIPTION}, which no name-matching rule would
     * ever find.
     *
     * <p>A class counts as a checker by membership of the checker package, not
     * by its name: plenty of checkers are called {@code JavaMethodName} rather
     * than {@code *Checker}, and a name filter quietly lost their rules.
     *
     * @param dump the javap output
     * @param knownDescriptions text already carried by a message class, so the
     *     same rule is not listed twice
     * @return those rules, in declaration order
     */
    public static List<CatalogEntry> checkerClasses(String dump, Set<String> knownDescriptions) {
        Set<String> known = new LinkedHashSet<>(knownDescriptions);
        List<CatalogEntry> rules = new ArrayList<>();
        for (ParsedClass parsed : classesIn(dump)) {
            if (checkerMarkerIn(parsed.name()).isEmpty()) {
                continue;
            }
            rules.addAll(rulesIn(parsed, known));
        }
        return rules;
    }

    /**
     * The checker package a class sits under, if any.
     *
     * @param className the fully qualified class name
     * @return the marker that matched, empty when the class is not a checker
     */
    static Optional<String> checkerMarkerIn(String className) {
        return CHECKER_MARKERS.stream().filter(className::contains).findFirst();
    }

    /**
     * The rules one checker class declares inline.
     *
     * @param parsed the checker and its constants, in declaration order
     * @param known the text already spoken for, extended with what this class adds
     * @return the rules that class contributes
     */
    private static List<CatalogEntry> rulesIn(ParsedClass parsed, Set<String> known) {
        String simple = simpleNameOf(parsed.name());
        String base = simple.endsWith(CHECKER_SUFFIX)
                ? simple.substring(0, simple.length() - CHECKER_SUFFIX.length())
                : simple;
        String[] classified = classify(packageOf(parsed.name()), checkerMarkerIn(parsed.name()).orElse(DEFAULT_MARKER));
        List<CatalogEntry> rules = new ArrayList<>();
        List<String[]> pending = new ArrayList<>();
        for (String[] constant : parsed.constants()) {
            if (isHowToSolve(constant[0])) {
                if (!pending.isEmpty()) {
                    pending.get(pending.size() - 1)[2] = constant[1];
                }
                continue;
            }
            if (known.contains(constant[1])) {
                continue;
            }
            known.add(constant[1]);
            pending.add(new String[] {constant[0], constant[1], EMPTY_TEXT});
        }
        for (String[] entry : pending) {
            String suffix = entry[0].contains(NAME_SEPARATOR)
                    ? entry[0].substring(entry[0].indexOf(NAME_SEPARATOR) + 1)
                    : entry[0];
            rules.add(new CatalogEntry(
                    new SheriffRule(base + PACKAGE_SEPARATOR + suffix, entry[1], entry[2],
                            classified[0], classified[1], EMPTY_TEXT, List.of()),
                    EMPTY_TEXT, CatalogEntry.CHECKER_SOURCE, slashed(parsed.name())));
        }
        return rules;
    }

    /**
     * The language and category a class's package implies.
     *
     * @param packageName the package, in dotted form
     * @param marker where to start reading, {@code .types.} or {@code .checker.}
     * @return a pair of language and category
     */
    static String[] classify(String packageName, String marker) {
        int index = packageName.indexOf(marker);
        if (index == NOT_FOUND) {
            return new String[] {GENERAL, EMPTY_TEXT};
        }
        String[] parts = packageName.substring(index + marker.length()).split(PACKAGE_SPLIT_PATTERN);
        List<String> segments = new ArrayList<>();
        for (String part : parts) {
            if (!part.isEmpty()) {
                segments.add(part);
            }
        }
        if (segments.isEmpty()) {
            return new String[] {GENERAL, EMPTY_TEXT};
        }
        String head = segments.get(0);
        List<String> rest = segments.subList(1, segments.size());
        if (KNOWN_LANGUAGES.contains(head)) {
            return new String[] {head, String.join(PACKAGE_SEPARATOR, rest)};
        }
        return new String[] {GENERAL, String.join(PACKAGE_SEPARATOR, segments)};
    }

    /**
     * Whether a constant holds the path of a fixer rather than text.
     *
     * @param name the constant's name
     * @return true when it is a fixer path
     */
    static boolean isFixerConstant(String name) {
        return FIXER_FAMILIES.contains(family(name));
    }

    /**
     * Whether a constant holds rule text worth keeping.
     *
     * @param name the constant's name
     * @return true when it belongs to one of the text families
     */
    private static boolean isTextConstant(String name) {
        return TEXT_FAMILIES.contains(family(name));
    }

    /**
     * Whether a constant holds a remedy rather than the error it goes with.
     *
     * @param name the constant's name
     * @return true when it is a remedy
     */
    static boolean isHowToSolve(String name) {
        return name.toUpperCase(Locale.ROOT).startsWith(HOW_FAMILY);
    }

    /**
     * The family a constant belongs to, read from its leading word.
     *
     * @param name the constant's name
     * @return the leading word, upper-cased
     */
    private static String family(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        int separator = upper.indexOf(NAME_SEPARATOR);
        return separator == NOT_FOUND ? upper : upper.substring(0, separator);
    }

    /**
     * Every class in the dump, each carrying the text constants it declares.
     *
     * @param dump the javap output
     * @return the classes, in the order the dump lists them
     */
    private static List<ParsedClass> classesIn(String dump) {
        List<ParsedClass> classes = new ArrayList<>();
        ParsedClass current = null;
        for (String line : dump.split(LINE_SEPARATOR)) {
            Matcher classLine = CLASS_LINE.matcher(line);
            if (classLine.find()) {
                current = new ParsedClass(classLine.group(1));
                classes.add(current);
                continue;
            }
            Matcher constant = CONSTANT_LINE.matcher(line);
            if (current != null && constant.find()) {
                String name = constant.group(1);
                String value = constant.group(2);
                if (!value.isBlank() && isTextConstant(name)) {
                    current.add(name, value);
                }
            }
        }
        return classes;
    }

    /**
     * The distinct values of one half of a class's constants.
     *
     * @param constants the constants, as name and value pairs
     * @param howToSolve true to read the remedies, false to read the errors
     * @return those values, without repetitions, in declaration order
     */
    static List<String> uniqueValues(List<String[]> constants, boolean howToSolve) {
        List<String> values = new ArrayList<>();
        for (String[] constant : constants) {
            if (isHowToSolve(constant[0]) == howToSolve && !values.contains(constant[1])) {
                values.add(constant[1]);
            }
        }
        return values;
    }

    /**
     * The several phrasings of one rule, as a single readable line.
     *
     * @param values the phrasings
     * @return them joined by the variant separator
     */
    private static String join(List<String> values) {
        return String.join(VARIANT_SEPARATOR, values);
    }

    /**
     * The class name without its package.
     *
     * @param fullyQualified the name, in dotted form
     * @return the last segment
     */
    private static String simpleNameOf(String fullyQualified) {
        int separator = fullyQualified.lastIndexOf(PACKAGE_SEPARATOR);
        return separator == NOT_FOUND ? fullyQualified : fullyQualified.substring(separator + 1);
    }

    /**
     * The package a class lives in.
     *
     * @param fullyQualified the name, in dotted form
     * @return everything before the last segment, or the empty string
     */
    private static String packageOf(String fullyQualified) {
        int separator = fullyQualified.lastIndexOf(PACKAGE_SEPARATOR);
        return separator == NOT_FOUND ? EMPTY_TEXT : fullyQualified.substring(0, separator);
    }

    /**
     * The class name as a path, which is how the catalog records where a rule
     * came from.
     *
     * @param fullyQualified the name, in dotted form
     * @return the same name with slashes
     */
    private static String slashed(String fullyQualified) {
        return fullyQualified.replace(PACKAGE_SEPARATOR, PATH_SEPARATOR);
    }
}
