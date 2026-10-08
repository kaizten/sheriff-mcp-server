package com.kaizten.sheriff.infrastructure.hook;

import com.kaizten.sheriff.infrastructure.process.TemporaryFolders;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * How many times in a row the Stop hook has sent one session back to work,
 * and what the sources looked like the last time.
 *
 * <p>Claude Code only says whether the stop follows a block
 * ({@code stop_hook_active}), not how many came before it, and every hook run
 * is a separate process. So the count is a file per session in the temporary
 * directory, like {@link GateMemory}'s markers. A session the payload does not
 * name is never counted, which keeps the old rule for it: block once.
 */
public final class StopBlocks {

    private static final String MEMORY_DIRECTORY = "sheriff-stop";
    private static final String DIGEST = "SHA-256";
    private static final int NONE = 0;
    private static final String SEPARATOR = "\n";
    private static final int COUNT_LINE = 0;
    private static final int SOURCES_LINE = 1;
    private static final int STALLED_LINE = 2;
    private static final int LINES = 3;
    private static final String STALLED = "stalled";
    private static final String NOT_STALLED = "";

    private final Path directory;

    /**
     * Counts in the user's own folder of the system's temporary directory,
     * where counts older than a week are pruned.
     */
    public StopBlocks() {
        this(TemporaryFolders.forUser(MEMORY_DIRECTORY));
    }

    /**
     * Counts in a chosen directory.
     *
     * @param directory where the counts go
     */
    public StopBlocks(Path directory) {
        this.directory = directory;
    }

    /**
     * How many blocks in a row this session has had.
     *
     * @param session the Claude Code session, or empty when unknown
     * @return that number, zero when unknown or unreadable
     */
    public int count(String session) {
        try {
            return Integer.parseInt(lines(session)[COUNT_LINE].strip());
        } catch (NumberFormatException exception) {
            return NONE;
        }
    }

    /**
     * The signature of the sources when this session was last blocked.
     *
     * @param session the Claude Code session, or empty when unknown
     * @return that signature, or the empty string when there is none
     */
    public String lastSources(String session) {
        return lines(session)[SOURCES_LINE];
    }

    /**
     * Whether the last block was already sent back for a stop with nothing
     * changed since the one before.
     *
     * @param session the Claude Code session, or empty when unknown
     * @return {@code true} when the session has stalled once already
     */
    public boolean stalled(String session) {
        return STALLED.equals(lines(session)[STALLED_LINE]);
    }

    /**
     * What is on record for a session, as its three lines.
     *
     * @param session the session
     * @return the count, the signature and whether it stalled, all empty when
     *     unknown
     */
    private String[] lines(String session) {
        String[] empty = {NOT_STALLED, NOT_STALLED, NOT_STALLED};
        if (session.isEmpty()) {
            return empty;
        }
        try {
            String[] read = Files.readString(fileFor(session)).split(SEPARATOR, LINES);
            return read.length == LINES ? read : empty;
        } catch (IOException exception) {
            return empty;
        }
    }

    /**
     * Records one more block for this session. Best effort: a count that
     * cannot be written reads back as zero, which only means one more block.
     *
     * @param session the Claude Code session, or empty when unknown
     * @param sources the signature of the sources at this block
     * @param stalledNow whether nothing changed since the block before
     */
    public void recordBlock(String session, String sources, boolean stalledNow) {
        if (session.isEmpty()) {
            return;
        }
        int next = count(session) + 1;
        try {
            Files.createDirectories(directory);
            TemporaryFolders.prune(directory, TemporaryFolders.MARKER_LIFETIME);
            Files.writeString(fileFor(session),
                    next + SEPARATOR + sources + SEPARATOR + (stalledNow ? STALLED : NOT_STALLED));
        } catch (IOException exception) {
            return;
        }
    }

    /**
     * Forgets this session's count, when a turn ends or starts afresh.
     *
     * @param session the Claude Code session, or empty when unknown
     */
    public void reset(String session) {
        if (session.isEmpty()) {
            return;
        }
        try {
            Files.deleteIfExists(fileFor(session));
        } catch (IOException exception) {
            return;
        }
    }

    /**
     * The count file for one session.
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
