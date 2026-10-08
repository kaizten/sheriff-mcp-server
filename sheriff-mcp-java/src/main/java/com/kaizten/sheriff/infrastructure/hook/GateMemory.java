package com.kaizten.sheriff.infrastructure.hook;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Which components the gate has already warned about, per Claude Code
 * session.
 *
 * <p>The gate blocks the first edit to a component that already fails, so the
 * model learns about the errors before writing on top of them. It used to
 * block every edit after that too, until the component was clean, and that
 * left no way to clean it by hand: fixing an error is an edit. A real session
 * ended with Sheriff's own repairs applied and every manual fix refused. Once
 * warned, the model's edits go through; the Stop hook is what makes sure the
 * turn does not end with the errors still there.
 *
 * <p>Each warning is a marker file named after a digest of the session and
 * the component, in the temporary directory, because every hook run is a
 * separate process. A session the payload does not name is never remembered,
 * which is the old behavior.
 */
public final class GateMemory {

    private static final String TEMPORARY_DIRECTORY = "java.io.tmpdir";
    private static final String MEMORY_DIRECTORY = "sheriff-gate";
    private static final String DIGEST = "SHA-256";
    private static final String SEPARATOR = "\n";

    private final Path directory;

    /**
     * Remembers warnings in the system's temporary directory.
     */
    public GateMemory() {
        this(Path.of(System.getProperty(TEMPORARY_DIRECTORY), MEMORY_DIRECTORY));
    }

    /**
     * Remembers warnings in a chosen directory.
     *
     * @param directory where the markers go
     */
    public GateMemory(Path directory) {
        this.directory = directory;
    }

    /**
     * Whether this session was already warned about this component.
     *
     * @param session the Claude Code session, or empty when unknown
     * @param component the component
     * @return {@code true} when a warning was recorded
     */
    public boolean warned(String session, String component) {
        return !session.isEmpty() && Files.exists(markerFor(session, component));
    }

    /**
     * Records that this session has been warned about this component. Best
     * effort: a marker that cannot be written means the next edit is blocked
     * again, as before.
     *
     * @param session the Claude Code session, or empty when unknown
     * @param component the component
     */
    public void remember(String session, String component) {
        if (session.isEmpty()) {
            return;
        }
        try {
            Files.createDirectories(directory);
            Files.createFile(markerFor(session, component));
        } catch (FileAlreadyExistsException exception) {
            return;
        } catch (IOException exception) {
            return;
        }
    }

    /**
     * The marker file for one session and component.
     *
     * @param session the session
     * @param component the component
     * @return its path
     */
    private Path markerFor(String session, String component) {
        try {
            byte[] digest = MessageDigest.getInstance(DIGEST)
                    .digest((session + SEPARATOR + component).getBytes(StandardCharsets.UTF_8));
            return directory.resolve(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
