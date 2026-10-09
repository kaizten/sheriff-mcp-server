package com.kaizten.sheriff.infrastructure.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/**
 * Where the hooks and the locks keep what must outlive one process: a folder
 * of the system's temporary directory that belongs to the user running them.
 *
 * <p>Every hook run is a process of its own, so what one remembers for the
 * next (a session already warned, a turn's starting point, how often a stop
 * was sent back, which run holds a mount) is a file. Those folders used to
 * be shared by every user of the machine: the first to run one created it
 * readable by all and writable by no one else, and from then on another
 * user's hooks could write nothing there, so their gate blocked again on
 * every edit and their runs took no lock. One folder per user cannot be
 * taken by someone else.
 *
 * <p>Nothing removed what was written for a session either, so a marker per
 * session and component built up for good. The folders of markers are pruned
 * when they are written to; a lock file is never removed, since removing one
 * another process may be waiting on would let two runs hold the same mount.
 */
public final class TemporaryFolders {

    /**
     * How long a marker is kept: longer than any session is left and picked
     * up again, short enough that the folder stays small.
     */
    public static final Duration MARKER_LIFETIME = Duration.ofDays(7);

    private static final String TEMPORARY_DIRECTORY = "java.io.tmpdir";
    private static final String USER_NAME = "user.name";
    private static final String NAME_FORMAT = "%s-%s";
    private static final String UNSAFE_CHARACTERS = "[^A-Za-z0-9._-]";
    private static final String SAFE_CHARACTER = "_";
    private static final String UNKNOWN_USER = "user";
    private static final String ERROR_UTILITY_CLASS = "This is a utility class and cannot be instantiated.";

    /**
     * Refuses to be instantiated: every member here is static.
     */
    private TemporaryFolders() {
        throw new UnsupportedOperationException(ERROR_UTILITY_CLASS);
    }

    /**
     * The folder of the system's temporary directory for one purpose, of the
     * user running this JVM.
     *
     * @param purpose what the folder holds, such as {@code sheriff-gate}
     * @return that folder, not necessarily created yet
     */
    public static Path forUser(String purpose) {
        return forUser(purpose, System.getProperty(USER_NAME));
    }

    /**
     * The same, for a given user, so the name can be tested.
     *
     * @param purpose what the folder holds
     * @param user the user's name, possibly empty or with characters a file
     *     name cannot hold
     * @return that folder
     */
    static Path forUser(String purpose, String user) {
        String safe = user == null || user.isBlank() ? UNKNOWN_USER
                : user.replaceAll(UNSAFE_CHARACTERS, SAFE_CHARACTER);
        return Path.of(System.getProperty(TEMPORARY_DIRECTORY), String.format(NAME_FORMAT, purpose, safe));
    }

    /**
     * Removes the files directly in a folder that were last written longer
     * ago than a lifetime. Best effort: what cannot be read or removed is
     * left as it is.
     *
     * @param folder the folder
     * @param lifetime how long a file is kept
     */
    public static void prune(Path folder, Duration lifetime) {
        Instant oldest = Instant.now().minus(lifetime);
        List<Path> files;
        try (Stream<Path> entries = Files.list(folder)) {
            files = entries.filter(Files::isRegularFile).toList();
        } catch (IOException exception) {
            return;
        }
        for (Path file : files) {
            try {
                FileTime written = Files.getLastModifiedTime(file);
                if (written.toInstant().isBefore(oldest)) {
                    Files.deleteIfExists(file);
                }
            } catch (IOException exception) {
                continue;
            }
        }
    }
}
