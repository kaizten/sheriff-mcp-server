package com.kaizten.sheriff.infrastructure.coverage;

/**
 * How many of something there are and how many the tests reached: lines
 * run, branches taken, mutants killed.
 *
 * @param total how many there are
 * @param reached how many the tests reached
 */
public record Tally(int total, int reached) {

    private static final int NONE = 0;
    private static final int ALL = 100;

    /**
     * Nothing counted.
     *
     * @return a tally of none
     */
    public static Tally none() {
        return new Tally(NONE, NONE);
    }

    /**
     * The share reached.
     *
     * @return it, in whole percent, rounded down; all of none
     */
    public int percent() {
        return total == NONE ? ALL : reached * ALL / total;
    }

    /**
     * Whether there was anything to count.
     *
     * @return {@code true} when there was
     */
    public boolean any() {
        return total > NONE;
    }
}
