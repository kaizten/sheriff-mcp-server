package com.kaizten.sheriff.infrastructure.hook;

import com.kaizten.sheriff.infrastructure.process.TemporaryFolders;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 *
 * <p>It also keeps which components the gate refused an edit to during the
 * turn, as the Stop hook checks those too. A session whose only edit the gate
 * refused changed nothing, so it had nothing to check: a model stopped there
 * and asked the user whether to fix the errors, and the turn ended with them.
 */
public final class TurnStart {

    private static final String MEMORY_DIRECTORY = "sheriff-turn";
    private static final String DIGEST = "SHA-256";
    private static final String LINE_SEPARATOR = "\n";
    private static final String FIELD_SEPARATOR = "\t";
    private static final int FIELDS = 2;
    private static final int COMPONENT_FIELD = 0;
    private static final int FINGERPRINT_FIELD = 1;
    private static final String REFUSED_SUFFIX = ".refused";

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
     * replacing the previous turn's record and forgetting the components the
     * gate refused in it. Best effort: a record that cannot be written means
     * the Stop hook checks every changed component.
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
            Files.deleteIfExists(refusedFileFor(session));
        } catch (IOException exception) {
            return;
        }
    }

    /**
     * Records that the gate refused an edit to a component during the
     * current turn. Best effort: a record that cannot be written means the
     * Stop hook checks only what the turn changed.
     *
     * @param session the session, or empty when unknown
     * @param component the component the edit was in
     */
    public void refused(String session, String component) {
        if (session.isEmpty() || refusedThisTurn(session).contains(component)) {
            return;
        }
        try {
            Files.createDirectories(directory);
            Files.writeString(refusedFileFor(session), component + LINE_SEPARATOR,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            return;
        }
    }

    /**
     * The components the gate refused an edit to since this session's
     * current turn began.
     *
     * @param session the session, or empty when unknown
     * @return those components, empty when there are none or no record
     */
    public Set<String> refusedThisTurn(String session) {
        Set<String> components = new LinkedHashSet<>();
        if (session.isEmpty()) {
            return components;
        }
        try {
            for (String line : Files.readString(refusedFileFor(session)).split(LINE_SEPARATOR)) {
                if (!line.isBlank()) {
                    components.add(line.strip());
                }
            }
        } catch (IOException exception) {
            return components;
        }
        return components;
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
        return directory.resolve(digestOf(session));
    }

    /**
     * The file of the components the gate refused, for one session.
     *
     * @param session the session
     * @return its path
     */
    private Path refusedFileFor(String session) {
        return directory.resolve(digestOf(session) + REFUSED_SUFFIX);
    }

    /**
     * The name a session's files take, so that no session id reaches a path.
     *
     * @param session the session
     * @return the hexadecimal digest of the session
     */
    private static String digestOf(String session) {
        try {
            byte[] digest = MessageDigest.getInstance(DIGEST).digest(session.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
