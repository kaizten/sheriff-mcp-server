package com.kaizten.sheriff.infrastructure.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Which version of this jar is running, as the build recorded it from the
 * {@code pom.xml}: what {@code --version} prints, what the MCP handshake
 * answers with, and what every task records, so a result can be traced back
 * to the code that produced it.
 */
public final class ServerVersion {

    private static final String RESOURCE = "/sheriff-mcp-version.properties";
    private static final String VERSION_KEY = "version";
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
        try (InputStream stream = ServerVersion.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                return UNKNOWN;
            }
            Properties properties = new Properties();
            properties.load(stream);
            String version = properties.getProperty(VERSION_KEY, UNKNOWN).strip();
            return version.isEmpty() || version.contains(UNFILTERED_MARKER) ? UNKNOWN : version;
        } catch (IOException exception) {
            return UNKNOWN;
        }
    }
}
