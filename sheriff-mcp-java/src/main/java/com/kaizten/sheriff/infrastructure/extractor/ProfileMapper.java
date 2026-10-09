package com.kaizten.sheriff.infrastructure.extractor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Works out which rules each {@code --test} profile actually runs.
 *
 * <p>Sharing a language is not the same as being in force: Sheriff has 135
 * Java rules and the {@code JAVA} profile runs about fifty. Telling a fixer
 * that all of them apply is how code gets restructured to satisfy a rule that
 * was never running.
 *
 * <p>The answer comes out of the image's own bytecode. {@code
 * TestFactory.create()} is a switch from the profile enum to one root checker
 * class, and a checker reaches its rules through the classes it references, so
 * a profile's rule set is the transitive closure of references from that root.
 *
 * <p>This is the one approximation in the catalog, and it is worth stating
 * plainly: a rule reached only through reflection leaves no reference to
 * follow, so a profile's list is "at least these", never "exactly these".
 */
public final class ProfileMapper {

    private static final String CREATE_MARKER = "@@@CREATE";
    private static final String CLASS_MARKER = "@@CLASS ";
    private static final String KAIZTEN_PREFIX = "com/kaizten/";
    private static final String ENUM_CONSTANT_REGEX = "AnalysisTest\\.([A-Z_]+):";
    private static final String CASE_NUMBER_REGEX = "(?:iconst_(\\d)|bipush\\s+(\\d+))";
    private static final String SWITCH_ENTRY_REGEX = "^\\s*(\\d+): (\\d+)\\s*$";
    private static final String INSTANTIATION_REGEX =
            "^\\s*(\\d+): new\\s+#\\d+\\s+// class (com/kaizten/\\S+)";
    private static final Pattern ENUM_CONSTANT = Pattern.compile(ENUM_CONSTANT_REGEX);
    private static final Pattern CASE_NUMBER = Pattern.compile(CASE_NUMBER_REGEX);
    private static final Pattern SWITCH_ENTRY = Pattern.compile(SWITCH_ENTRY_REGEX);
    private static final Pattern INSTANTIATION = Pattern.compile(INSTANTIATION_REGEX);
    private static final String TABLESWITCH = "tableswitch";
    private static final String DEFAULT_CASE = "default:";
    private static final String NEWLINE = "\n";
    private static final String UTILITY_CLASS_MESSAGE = "This is a utility class and cannot be instantiated.";

    /**
     * Prevents instantiation of this utility class.
     */
    private ProfileMapper() {
        throw new UnsupportedOperationException(UTILITY_CLASS_MESSAGE);
    }

    /**
     * Class to the classes it references, from the reference-graph dump.
     *
     * @param dump the dump produced inside the image
     * @return that graph
     */
    public static Map<String, Set<String>> referenceGraph(String dump) {
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        Set<String> current = null;
        for (String raw : dump.split(NEWLINE)) {
            String line = raw.strip();
            if (line.startsWith(CLASS_MARKER)) {
                current = new LinkedHashSet<>();
                graph.put(line.substring(CLASS_MARKER.length()).strip(), current);
                continue;
            }
            if (current != null && line.startsWith(KAIZTEN_PREFIX)) {
                current.add(line);
            }
        }
        return graph;
    }

    /**
     * Profile to the root checker class the factory builds for it.
     *
     * <p>Two halves of one bytecode dump: the switch map says which case number
     * each enum constant got, and {@code create()}'s tableswitch says which
     * offset each case jumps to, where a {@code new} instruction names the
     * checker. A profile absent from the switch map falls through to the
     * default branch and gets no checker here — claiming one would be an
     * invention.
     *
     * @param dump the factory dump
     * @return profile names and their root checkers
     */
    public static Map<String, String> profileCheckers(String dump) {
        if (!dump.contains(CREATE_MARKER)) {
            return Map.of();
        }
        String[] halves = dump.split(CREATE_MARKER, 2);
        Map<String, Integer> profileCase = profileCases(halves[0]);
        Map<Integer, Integer> caseOffset = caseOffsets(halves[1]);
        Map<Integer, String> offsetClass = instantiations(halves[1]);
        Map<String, String> checkers = new TreeMap<>();
        for (Map.Entry<String, Integer> entry : profileCase.entrySet()) {
            Integer offset = caseOffset.get(entry.getValue());
            String checker = offset == null ? null : offsetClass.get(offset);
            if (checker != null) {
                checkers.put(entry.getKey(), checker);
            }
        }
        return checkers;
    }

