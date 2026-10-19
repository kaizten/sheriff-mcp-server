package com.kaizten.sheriff.infrastructure.coverage;

/**
 * What the tests did with one line of code, as JaCoCo counts it: whether
 * they ran it, and how many of the ways its conditions can go they took.
 *
 * <p>A line runs whichever way its condition goes, so a line covered says
 * nothing of the other way: {@code if (member && units > 2)} is four
 * branches, one per side of each half, and a test with a member buying
 * three runs the line taking one of them.
 *
 * @param run whether a test ran it
 * @param branches the branches its conditions have, none for a line with
 *     no condition
 * @param coveredBranches those a test took
 */
record CoveredLine(boolean run, int branches, int coveredBranches) {
}
