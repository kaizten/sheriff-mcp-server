package com.kaizten.sheriff.infrastructure.config;

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
import java.util.Properties;

/**
 * The settings written into a project's file in a way the user approves or
 * performs ({@link Settings#changeAsChosen}): the {@code sheriff_config} tool
 * asked through an MCP client, which the installers leave to the user to
 * allow, and the settings page. Not {@code --config NAME=VALUE --project} nor
 * {@code --call sheriff_config}: a model's shell runs those as a person's
 * does, so a person who uses them is in the place of one who edits the file.
 *
 * <p>The Stop hook sends a turn back when a project's settings check less than
 * they did, as an assistant can write that file like any other. A change made
 * the user's way is theirs, so the hook leaves it alone while the line still
 * holds the value written here. A line changed by any other means afterwards
 * is not, even in the same file. Kept per user in the
 * temporary directory, a file per settings file, and never pruned: a record
 * gone would send back a change the user made.
 */
public final class ChosenSettings {

    private static final String FOLDER = "sheriff-settings";
    private static final String DIGEST = "SHA-256";
    private static final String SUFFIX = ".properties";
    private static final String NO_COMMENTS = null;
    private final Path directory;

    /**
     * Records in the user's own folder of the system's temporary directory.
     */
    public ChosenSettings() {
        this(TemporaryFolders.forUser(FOLDER));
    }

    /**
     * Records in a chosen directory.
     *
     * @param directory where the records go
     */
    public ChosenSettings(Path directory) {
        this.directory = directory;
    }

    /**
     * Records that a setting was given a value in a file the user's way.
     * Best effort: a record that cannot be written means the Stop hook asks
     * for the change to be committed.
     *
     * @param file the settings file written
     * @param name the setting's name, as the file writes it
     * @param value its value, empty when it was put back to its default
     */
    public void record(Path file, String name, String value) {
        Properties chosen = read(file);
        chosen.setProperty(name, value);
        try {
            Files.createDirectories(directory);
            try (Writer writer = Files.newBufferedWriter(recordOf(file), StandardCharsets.UTF_8)) {
                chosen.store(writer, NO_COMMENTS);
            }
        } catch (IOException exception) {
            return;
        }
    }

    /**
     * Whether a line of a file holds the value the user's way gave it.
     *
     * @param file the settings file
     * @param name the key, as the file writes it
     * @param value its value now, empty when it is not set
     * @return {@code true} when it was last written the user's way, with
     *     that value
     */
    public boolean chose(Path file, String name, String value) {
        String recorded = read(file).getProperty(name);
        return recorded != null && recorded.strip().equals(value.strip());
    }

    /**
     * What was recorded for one file.
     *
     * @param file the settings file
     * @return its settings as written the user's way, none when there is no
     *     record
     */
    private Properties read(Path file) {
        Properties chosen = new Properties();
        Path record = recordOf(file);
        if (!Files.isRegularFile(record)) {
            return chosen;
        }
        try (Reader reader = Files.newBufferedReader(record, StandardCharsets.UTF_8)) {
            chosen.load(reader);
        } catch (IOException | IllegalArgumentException exception) {
            return new Properties();
        }
        return chosen;
    }

    /**
     * The record of one settings file, named by a digest of its real path so
     * that the hook, which has the path from git, finds what
     * {@link Settings#change} wrote.
     *
     * @param file the settings file
     * @return the record's path
     */
    private Path recordOf(Path file) {
        String path;
        try {
            path = file.toRealPath().toString();
        } catch (IOException exception) {
            path = file.toAbsolutePath().normalize().toString();
        }
        try {
            byte[] digest = MessageDigest.getInstance(DIGEST).digest(path.getBytes(StandardCharsets.UTF_8));
            return directory.resolve(HexFormat.of().formatHex(digest) + SUFFIX);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
