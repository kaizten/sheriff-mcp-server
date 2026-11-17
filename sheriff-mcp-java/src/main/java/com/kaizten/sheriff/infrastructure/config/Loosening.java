package com.kaizten.sheriff.infrastructure.config;

import java.nio.file.Path;

/**
 * One line of a project's settings that now checks less than the last commit
 * has it check.
 *
 * @param file the file the line is in
 * @param key the key as the file writes it, such as {@code coverage} or
 *     {@code sheriff.coverage}
 * @param before its value in the last commit, empty when it was not set
 * @param after its value now, empty when it is not set
 */
public record Loosening(Path file, String key, String before, String after) {

    private static final String LINE = "%s: %s %s -> %s";
    private static final String UNSET = "unset";

    /**
     * The change as a person reads it.
     *
     * @return {@code file: key before -> after}, a value not set written
     *     {@code unset}
     */
    public String describe() {
        return String.format(LINE, file, key, shown(before), shown(after));
    }

    /**
     * The change as a person reads it, the file named from a folder it is
     * in.
     *
     * @param folder the folder, such as the one Sheriff mounts
     * @return {@code file: key before -> after}, the file relative to the
     *     folder when it is inside it
     */
    public String describeFrom(Path folder) {
        Path shown = file.startsWith(folder) ? folder.relativize(file) : file;
        return new Loosening(shown, key, before, after).describe();
    }

    /**
     * A value as the description shows it.
     *
     * @param value the value, empty when it is not set
     * @return it, or {@code unset}
     */
    private static String shown(String value) {
        return value.isEmpty() ? UNSET : value;
    }
}
