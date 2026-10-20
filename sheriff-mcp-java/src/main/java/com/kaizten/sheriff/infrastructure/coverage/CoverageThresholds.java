package com.kaizten.sheriff.infrastructure.coverage;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * How well a change's tests must test the code it wrote: the share of its
 * lines they run, the share of the branches of its conditions they take, and
 * the share of PIT's mutants of those lines they kill, with the mutators the
 * mutants are made with.
 *
 * <p>All of the lines and all of the branches by default: a line no test runs
 * is code nothing checks, and a condition taken one way only is half of it
 * checked. Four mutants in five, not all, as some mutants change nothing a
 * test could see (equivalent mutants), and asking for all of them would block
 * a change with no way out. The mutants are made with PIT's stronger set, every
 * condition forced each way ({@code REMOVE_CONDITIONALS}) and a call's argument
 * put in place of its result ({@code EXPERIMENTAL_ARGUMENT_PROPAGATION}): what
 * the conditions on a method's parameters, and what is done with them, must
 * be tested for. Not {@code ALL}: on PetClinic's owner package it made 497
 * mutants where this makes 204, the rest such as a constant in
 * {@code setDisallowedFields("id")} replaced, which no sensible test kills
 * (measured 19 October 2026). A project declares others where it declares
 * its profile: {@code sheriff.coverage}, {@code sheriff.branches},
 * {@code sheriff.mutation} and {@code sheriff.mutators} in its
 * {@code pom.xml}; {@code coverage}, {@code branches}, {@code mutation} and
 * {@code mutators} in {@code .sheriff.properties}.
 *
 * @param lines the least share of the changed lines the tests must run, in
 *     percent
 * @param branches the least share of the branches on those lines the tests
 *     must take, in percent
 * @param mutants the least share of the mutants of those lines the tests
 *     must kill, in percent
 * @param mutators PIT's mutators, or groups of them, separated by commas
 */
public record CoverageThresholds(int lines, int branches, int mutants, String mutators) {

    /**
     * The {@code pom.xml} property that declares the share of lines.
     */
    public static final String LINES_PROPERTY = "sheriff.coverage";

    /**
     * The {@code .sheriff.properties} key that declares the share of lines.
     */
    public static final String LINES_KEY = "coverage";

    /**
     * The {@code pom.xml} property that declares the share of branches.
     */
    public static final String BRANCHES_PROPERTY = "sheriff.branches";

    /**
     * The {@code .sheriff.properties} key that declares the share of
     * branches.
     */
    public static final String BRANCHES_KEY = "branches";

    /**
     * The {@code pom.xml} property that declares the share of mutants.
     */
    public static final String MUTANTS_PROPERTY = "sheriff.mutation";

    /**
     * The {@code .sheriff.properties} key that declares the share of mutants.
     */
    public static final String MUTANTS_KEY = "mutation";

    /**
     * The {@code pom.xml} property that declares PIT's mutators.
     */
    public static final String MUTATORS_PROPERTY = "sheriff.mutators";

    /**
     * The {@code .sheriff.properties} key that declares PIT's mutators.
     */
    public static final String MUTATORS_KEY = "mutators";

    private static final int ALL = 100;
    private static final int NONE = 0;
    private static final int DEFAULT_MUTANTS = 80;
    private static final String DEFAULT_MUTATORS = "STRONGER,REMOVE_CONDITIONALS,EXPERIMENTAL_ARGUMENT_PROPAGATION";
    private static final String MUTATOR_NAMES = "[A-Z0-9_]+(,[A-Z0-9_]+)*";
    private static final Pattern MUTATOR_LIST = Pattern.compile(MUTATOR_NAMES);
    private static final String PERCENT = "%";
    private static final String NOTHING = "";
    private static final String SPACE = " ";

    /**
     * The thresholds a project declares, each one it leaves out, or writes as
     * no share from 0 to 100, at its default.
     *
     * @param lines the share of lines as declared, or empty
     * @param branches the share of branches as declared, or empty
     * @param mutants the share of mutants as declared, or empty
     * @param mutators PIT's mutators as declared, or empty
     * @return those thresholds
     */
    public static CoverageThresholds declared(Optional<String> lines, Optional<String> branches,
            Optional<String> mutants, Optional<String> mutators) {
        return new CoverageThresholds(percent(lines, ALL), percent(branches, ALL), percent(mutants, DEFAULT_MUTANTS),
                mutatorsOf(mutators));
    }

    /**
     * The thresholds of a project that declares none.
     *
     * @return the defaults
     */
    public static CoverageThresholds defaults() {
        return declared(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    /**
     * A share as written, {@code 80} or {@code 80%}.
     *
     * @param declared the value as written, or empty
     * @param otherwise the share when it is missing or is not one
     * @return the share, in percent
     */
    private static int percent(Optional<String> declared, int otherwise) {
        try {
            int value = Integer.parseInt(declared.orElse(NOTHING).strip().replace(PERCENT, NOTHING));
            return value < NONE || value > ALL ? otherwise : value;
        } catch (NumberFormatException exception) {
            return otherwise;
        }
    }

    /**
     * PIT's mutators as written, {@code STRONGER} or
     * {@code DEFAULTS,REMOVE_CONDITIONALS}: names in capitals, never anything
     * else that would reach Maven's command line.
     *
     * @param declared the value as written, or empty
     * @return it, without spaces, or the default set when it is missing or
     *     is not a list of names
     */
    private static String mutatorsOf(Optional<String> declared) {
        String written = declared.orElse(NOTHING).replace(SPACE, NOTHING).strip();
        return MUTATOR_LIST.matcher(written).matches() ? written : DEFAULT_MUTATORS;
    }
}
