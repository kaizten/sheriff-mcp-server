package com.kaizten.sheriff.infrastructure.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * Writes an assistant's configuration file whole, where it really is, and
 * readable by no one more than before.
 *
 * <p>Three things the installers need and used to do each on its own. The
 * content goes to a file of its own first and is then moved over the old
 * one, so an interrupted write never leaves half a file behind. A file that
 * is a symbolic link is written where it points: user settings often live in
 * a dotfiles repository behind such a link, and moving the new file onto the
 * link replaced it with a copy the repository no longer saw.
 *
 * <p>And the new file keeps the old one's permissions. These files can hold
 * tokens (an {@code env} in Claude Code's settings, a server's environment
 * in Codex's {@code config.toml}), and the file written beside them was
 * created with the default mask: a settings file only its owner could read
 * came out of an install readable by every user of the machine. The copy is
 * created with those permissions before anything is written into it, so
 * there is no moment when it is more open than the original.
 */
final class ConfigFile {

    private static final String TEMPORARY_SUFFIX = ".tmp";
    private static final String POSIX_VIEW = "posix";
    private static final String ERROR_UTILITY_CLASS = "This is a utility class and cannot be instantiated.";

    /**
     * Refuses to be instantiated: every member here is static.
     */
    private ConfigFile() {
        throw new UnsupportedOperationException(ERROR_UTILITY_CLASS);
    }

    /**
     * Replaces a file's content, through a temporary file beside it, keeping
     * its permissions.
     *
     * @param file the file, or a symbolic link to it
     * @param content its new content
     * @throws IOException when it cannot be written, or is a link to nothing
     */
    static void replace(Path file, String content) throws IOException {
        Path target = Files.isSymbolicLink(file) ? file.toRealPath() : file;
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + TEMPORARY_SUFFIX);
        Files.deleteIfExists(temporary);
        Optional<Set<PosixFilePermission>> permissions = permissionsOf(target);
        if (permissions.isEmpty()) {
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
        } else {
            Set<PosixFilePermission> writable = EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            writable.addAll(permissions.get());
            Files.createFile(temporary, PosixFilePermissions.asFileAttribute(writable));
            Files.setPosixFilePermissions(temporary, writable);
            Files.writeString(temporary, content, StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(temporary, permissions.get());
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * The permissions a file has now.
     *
     * <p>The copy is written with its owner able to write it whatever they
     * are, and given them exactly once it is written: a settings file its
     * owner had made read-only could not be written otherwise.
     *
     * @param file the file
     * @return them, or empty when it does not exist yet or the file system
     *     has no POSIX permissions, as on Windows
     * @throws IOException when they cannot be read
     */
    private static Optional<Set<PosixFilePermission>> permissionsOf(Path file) throws IOException {
        if (!Files.exists(file) || !file.getFileSystem().supportedFileAttributeViews().contains(POSIX_VIEW)) {
            return Optional.empty();
        }
        return Optional.of(Files.getPosixFilePermissions(file));
    }
}
