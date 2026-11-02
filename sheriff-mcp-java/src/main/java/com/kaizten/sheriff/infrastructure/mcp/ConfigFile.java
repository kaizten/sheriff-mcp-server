package com.kaizten.sheriff.infrastructure.mcp;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.UUID;

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
 *
 * <p>Claude Code's settings are written the way Claude Code writes them
 * ({@link #json}), so a reinstall changes only what it changes. Jackson's
 * default printer put every array on one line: a user's settings with some
 * 450 permission rules, one per line, came out of a reinstall as 51 lines,
 * the same content with the whole list on one.
 *
 * <p>An uninstall leaves each file as it was before the install
 * ({@link #installing}, {@link #uninstalling}): byte for byte when nothing
 * else in it changed since, and not there at all when the install created
 * it. Taking Sheriff's lines out alone left Codex's {@code hooks.json} and
 * Antigravity's settings behind as {@code {}}, and Antigravity's server list
 * reformatted (measured on 31 October).
 */
final class ConfigFile {

    private static final String TEMPORARY_SUFFIX = ".tmp";
    private static final String POSIX_VIEW = "posix";
    private static final String ERROR_UTILITY_CLASS = "This is a utility class and cannot be instantiated.";
    private static final String NOTHING_INSIDE = "";
    private static final String ORIGINALS_FOLDER = "originals";
    private static final String ABSENT_SUFFIX = ".absent";
    private static final ObjectMapper READER = new ObjectMapper();
    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE);
    private static final Set<PosixFilePermission> OWNER_FOLDER = EnumSet.of(PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE);
    private static final DefaultPrettyPrinter AS_CLAUDE_CODE_WRITES_IT = new DefaultPrettyPrinter(Separators.createDefaultInstance()
                    .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
                    .withObjectEmptySeparator(NOTHING_INSIDE)
                    .withArrayEmptySeparator(NOTHING_INSIDE))
            .withArrayIndenter(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);

    /**
     * Refuses to be instantiated: every member here is static.
     */
    private ConfigFile() {
        throw new UnsupportedOperationException(ERROR_UTILITY_CLASS);
    }

    /**
     * A JSON document as Claude Code writes its own settings: two spaces of
     * indent, {@code "key": value}, one array element per line, and
     * {@code []} and {@code {}} when empty, ending in a line break.
     *
     * @param json the mapper to write with
     * @param root the document
     * @return its text
     * @throws IOException when it cannot be written as JSON
     */
    static String json(ObjectMapper json, JsonNode root) throws IOException {
        return json.writer(AS_CLAUDE_CODE_WRITES_IT).writeValueAsString(root) + System.lineSeparator();
    }

    /**
     * Where the installers keep the files they changed as they were before:
     * beside the jar, in the folder it is installed into.
     *
     * @param jar the jar the installer sets up
     * @return that folder
     */
    static Path originalsBeside(Path jar) {
        return jar.toAbsolutePath().normalize().resolveSibling(ORIGINALS_FOLDER);
    }

    /**
     * Writes what an install puts into a file, keeping the file as it was
     * the first time an install changed it.
     *
     * <p>Only the first time: an install run again finds Sheriff's own lines
     * there, and the file to go back to is the one from before any of them.
     * A file that did not exist is recorded as such. The copies are readable
     * by their owner alone, as these files can hold tokens.
     *
     * @param file the file, or a symbolic link to it
     * @param content its new content
     * @param originals where the files as they were are kept
     * @throws IOException when it cannot be written
     */
    static void installing(Path file, String content, Path originals) throws IOException {
        Path target = realFile(file);
        Path record = recordOf(target, originals);
        Path absent = absenceOf(record);
        if (!Files.exists(record) && !Files.exists(absent)) {
            remember(target, record, absent, originals);
        }
        replace(target, content);
    }

    /**
     * Writes a file with Sheriff's lines taken out, as it was before the
     * install when nothing else in it changed since.
     *
     * <p>Taking the lines out gives back the content, not the file: the
     * installers write JSON their own way, so a file whose user had
     * formatted it otherwise came back reformatted, and a file the install
     * had created came back as an empty {@code {}}. So the file as it was is
     * written back byte for byte when what is left means the same, and a file
     * left with nothing in it is removed, unless it is a symbolic link: one
     * the install created, also when what is left is JSON with nothing in
     * its objects, as Antigravity's {@code {"mcpServers": {}}}. When
     * the user changed something else meanwhile, only Sheriff's lines go.
     *
     * @param file the file, or a symbolic link to it
     * @param content its content with Sheriff's lines taken out
     * @param originals where the files as they were are kept
     * @throws IOException when it cannot be written
     */
    static void uninstalling(Path file, String content, Path originals) throws IOException {
        Path target = realFile(file);
        Path record = recordOf(target, originals);
        Path absent = absenceOf(record);
        if (Files.exists(record) && same(Files.readString(record, StandardCharsets.UTF_8), content)) {
            replace(target, Files.readAllBytes(record));
        } else if ((empty(content) || Files.exists(absent) && hollow(content)) && !Files.isSymbolicLink(file)) {
            Files.deleteIfExists(target);
        } else {
            replace(target, content);
        }
        Files.deleteIfExists(record);
        Files.deleteIfExists(absent);
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
        replace(file, content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Replaces a file's content with these bytes, through a temporary file
     * beside it, keeping its permissions.
     *
     * @param file the file, or a symbolic link to it
     * @param content its new content
     * @throws IOException when it cannot be written, or is a link to nothing
     */
    private static void replace(Path file, byte[] content) throws IOException {
        Path target = realFile(file);
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + TEMPORARY_SUFFIX);
        Files.deleteIfExists(temporary);
        Optional<Set<PosixFilePermission>> permissions = permissionsOf(target);
        if (permissions.isEmpty()) {
            Files.write(temporary, content);
        } else {
            Set<PosixFilePermission> writable = EnumSet.of(PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE);
            writable.addAll(permissions.get());
            Files.createFile(temporary, PosixFilePermissions.asFileAttribute(writable));
            Files.setPosixFilePermissions(temporary, writable);
            Files.write(temporary, content);
            Files.setPosixFilePermissions(temporary, permissions.get());
        }
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * The file a path names: where it points, for a symbolic link.
     *
     * @param file the path
     * @return the file
     * @throws IOException when it is a link to nothing
     */
    private static Path realFile(Path file) throws IOException {
        return Files.isSymbolicLink(file) ? file.toRealPath() : file;
    }

    /**
     * Keeps a file as it is now, or a note that it does not exist.
     *
     * @param target the file
     * @param record where to keep it
     * @param absent the note to leave when it does not exist
     * @param originals the folder they go in
     * @throws IOException when they cannot be written
     */
    private static void remember(Path target, Path record, Path absent, Path originals) throws IOException {
        Files.createDirectories(originals);
        boolean posix = originals.getFileSystem().supportedFileAttributeViews().contains(POSIX_VIEW);
        if (posix) {
            Files.setPosixFilePermissions(originals, OWNER_FOLDER);
        }
        Path kept = Files.exists(target) ? record : absent;
        if (posix) {
            Files.createFile(kept, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
        } else {
            Files.createFile(kept);
        }
        if (kept.equals(record)) {
            Files.write(record, Files.readAllBytes(target));
        }
    }

    /**
     * Where a file's copy as it was is kept: named by a digest of its path.
     *
     * @param target the file
     * @param originals the folder they go in
     * @return that copy's path
     */
    private static Path recordOf(Path target, Path originals) {
        byte[] path = target.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8);
        return originals.resolve(UUID.nameUUIDFromBytes(path).toString());
    }

    /**
     * The note that a file did not exist, beside where its copy would be.
     *
     * @param record where its copy would be
     * @return that note's path
     */
    private static Path absenceOf(Path record) {
        return record.resolveSibling(record.getFileName() + ABSENT_SUFFIX);
    }

    /**
     * Whether two contents say the same: the same text, line ends and the
     * end of the file aside, or the same JSON however it is laid out.
     *
     * @param original the file as it was
     * @param content what is left of it now
     * @return {@code true} when they do
     */
    private static boolean same(String original, String content) {
        if (original.stripTrailing().equals(content.stripTrailing())) {
            return true;
        }
        try {
            JsonNode before = READER.readTree(original);
            return !before.isMissingNode() && before.equals(READER.readTree(content));
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * Whether nothing is left in a file: no text, or an empty JSON object or
     * list.
     *
     * @param content its content
     * @return {@code true} when nothing is
     */
    private static boolean empty(String content) {
        if (content.isBlank()) {
            return true;
        }
        try {
            JsonNode root = READER.readTree(content);
            return root.isContainerNode() && root.isEmpty();
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * Whether JSON holds nothing but empty objects and lists.
     *
     * @param content the text
     * @return {@code true} when it is such JSON
     */
    private static boolean hollow(String content) {
        try {
            return hollow(READER.readTree(content));
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * Whether a JSON node is an object or a list with nothing but empty
     * objects and lists inside.
     *
     * @param node the node
     * @return {@code true} when it is
     */
    private static boolean hollow(JsonNode node) {
        if (!node.isContainerNode()) {
            return false;
        }
        for (JsonNode child : node) {
            if (!hollow(child)) {
                return false;
            }
        }
        return true;
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
