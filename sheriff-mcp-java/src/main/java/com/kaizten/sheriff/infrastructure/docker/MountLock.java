package com.kaizten.sheriff.infrastructure.docker;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * One Sheriff at a time per mounted directory, across threads and processes.
 *
 * <p>Sheriff keeps its state in three files at the root of what it mounts,
 * and its {@code fix} reads the state the previous {@code test} left there.
 * Two runs on the same mount, from two MCP sessions on sibling single-module
 * projects that share a parent, or a hook firing during a repair, clobbered
 * each other's state: a repair that read someone else's findings, an analysis
 * whose record another run had already deleted.
 *
 * <p>The lock is reentrant, so a whole {@code test}, {@code fix}, {@code test}
 * sequence can hold it while each run inside it asks again. Between processes
 * it is a file lock, kept in the temporary directory rather than in the
 * mount, where the scope check would read it as a change; the operating
 * system releases it if the holder dies. Best effort: a lock file that cannot
 * be created means running unlocked, as before, rather than not running.
 */
public final class MountLock {

    private static final Map<Path, ReentrantLock> IN_PROCESS = new ConcurrentHashMap<>();
    private static final Map<Path, FileChannel> CHANNELS = new ConcurrentHashMap<>();
    private static final Map<Path, FileLock> BETWEEN_PROCESSES = new ConcurrentHashMap<>();
    private static final String TEMPORARY_DIRECTORY = "java.io.tmpdir";
    private static final String LOCK_DIRECTORY = "sheriff-locks";
    private static final String LOCK_SUFFIX = ".lock";
    private static final String DIGEST = "SHA-256";
    private static final int FIRST_HOLD = 1;
    private static final String ERROR_UTILITY_CLASS = "This is a utility class and cannot be instantiated.";

    /**
     * Refuses to be instantiated: every member here is static.
     */
    private MountLock() {
        throw new UnsupportedOperationException(ERROR_UTILITY_CLASS);
    }

    /**
     * Runs an action while holding the mount's lock.
     *
     * @param mount the directory Sheriff mounts
     * @param action what to run
     * @param <T> what it returns
     * @return what the action returned
     */
    public static <T> T holding(Path mount, Supplier<T> action) {
        Path key = mount.toAbsolutePath().normalize();
        ReentrantLock lock = IN_PROCESS.computeIfAbsent(key, path -> new ReentrantLock());
        lock.lock();
        try {
            if (lock.getHoldCount() == FIRST_HOLD) {
                acquire(key);
            }
            try {
                return action.get();
            } finally {
                if (lock.getHoldCount() == FIRST_HOLD) {
                    release(key);
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Takes the file lock for a mount, waiting for any other process that
     * holds it.
     *
     * @param mount the mount
     */
    private static void acquire(Path mount) {
        try {
            Path file = lockFileFor(mount);
            Files.createDirectories(file.getParent());
            FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            CHANNELS.put(mount, channel);
            BETWEEN_PROCESSES.put(mount, channel.lock());
        } catch (IOException | NoSuchAlgorithmException exception) {
            release(mount);
        }
    }

    /**
     * Gives the file lock for a mount back.
     *
     * @param mount the mount
     */
    private static void release(Path mount) {
        try {
            FileLock lock = BETWEEN_PROCESSES.remove(mount);
            if (lock != null) {
                lock.release();
            }
            FileChannel channel = CHANNELS.remove(mount);
            if (channel != null) {
                channel.close();
            }
        } catch (IOException exception) {
            return;
        }
    }

    /**
     * Where the lock file for a mount lives: one per mount, named after a
     * digest of its path so any path makes a valid file name.
     *
     * @param mount the mount
     * @return the lock file's path
     * @throws NoSuchAlgorithmException never, SHA-256 is always there
     */
    static Path lockFileFor(Path mount) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance(DIGEST).digest(mount.toString().getBytes(StandardCharsets.UTF_8));
        return Path.of(System.getProperty(TEMPORARY_DIRECTORY), LOCK_DIRECTORY,
                HexFormat.of().formatHex(digest) + LOCK_SUFFIX);
    }
}
