package com.kaizten.sheriff.infrastructure.hook;

import java.nio.file.Path;
import java.util.Map;

/**
 * What a project's settings files held at a point the Stop hook compares
 * with: a commit, and the settings files that differed from it then, with
 * what they held.
 *
 * @param commit the commit, empty for whatever {@code HEAD} is when compared
 * @param contents each settings file that was not as the commit has it, with
 *     its text then, empty for one that was gone
 */
public record SettingsSnapshot(String commit, Map<Path, String> contents) {

    private static final String HEAD = "HEAD";
    private static final String NO_COMMIT = "";

    /**
     * The snapshot of a session with no record: the last commit, whatever it
     * is when compared.
     *
     * @return that snapshot
     */
    public static SettingsSnapshot lastCommit() {
        return new SettingsSnapshot(NO_COMMIT, Map.of());
    }

    /**
     * Whether it names a commit of its own rather than {@code HEAD}.
     *
     * @return {@code true} when it does
     */
    public boolean hasCommit() {
        return !commit.isEmpty();
    }

    /**
     * The revision to ask git for the files it did not keep.
     *
     * @return the commit, or {@code HEAD}
     */
    public String revision() {
        return hasCommit() ? commit : HEAD;
    }
}
