package com.kaizten.sheriff.infrastructure.hook;

import com.kaizten.sheriff.infrastructure.process.TemporaryFolders;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * What the components git already reported as changed looked like when a
 * turn began, per session.
 *
 * <p>Git cannot say which turn changed a file, and the Stop hook used to leave
 * that to the model: its message ended with "if this turn wrote no code, name
 * the errors and finish". A weaker model took that way out after writing code
 * ("no changes in this turn"), and a question asked in a repository with
 * earlier work in it was sent back to fix code nobody had asked about. The
 * turn hook records each changed component's fingerprint here when the user's
 * prompt arrives, and the Stop hook leaves alone every component whose
 * fingerprint is still the same: this turn did not touch it.
 *
 * <p>One file per session in the temporary directory, like {@link StopBlocks},
 * since every hook run is a separate process. A session with no record (no
 * turn hook installed, or a client that names no session) is checked as
 * before: every component git reports.
 */
public final class TurnStart {

    private static final String MEMORY_DIRECTORY = "sheriff-turn";
    private static final String DIGEST = "SHA-256";
    private static final String LINE_SEPARATOR = "\n";
    private static final String FIELD_SEPARATOR = "\t";
    private static final int FIELDS = 2;
    private static final int COMPONENT_FIELD = 0;
    private static final int FINGERPRINT_FIELD = 1;

    private final Path directory;

    /**
     * Records in the user's own folder of the system's temporary directory,
     * where records older than a week are pruned.
     */
    public TurnStart() {
        this(TemporaryFolders.forUser(MEMORY_DIRECTORY));
    }

    /**
     * Records in a chosen directory.
     *
     * @param directory where the records go
     */
    public TurnStart(Path directory) {
        this.directory = directory;
    }

    /**
     * Records what the changed components looked like as a turn begins,
     * replacing the previous turn's record. Best effort: a record that cannot
     * be written means the Stop hook checks every changed component.
     *
     * @param session the session, or empty when unknown
     * @param fingerprints each changed component's fingerprint
     */
    public void record(String session, Map<String, String> fingerprints) {
        if (session.isEmpty()) {
            return;
        }
        StringBuilder content = new StringBuilder();
        fingerprints.forEach((component, fingerprint) ->
                content.append(component).append(FIELD_SEPARATOR).append(fingerprint).append(LINE_SEPARATOR));
        try {
            Files.createDirectories(directory);
            TemporaryFolders.prune(directory, TemporaryFolders.MARKER_LIFETIME);
            Files.writeString(fileFor(session), content);
        } catch (IOException exception) {
            return;
        }
    }

    /**
     * What was recorded when this session's current turn began.
     *
     * @param session the session, or empty when unknown
     * @return each component's fingerprint, or nothing when there is no record
     */
    public Optional<Map<String, String>> recorded(String session) {
        if (session.isEmpty()) {
            return Optional.empty();
        }
        try {
            Map<String, String> fingerprints = new LinkedHashMap<>();
            for (String line : Files.readString(fileFor(session)).split(LINE_SEPARATOR)) {
                String[] fields = line.split(FIELD_SEPARATOR, FIELDS);
                if (fields.length == FIELDS) {
                    fingerprints.put(fields[COMPONENT_FIELD], fields[FINGERPRINT_FIELD]);
                }
            }
            return Optional.of(fingerprints);
        } catch (IOException exception) {
            return Optional.empty();
        }
    }

    /**
     * The record file for one session.
     *
     * @param session the session
     * @return its path
     */
    private Path fileFor(String session) {
        try {
            byte[] digest = MessageDigest.getInstance(DIGEST).digest(session.getBytes(StandardCharsets.UTF_8));
            return directory.resolve(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
