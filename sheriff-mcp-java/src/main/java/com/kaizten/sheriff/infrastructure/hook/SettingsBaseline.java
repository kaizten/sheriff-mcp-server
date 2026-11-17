package com.kaizten.sheriff.infrastructure.hook;

import com.kaizten.sheriff.infrastructure.process.TemporaryFolders;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

/**
 * What the Stop hook compares a project's settings with: what they held when
 * the turn began, and, while a loosening it sent back is still there, what
 * they held before it.
 *
 * <p>Compared with {@code HEAD}, a model that committed the line it loosened
 * walked through, as nothing differed from the last commit any more. So the
 * turn hook records the commit the repository was at and the settings files
 * that differed from it, and the Stop hook compares with that. A person who
 * commits a looser setting between turns, or leaves one uncommitted, has it
 * in the next turn's record and is not sent anything.
 *
 * <p>A turn can still end with a loosening in place, when the stops sent back
 * run out. The record it was compared with is then held for the repository,
 * whatever session or turn comes next, so it is never taken as the next
 * starting point: every later stop is sent back over it again, until it is put
 * back or the user makes it with {@code sheriff_config}, and only a stop that
 * finds nothing looser lets the record go. Turn records are pruned after a
 * week, as the hooks' other markers; a held one never is.
 */
public final class SettingsBaseline {

    private static final String TURN_FOLDER = "sheriff-settings-turn";
    private static final String HELD_FOLDER = "sheriff-settings-held";
    private static final String TURNS = "turns";
    private static final String HELD = "held";
    private static final String DIGEST = "SHA-256";
    private static final String COMMIT_KEY = "commit";
    private static final String FILE_KEY = "file.%d";
    private static final String CONTENT_KEY = "content.%d";
    private static final String NOTHING = "";
    private static final String NO_COMMENTS = null;
    private static final int FIRST = 0;
    private final Path turns;
    private final Path held;

    /**
     * Records in the user's own folders of the system's temporary directory.
     */
    public SettingsBaseline() {
        this(TemporaryFolders.forUser(TURN_FOLDER), TemporaryFolders.forUser(HELD_FOLDER));
    }

    /**
     * Records in a chosen directory.
     *
     * @param directory where the records go
     */
    public SettingsBaseline(Path directory) {
        this(directory.resolve(TURNS), directory.resolve(HELD));
    }

    /**
     * Records in two chosen directories.
     *
     * @param turns where each session's turn records go, pruned
     * @param held where the records held for a repository go, never pruned
     */
    private SettingsBaseline(Path turns, Path held) {
        this.turns = turns;
        this.held = held;
    }

    /**
     * Records what the settings held as a session's turn begins. Best effort:
     * with no record the Stop hook compares with {@code HEAD}.
     *
     * @param session the session, or empty when unknown
     * @param snapshot what they held
     */
    public void recordTurn(String session, SettingsSnapshot snapshot) {
        if (session.isEmpty()) {
            return;
        }
        write(turns.resolve(digestOf(session)), snapshot);
        TemporaryFolders.prune(turns, TemporaryFolders.MARKER_LIFETIME);
    }

    /**
     * What the settings held when a session's current turn began.
     *
     * @param session the session, or empty when unknown
     * @return that record, or empty when there is none
     */
    public Optional<SettingsSnapshot> turn(String session) {
        return session.isEmpty() ? Optional.empty() : read(turns.resolve(digestOf(session)));
    }

    /**
     * Holds a record for a repository, unless one is held already, which is
     * older and so stays.
     *
     * @param repository the folder Sheriff mounts
     * @param snapshot the record a loosening was found against
     */
    public void hold(Path repository, SettingsSnapshot snapshot) {
        if (heldFor(repository).isEmpty()) {
            write(held.resolve(digestOf(repository.toAbsolutePath().normalize().toString())), snapshot);
        }
    }

    /**
     * The record held for a repository.
     *
     * @param repository the folder Sheriff mounts
     * @return that record, or empty when none is held
     */
    public Optional<SettingsSnapshot> heldFor(Path repository) {
        return read(held.resolve(digestOf(repository.toAbsolutePath().normalize().toString())));
    }

    /**
     * Lets a repository's held record go, once nothing is looser than it.
     *
     * @param repository the folder Sheriff mounts
     */
    public void release(Path repository) {
        try {
            Files.deleteIfExists(held.resolve(digestOf(repository.toAbsolutePath().normalize().toString())));
        } catch (IOException exception) {
            return;
        }
    }

    /**
     * Writes a record, best effort.
     *
     * @param file the record's file
     * @param snapshot what it holds
     */
    private static void write(Path file, SettingsSnapshot snapshot) {
        Properties record = new Properties();
        record.setProperty(COMMIT_KEY, snapshot.commit());
        int index = FIRST;
        for (Map.Entry<Path, String> entry : snapshot.contents().entrySet()) {
            record.setProperty(String.format(FILE_KEY, index), entry.getKey().toString());
            record.setProperty(String.format(CONTENT_KEY, index), entry.getValue());
            index++;
        }
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                record.store(writer, NO_COMMENTS);
            }
        } catch (IOException exception) {
            return;
        }
    }

    /**
     * Reads a record.
     *
     * @param file the record's file
     * @return what it holds, or empty when there is none or it cannot be read
     */
    private static Optional<SettingsSnapshot> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        Properties record = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            record.load(reader);
        } catch (IOException | IllegalArgumentException exception) {
            return Optional.empty();
        }
        Map<Path, String> contents = new LinkedHashMap<>();
        for (int index = FIRST; record.containsKey(String.format(FILE_KEY, index)); index++) {
            contents.put(Path.of(record.getProperty(String.format(FILE_KEY, index))),
                    record.getProperty(String.format(CONTENT_KEY, index), NOTHING));
        }
        return Optional.of(new SettingsSnapshot(record.getProperty(COMMIT_KEY, NOTHING), contents));
    }

    /**
     * The name a record's file takes, so that no session id or path reaches
     * a file name.
     *
     * @param key the session, or the repository's path
     * @return the hexadecimal digest of it
     */
    private static String digestOf(String key) {
        try {
            byte[] digest = MessageDigest.getInstance(DIGEST).digest(key.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
