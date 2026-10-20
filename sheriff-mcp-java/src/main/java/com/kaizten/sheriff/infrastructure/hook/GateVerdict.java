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
 * @param keys every error, as {@link StuckErrors#key} knows it; empty when
 *     the analysis stopped at the first error, or the verdict was recorded
 *     before it said
 */
public record GateVerdict(int errors, String sample, Set<String> files, Set<String> keys) {

    private static final int UNKNOWN = -1;
    private static final int NO_ERRORS = 0;

    /**
     * A verdict that could not be reached.
     */
    public static final GateVerdict UNAVAILABLE = new GateVerdict(UNKNOWN, "");

    /**
     * Normalizes a null sample, a null set of files and a null set of keys.
     */
    public GateVerdict {
        sample = sample == null ? "" : sample;
        files = files == null ? Set.of() : Set.copyOf(files);
        keys = keys == null ? Set.of() : Set.copyOf(keys);
    }

    /**
     * A verdict that does not say which errors it found.
     *
     * @param errors how many errors the component has, negative when unknown
     * @param sample a few of them, for the message
     * @param files the files that have them
     */
    public GateVerdict(int errors, String sample, Set<String> files) {
        this(errors, sample, files, Set.of());
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
     * Whether every error it found is one left for the end: what is left is
     * nothing to hold new work up for.
     *
     * @param deferred the errors left for the end
     * @return {@code true} when it has errors, knows each one, and each one is
     *     left for the end
     */
    public boolean onlyAmong(Set<String> deferred) {
        return errors > NO_ERRORS && !keys.isEmpty() && deferred.containsAll(keys);
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
