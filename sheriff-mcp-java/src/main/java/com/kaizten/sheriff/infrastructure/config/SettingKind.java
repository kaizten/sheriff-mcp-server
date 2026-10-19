package com.kaizten.sheriff.infrastructure.config;

/**
 * What values a {@link Setting} takes.
 */
public enum SettingKind {

    /**
     * A whole number of seconds, one or more.
     */
    SECONDS,

    /**
     * A whole number, its minimum or more.
     */
    COUNT,

    /**
     * A share from 0 to 100.
     */
    SHARE,

    /**
     * {@code 0} or {@code 1}.
     */
    FLAG,

    /**
     * One of a list.
     */
    CHOICE,

    /**
     * A word with no spaces.
     */
    WORD,

    /**
     * PIT's mutators, separated by commas.
     */
    MUTATORS
}
