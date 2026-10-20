package com.kaizten.sheriff.infrastructure.coverage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * How well the tests test the code a change wrote: how many of its lines of
 * code they run, how many of the branches of its conditions they take, and
 * how many of PIT's mutants of those lines they kill, against the thresholds
 * the project is held to.
 *
 * @param applies whether there was a change to measure: Java a Maven
 *     component's main sources do not hold at its last commit
 * @param problem why it could not be measured, empty when it was
 * @param lines the changed lines that hold code, and those a test ran
 * @param branches the branches on those lines, and those a test took
 * @param mutants the mutants of those lines that count, and those a test
 *     failed on
 * @param uncovered the lines no test ran, per file, the file relative to the
 *     component
 * @param partial the lines a test ran without taking every branch, as
 *     {@code file:line takes 1 of its 4 branches}
 * @param survivors the mutants every test passed with, as
 *     {@code file:line change}
 * @param thresholds what the project is held to
 */
public record CoverageVerdict(boolean applies, String problem, Tally lines, Tally branches, Tally mutants,
        Map<String, List<Integer>> uncovered, List<String> partial, List<String> survivors,
        CoverageThresholds thresholds) {

    private static final String NOTHING = "";
    private static final int NONE = 0;
    private static final int SHOWN = 20;
    private static final String SOURCES = "src/main/java/";
    private static final String SURVIVOR = "%s:%d %s";
    private static final String PARTIAL = "%s:%d takes %d of its %d branches";
    private static final String SUMMARY = "The tests run %d of the %d lines of code this change wrote (%d%%, %d%% "
            + "required), take %d of the %d branches of their conditions (%d%%, %d%% required) and kill %d of the "
            + "%d mutants PIT made of them (%d%%, %d%% required).";
    private static final String NO_CODE = "This change wrote no line of code the tests could run.";
    private static final String UNCOVERED_HEADING = "%nLines of code this change wrote that no test runs:";
    private static final String PARTIAL_HEADING = "%nConditions this change wrote that the tests take only some "
            + "ways, each side of every && and || counted:";
    private static final String SURVIVOR_HEADING = "%nChanges PIT made to those lines that every test still "
            + "passed with:";
    private static final String ENTRY = "%n  - %s";
    private static final String FILE_LINES = "%s: %s";
    private static final String MORE = "%n  ... and %d more";
    private static final String LINE_SEPARATOR = ", ";

    /**
     * There was nothing to measure: no Java the change wrote, or no Maven.
     *
     * @return that verdict
     */
    public static CoverageVerdict nothingToMeasure() {
        return new CoverageVerdict(false, NOTHING, Tally.none(), Tally.none(), Tally.none(), Map.of(), List.of(),
                List.of(), CoverageThresholds.defaults());
    }

    /**
     * There was a change, and it could not be measured.
     *
     * @param problem why, in the tools' own words where they gave any
     * @return that verdict
     */
    public static CoverageVerdict unmeasured(String problem) {
        return new CoverageVerdict(true, problem, Tally.none(), Tally.none(), Tally.none(), Map.of(), List.of(),
                List.of(), CoverageThresholds.defaults());
    }

    /**
     * What the reports say of the lines a change wrote.
     *
     * @param changes the changed lines of each source file, under its source
     *     folder
     * @param covered JaCoCo's lines of code of each file
     * @param found PIT's mutants
     * @param thresholds what the project is held to
     * @return that verdict
     */
    static CoverageVerdict of(Map<String, Set<Integer>> changes, Map<String, Map<Integer, CoveredLine>> covered,
            List<Mutant> found, CoverageThresholds thresholds) {
        int code = NONE;
        int run = NONE;
        int branches = NONE;
        int taken = NONE;
        Map<String, List<Integer>> uncovered = new TreeMap<>();
        List<String> partial = new ArrayList<>();
        for (Map.Entry<String, Set<Integer>> change : changes.entrySet()) {
            String file = SOURCES + change.getKey();
            Map<Integer, CoveredLine> known = covered.getOrDefault(change.getKey(), Map.of());
            for (int number : change.getValue()) {
                CoveredLine line = known.get(number);
                if (line == null) {
                    continue;
                }
                code++;
                branches += line.branches();
                taken += line.coveredBranches();
                if (!line.run()) {
                    uncovered.computeIfAbsent(file, key -> new ArrayList<>()).add(number);
                } else if (line.coveredBranches() < line.branches()) {
                    run++;
                    partial.add(String.format(PARTIAL, file, number, line.coveredBranches(), line.branches()));
                } else {
                    run++;
                }
            }
        }
        int mutants = NONE;
        int killed = NONE;
        List<String> survivors = new ArrayList<>();
        for (Mutant mutant : found) {
            if (!mutant.counts() || !changes.getOrDefault(mutant.file(), Set.of()).contains(mutant.line())) {
                continue;
            }
            mutants++;
            if (mutant.detected()) {
                killed++;
            } else if (mutant.survived()) {
                survivors.add(String.format(SURVIVOR, SOURCES + mutant.file(), mutant.line(), mutant.description()));
            }
        }
        return new CoverageVerdict(true, NOTHING, new Tally(code, run), new Tally(branches, taken),
                new Tally(mutants, killed), uncovered, partial, survivors, thresholds);
    }

    /**
     * Whether it was measured.
     *
     * @return {@code true} when there was a change and its reports were read
     */
    public boolean measured() {
        return applies && problem.isEmpty();
    }

    /**
     * Whether the tests test the change as well as the project asks.
     *
     * @return {@code true} when the three shares reach their thresholds, and
     *     when there was nothing to measure
     */
    public boolean passes() {
        return !measured() || lines.percent() >= thresholds.lines() && branches.percent() >= thresholds.branches()
                && mutants.percent() >= thresholds.mutants();
    }

    /**
     * The three shares and their thresholds, in a sentence.
     *
     * @return that sentence
     */
    public String summary() {
        if (!lines.any() && !mutants.any()) {
            return NO_CODE;
        }
        return String.format(SUMMARY, lines.reached(), lines.total(), lines.percent(), thresholds.lines(),
                branches.reached(), branches.total(), branches.percent(), thresholds.branches(), mutants.reached(),
                mutants.total(), mutants.percent(), thresholds.mutants());
    }

    /**
     * What is left to test: the lines no test runs, the conditions taken only
     * some ways, and the mutants every test passed with, each list cut at
     * twenty.
     *
     * @return those lists, each under its heading, or empty when all are
     */
    public String shortfall() {
        StringBuilder text = new StringBuilder();
        if (!uncovered.isEmpty()) {
            text.append(String.format(UNCOVERED_HEADING));
            text.append(listed(uncovered.entrySet().stream().map(file -> String.format(FILE_LINES, file.getKey(),
                    file.getValue().stream().map(String::valueOf).collect(Collectors.joining(LINE_SEPARATOR))))
                    .toList()));
        }
        if (!partial.isEmpty()) {
            text.append(String.format(PARTIAL_HEADING));
            text.append(listed(partial));
        }
        if (!survivors.isEmpty()) {
            text.append(String.format(SURVIVOR_HEADING));
            text.append(listed(survivors));
        }
        return text.toString();
    }

    /**
     * Entries one per line, the first twenty and how many more.
     *
     * @param entries the entries
     * @return them, each on its own line
     */
    private static String listed(List<String> entries) {
        StringBuilder text = new StringBuilder();
        entries.stream().limit(SHOWN).forEach(entry -> text.append(String.format(ENTRY, entry)));
        if (entries.size() > SHOWN) {
            text.append(String.format(MORE, entries.size() - SHOWN));
        }
        return text.toString();
    }
}
