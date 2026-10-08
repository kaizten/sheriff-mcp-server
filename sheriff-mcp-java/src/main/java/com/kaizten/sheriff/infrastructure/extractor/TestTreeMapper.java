package com.kaizten.sheriff.infrastructure.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads which rules a profile runs out of Sheriff's own test tree.
 *
 * <p>This replaces an approximation. {@link ProfileMapper} deduces the same
 * answer from the factory's bytecode and the class reference graph, which is
 * as close as static references can get; Sheriff gained a {@code tree}
 * subcommand in September 2026 that simply states it. The bytecode reading
 * stays as the fallback for an older image.
 *
 * <p>The two sources do not name a rule the same way, and cannot: the tree
 * enumerates <em>checks</em> and the message classes enumerate
 * <em>messages</em>, and one check emits several messages. Matching is by
 * name, forgiving about the language prefix and about a check's messages
 * carrying a suffix.
 */
public final class TestTreeMapper {

    private static final String POSSIBLE_VALUES_EXPRESSION = "Possible Values:\\s*\\[([^\\]]+)]";
    private static final String NOT_ALPHANUMERIC_EXPRESSION = "[^a-z0-9]";
    private static final Pattern POSSIBLE_VALUES = Pattern.compile(POSSIBLE_VALUES_EXPRESSION);
    private static final Pattern NOT_ALPHANUMERIC = Pattern.compile(NOT_ALPHANUMERIC_EXPRESSION);
    private static final String PROFILE_MARKER = "JAVA";
    private static final String NOTHING = "";
    private static final String VALUE_SEPARATOR = ",";
    private static final String CODE_SEPARATOR = ".";
    private static final String DISPLAY_NAME = "displayName";
    private static final String CHILDREN = "children";
    private static final String UNMATCHED_FORMAT = "%s: %s";
    private static final String JAVA_PREFIX = "java";
    private static final String TYPESCRIPT_PREFIX = "typescript";
    private static final String VUEJS_PREFIX = "vuejs";
    private static final String PERL_PREFIX = "perl";
    private static final List<String> LANGUAGE_PREFIXES =
            List.of(JAVA_PREFIX, TYPESCRIPT_PREFIX, VUEJS_PREFIX, PERL_PREFIX);
    private static final int MINIMUM_PREFIX_MATCH = 10;
    private static final String NOT_INSTANTIABLE = "This is a utility class and cannot be instantiated.";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Refuses instantiation: this is a holder of static methods.
     */
    private TestTreeMapper() {
        throw new UnsupportedOperationException(NOT_INSTANTIABLE);
    }

    /**
     * The {@code --test} values, read out of Sheriff's usage text.
     *
     * <p>The usage lists several enums and {@code --test} is not the first of
     * them, so the list is picked by what it contains. Taking the first match
     * silently yields the output formats, which name no profile at all.
     *
     * @param helpOutput what {@code tree --help} printed
     * @return the profile names, empty when none could be found
     */
    public static List<String> profileNames(String helpOutput) {
        Matcher matcher = POSSIBLE_VALUES.matcher(helpOutput);
        while (matcher.find()) {
            List<String> names = new ArrayList<>();
            for (String value : matcher.group(1).split(VALUE_SEPARATOR)) {
                names.add(value.strip());
            }
            if (names.contains(PROFILE_MARKER)) {
                return names;
            }
        }
        return List.of();
    }

    /**
     * Profile name to the display names of the checks it runs.
     *
     * @param treeJson what {@code tree --export-profile JSON} printed
     * @param profileNames the valid {@code --test} values
     * @return the checks of each profile this could place, in tree order
     */
    public static Map<String, List<String>> checksByProfile(String treeJson, List<String> profileNames) {
        Map<String, List<String>> placed = new LinkedHashMap<>();
        Map<String, String> byKey = new LinkedHashMap<>();
        for (String name : profileNames) {
            byKey.put(normalize(name), name);
        }
        for (JsonNode root : roots(treeJson)) {
            String profile = byKey.get(normalize(root.path(DISPLAY_NAME).asText()));
            if (profile != null) {
                placed.put(profile, leaves(root));
            }
        }
        return placed;
    }

    /**
     * The profiles Sheriff named that are not {@code --test} values.
     *
     * @param treeJson what {@code tree --export-profile JSON} printed
     * @param profileNames the valid {@code --test} values
     * @return those display names, so they are reported rather than dropped
     */
    public static List<String> unplacedProfiles(String treeJson, List<String> profileNames) {
        Set<String> keys = new LinkedHashSet<>();
        for (String name : profileNames) {
            keys.add(normalize(name));
        }
        List<String> unplaced = new ArrayList<>();
        for (JsonNode root : roots(treeJson)) {
            String display = root.path(DISPLAY_NAME).asText();
            if (!keys.contains(normalize(display))) {
                unplaced.add(display);
            }
        }
        return unplaced;
    }

    /**
     * Profile name to the rule codes it can report.
     *
     * @param entries the merged catalog
     * @param treeJson what {@code tree --export-profile JSON} printed
     * @param profileNames the valid {@code --test} values
     * @return the codes of each profile, sorted
     */
    public static Map<String, List<String>> profileRules(
            List<CatalogEntry> entries, String treeJson, List<String> profileNames) {
        Map<String, List<String>> byProfile = new TreeMap<>();
        for (Map.Entry<String, List<String>> profile : checksByProfile(treeJson, profileNames).entrySet()) {
            Set<String> codes = new LinkedHashSet<>();
            for (String check : profile.getValue()) {
                for (CatalogEntry entry : entries) {
                    if (matches(entry.code(), check)) {
                        codes.add(entry.code());
                    }
                }
            }
            List<String> sorted = new ArrayList<>(codes);
            sorted.sort(String::compareTo);
            byProfile.put(profile.getKey(), sorted);
        }
        return byProfile;
    }

