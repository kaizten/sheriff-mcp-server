package com.kaizten.sheriff.infrastructure.hook;

import java.util.Set;

/**
 * What the gate knows about one component right now.
 *
 * <p>Three states, not two, and the third is the one that matters: "could not
 * check" is not "clean". A gate that cannot reach Docker must let the edit
 * through, and it must not record that as a clean verdict either.
 *
 * @param errors how many errors the component has, negative when unknown
 * @param sample a few of them, for the message
 * @param files the files that have them, relative to the mount and written
 *     with {@code /}, as Sheriff names them
 */
public record GateVerdict(int errors, String sample, Set<String> files) {

    private static final int UNKNOWN = -1;
    private static final int NO_ERRORS = 0;

    /**
     * A verdict that could not be reached.
     */
    public static final GateVerdict UNAVAILABLE = new GateVerdict(UNKNOWN, "");

    /**
     * Normalizes a null sample and a null set of files.
     */
    public GateVerdict {
        sample = sample == null ? "" : sample;
        files = files == null ? Set.of() : Set.copyOf(files);
    }

    /**
     * A verdict that does not say which files have the errors, as one cached
     * before it did.
     *
     * @param errors how many errors the component has, negative when unknown
     * @param sample a few of them, for the message
     */
    public GateVerdict(int errors, String sample) {
        this(errors, sample, Set.of());
    }

    /**
     * Whether a file is one of those with errors.
     *
     * @param file the file, relative to the mount and written with {@code /}
     * @return {@code true} when Sheriff reported an error in it
     */
    public boolean hasErrorsIn(String file) {
        return files.contains(file);
    }

    /**
     * Whether the check actually happened.
     *
     * @return {@code true} when there is a real answer
     */
    public boolean known() {
        return errors >= NO_ERRORS;
    }

    /**
     * Whether the component is clean.
     *
     * @return {@code true} when it was checked and had no errors
     */
    public boolean clean() {
        return errors == NO_ERRORS;
    }
}
