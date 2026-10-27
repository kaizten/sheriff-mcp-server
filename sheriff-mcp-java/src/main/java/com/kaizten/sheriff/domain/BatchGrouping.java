package com.kaizten.sheriff.domain;

import java.util.Locale;

/**
 * What the errors of one pass have in common: their file, or their rule.
 *
 * <p>By file, a pass takes every error of a few files, whatever their rules,
 * so the model reads each file once. By rule, a pass takes the errors of one
 * rule in a few files, so the model works on one kind of change at a time.
 * Which costs less, and which leaves less behind, is what a benchmark
 * measures; by file is the loop's default.
 */
public enum BatchGrouping {

    /**
     * Every error of a few files.
     */
    FILE,

    /**
     * The errors of one rule, in a few files.
     */
    RULE;

    private static final String UNKNOWN = "Unknown grouping '%s': it is file or rule";

    /**
     * The grouping a setting or a flag names, in any case.
     *
     * @param name {@code file} or {@code rule}
     * @return the grouping
     * @throws IllegalArgumentException when it names neither
     */
    public static BatchGrouping named(String name) {
        for (BatchGrouping grouping : values()) {
            if (grouping.name().equalsIgnoreCase(name.strip())) {
                return grouping;
            }
        }
        throw new IllegalArgumentException(String.format(UNKNOWN, name));
    }

    /**
     * The name a setting or a flag gives it.
     *
     * @return {@code file} or {@code rule}
     */
    public String setting() {
        return name().toLowerCase(Locale.ROOT);
    }
}
