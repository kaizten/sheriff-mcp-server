package com.kaizten.sheriff.infrastructure.coverage;

/**
 * One change PIT made to the code, and what the tests did about it.
 *
 * @param file the source file, under its source folder ({@code demo/Calc.java})
 * @param line the line it changed
 * @param status what became of it: {@code KILLED}, {@code SURVIVED},
 *     {@code NO_COVERAGE}, {@code TIMED_OUT}, {@code NON_VIABLE} and others
 * @param description the change, in PIT's words
 * @param detected whether a test failed on it, as PIT counts it
 */
record Mutant(String file, int line, String status, String description, boolean detected) {

    private static final String NON_VIABLE = "NON_VIABLE";
    private static final String SURVIVED = "SURVIVED";

    /**
     * Whether it counts: a mutant the JVM could not even load tells nothing
     * about the tests.
     *
     * @return {@code false} for a mutant that was not viable
     */
    boolean counts() {
        return !NON_VIABLE.equals(status);
    }

    /**
     * Whether the tests ran its line and still passed.
     *
     * @return {@code true} for a mutant that survived
     */
    boolean survived() {
        return SURVIVED.equals(status);
    }
}