    /**
     * The checks no rule code could be matched to.
     *
     * <p>Real rather than a matching bug to hide: the tree lists
     * {@code Java to string method} and no message class in the image
     * mentions toString, so it is a rule this catalog does not know.
     *
     * @param entries the merged catalog
     * @param treeJson what {@code tree --export-profile JSON} printed
     * @param profileNames the valid {@code --test} values
     * @return those checks, each prefixed by its profile, plus any profile
     *     that could not be placed at all
     */
    public static List<String> unmatchedChecks(
            List<CatalogEntry> entries, String treeJson, List<String> profileNames) {
        List<String> unmatched = new ArrayList<>(unplacedProfiles(treeJson, profileNames));
        for (Map.Entry<String, List<String>> profile : checksByProfile(treeJson, profileNames).entrySet()) {
            for (String check : profile.getValue()) {
                boolean found = entries.stream().anyMatch(entry -> matches(entry.code(), check));
                if (!found) {
                    unmatched.add(String.format(UNMATCHED_FORMAT, profile.getKey(), check));
                }
            }
        }
        unmatched.sort(String::compareTo);
        return unmatched;
    }

    /**
     * Whether a rule code is one of the messages a check reports.
     *
     * <p>Not equality on purpose: one check emits several messages, so both
     * {@code SentencePositionFirstLine} and {@code SentencePositionLastLine}
     * belong to the leaf "Java sentence position".
     *
     * <p>Dropping the language prefix on both sides at once would let a
     * TypeScript rule match a Java check — {@code TypeScriptFolderNameFormat}
     * and "Java folder name format" both reduce to {@code foldernameformat}.
     * Measured: that put 15 rules in a profile of the wrong language. When
     * both sides name a language and the languages differ, they are not the
     * same rule.
     *
     * @param code the rule code
     * @param check the check's display name
     * @return whether they are the same rule
     */
    static boolean matches(String code, String check) {
        String bareCode = code.split(Pattern.quote(CODE_SEPARATOR))[0];
        String codeLanguage = languagePrefix(normalize(bareCode));
        String checkLanguage = languagePrefix(normalize(check));
        if (!codeLanguage.isEmpty() && !checkLanguage.isEmpty() && !codeLanguage.equals(checkLanguage)) {
            return false;
        }
        Set<String> codeForms = forms(bareCode);
        Set<String> checkForms = forms(check);
        for (String form : checkForms) {
            if (codeForms.contains(form)) {
                return true;
            }
            if (form.length() < MINIMUM_PREFIX_MATCH) {
                continue;
            }
            for (String candidate : codeForms) {
                if (candidate.startsWith(form)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * The spellings a name might be written in.
     *
     * <p>The language prefix cannot simply be stripped:
     * {@code JavaDocCommentInConstructorIsEmpty} begins with "Java" as part
     * of the word JavaDoc, not as a prefix. Both readings are kept.
     *
     * @param name the name to normalize
     * @return its candidate forms
     */
    private static Set<String> forms(String name) {
        String flat = normalize(name);
        Set<String> forms = new LinkedHashSet<>();
        forms.add(flat);
        String prefix = languagePrefix(flat);
        if (!prefix.isEmpty()) {
            forms.add(flat.substring(prefix.length()));
        }
        return forms;
    }

    /**
     * The language a flattened name announces.
     *
     * @param flat the name reduced to letters and digits
     * @return that language, or the empty string when it announces none
     */
    private static String languagePrefix(String flat) {
        for (String prefix : LANGUAGE_PREFIXES) {
            if (flat.startsWith(prefix) && flat.length() > prefix.length()) {
                return prefix;
            }
        }
        return NOTHING;
    }

    /**
     * A name reduced to its letters and digits, in lower case.
     *
     * @param name the name to reduce
     * @return that reduction
     */
    private static String normalize(String name) {
        return NOT_ALPHANUMERIC.matcher(name.toLowerCase(Locale.ROOT)).replaceAll(NOTHING);
    }

    /**
     * The roots of the exported tree.
     *
     * @param treeJson what Sheriff printed
     * @return those roots, empty when the text is not the tree
     */
    private static List<JsonNode> roots(String treeJson) {
        try {
            JsonNode parsed = MAPPER.readTree(treeJson);
            List<JsonNode> roots = new ArrayList<>();
            parsed.forEach(roots::add);
            return roots;
        } catch (Exception malformed) {
            return List.of();
        }
    }

    /**
     * Every leaf below a node, in tree order.
     *
     * @param node the node to walk
     * @return the display names of its leaves
     */
    private static List<String> leaves(JsonNode node) {
        JsonNode children = node.path(CHILDREN);
        if (!children.isArray() || children.isEmpty()) {
            String display = node.path(DISPLAY_NAME).asText();
            return display.isEmpty() ? List.of() : List.of(display);
        }
        List<String> found = new ArrayList<>();
        children.forEach(child -> found.addAll(leaves(child)));
        return found;
    }
}
