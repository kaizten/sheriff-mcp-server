package com.kaizten.sheriff.infrastructure.mcp;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Which version of this jar is running, as the build recorded it from the
 * {@code pom.xml}: what {@code --version} prints, what the MCP handshake
 * answers with, and what every task records, so a result can be traced back
 * to the code that produced it; and which commit it was built from, as a
 * version names every commit until the next release.
 */
public final class ServerVersion {

    private static final String RESOURCE = "/sheriff-mcp-version.properties";
    private static final String ENTRY = RESOURCE.substring(1);
    private static final String VERSION_KEY = "version";
    private static final String COMMIT_KEY = "commit";
    private static final String DESCRIBED = "%s (%s)";
    private static final String UNKNOWN = "unknown";
    private static final String UNFILTERED_MARKER = "${";
    private static final String ERROR_UTILITY_CLASS = "This is a utility class and cannot be instantiated.";

    /**
     * Refuses to be instantiated: every member here is static.
     */
    private ServerVersion() {
        throw new UnsupportedOperationException(ERROR_UTILITY_CLASS);
    }

    /**
     * The version of the running jar.
     *
     * <p>It is {@code unknown} rather than a guess when the file is missing or
     * was not filled in, as from classes compiled outside Maven: reporting a
     * number that was never built is what {@code 1.0.0}, written by hand in
     * the handshake, used to do.
     *
     * @return the version, or {@code unknown}
     */
    public static String current() {
        return recorded(VERSION_KEY);
    }

    /**
     * The commit the running jar was built from, abbreviated.
     *
     * <p>{@code unknown} for a build with no git or no repository, as in the
     * image's, and for classes compiled outside Maven.
     *
     * @return the commit, or {@code unknown}
     */
    public static String commit() {
        return recorded(COMMIT_KEY);
    }

    /**
     * The version and, when the build recorded it, the commit, as
     * {@code 0.1.10 (6ff458f)}: what tells two jars of one version apart.
     * {@code --version} prints the version alone, as the installers and the
     * workflows compare it with a release's.
     *
     * @return the version, with its commit when there is one
     */
    public static String described() {
        String commit = commit();
        return UNKNOWN.equals(commit) ? current() : String.format(DESCRIBED, current(), commit);
    }

    /**
     * The version another jar of these tools records, as a downloaded one
     * says it is.
     *
     * @param jar the jar's bytes
     * @return its version, or empty when it is no jar, records none, or the
     *     build did not fill it in
     */
    public static Optional<String> of(byte[] jar) {
        try (ZipInputStream entries = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry entry = entries.getNextEntry(); entry != null; entry = entries.getNextEntry()) {
                if (ENTRY.equals(entry.getName())) {
                    return Optional.of(valueIn(entries, VERSION_KEY)).filter(version -> !UNKNOWN.equals(version));
                }
            }
            return Optional.empty();
        } catch (IOException | IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * One value the build recorded.
     *
     * @param key its name in the version file
     * @return its value, or {@code unknown} when the file is missing, the
     *     value is empty, or the build did not fill it in
     */
    private static String recorded(String key) {
        try (InputStream stream = ServerVersion.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return UNKNOWN;
            }
            return valueIn(stream, key);
        } catch (IOException exception) {
            return UNKNOWN;
        }
    }

    /**
     * One value of a version file.
     *
     * @param stream the file's content
     * @param key its name in the file
     * @return its value, or {@code unknown} when it is empty or the build did
     *     not fill it in
     * @throws IOException when the file cannot be read
     */
    private static String valueIn(InputStream stream, String key) throws IOException {
        Properties properties = new Properties();
        properties.load(stream);
        String value = properties.getProperty(key, UNKNOWN).strip();
        return value.isEmpty() || value.contains(UNFILTERED_MARKER) ? UNKNOWN : value;
    }
}