    /**
     * Every class a checker can reach, transitively.
     *
     * @param root the checker to start from
     * @param graph the reference graph
     * @return those classes, the root included
     */
    public static Set<String> reachableFrom(String root, Map<String, Set<String>> graph) {
        Set<String> seen = new HashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            String current = pending.removeLast();
            if (!seen.add(current)) {
                continue;
            }
            for (String reference : graph.getOrDefault(current, Set.of())) {
                if (!seen.contains(reference)) {
                    pending.add(reference);
                }
            }
        }
        return seen;
    }

    /**
     * Profile to the rule codes it can report.
     *
     * @param entries every rule in the catalog, with its owning class
     * @param factoryDump the factory bytecode dump
     * @param graphDump the reference-graph dump
     * @return the mapping, profiles sorted
     */
    public static Map<String, List<String>> profileRules(
            List<CatalogEntry> entries, String factoryDump, String graphDump) {
        Map<String, Set<String>> graph = referenceGraph(graphDump);
        Map<String, List<String>> profiles = new TreeMap<>();
        for (Map.Entry<String, String> entry : profileCheckers(factoryDump).entrySet()) {
            Set<String> reachable = reachableFrom(entry.getValue(), graph);
            List<String> codes = new ArrayList<>();
            for (CatalogEntry rule : entries) {
                if (reachable.contains(rule.owner())) {
                    codes.add(rule.code());
                }
            }
            codes.sort(String::compareTo);
            profiles.put(entry.getKey(), codes);
        }
        return profiles;
    }

    /**
     * Which case number the compiler gave each profile in the switch map.
     *
     * @param switchMap the half of the dump holding the synthetic switch map
     * @return profile names and their case numbers
     */
    private static Map<String, Integer> profileCases(String switchMap) {
        Map<String, Integer> cases = new LinkedHashMap<>();
        String pending = "";
        for (String line : switchMap.split(NEWLINE)) {
            Matcher constant = ENUM_CONSTANT.matcher(line);
            if (constant.find()) {
                pending = constant.group(1);
                continue;
            }
            Matcher number = CASE_NUMBER.matcher(line);
            if (number.find() && !pending.isEmpty()) {
                String value = number.group(1) == null ? number.group(2) : number.group(1);
                cases.put(pending, Integer.parseInt(value));
                pending = "";
            }
        }
        return cases;
    }

    /**
     * Which bytecode offset each case of {@code create()}'s tableswitch jumps
     * to, read from the tableswitch table itself.
     *
     * @param create the half of the dump holding {@code create()}
     * @return case numbers and their offsets
     */
    private static Map<Integer, Integer> caseOffsets(String create) {
        Map<Integer, Integer> offsets = new LinkedHashMap<>();
        boolean inSwitch = false;
        for (String line : create.split(NEWLINE)) {
            if (line.contains(TABLESWITCH)) {
                inSwitch = true;
                continue;
            }
            if (!inSwitch) {
                continue;
            }
            Matcher entry = SWITCH_ENTRY.matcher(line);
            if (entry.find()) {
                offsets.put(Integer.parseInt(entry.group(1)), Integer.parseInt(entry.group(2)));
            }
            if (line.contains(DEFAULT_CASE)) {
                inSwitch = false;
            }
        }
        return offsets;
    }

    /**
     * Which checker class each {@code new} instruction builds, keyed by the
     * offset the tableswitch jumps to.
     *
     * @param create the half of the dump holding {@code create()}
     * @return offsets and the classes instantiated there, in slash form
     */
    private static Map<Integer, String> instantiations(String create) {
        Map<Integer, String> classes = new LinkedHashMap<>();
        for (String line : create.split(NEWLINE)) {
            Matcher instantiation = INSTANTIATION.matcher(line);
            if (instantiation.find()) {
                classes.put(Integer.parseInt(instantiation.group(1)), instantiation.group(2));
            }
        }
        return classes;
    }
}
