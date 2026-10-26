package com.kaizten.sheriff.infrastructure.mcp;

import java.io.IOException;
import java.util.Optional;

/**
 * Where the published releases of these tools are: which one is the newest,
 * and the files each one carries.
 */
public interface ReleaseSource {

    /**
     * The newest release that is not a prerelease.
     *
     * @return its version, as {@code 0.1.8}, or empty when it cannot be told
     * @throws IOException when the place cannot be reached
     */
    Optional<String> latest() throws IOException;

    /**
     * One file a release carries.
     *
     * @param version the release, as {@code 0.1.8}
     * @param file the file's name, as {@code sheriff-mcp.jar}
     * @return its bytes
     * @throws IOException when it cannot be downloaded
     */
    byte[] download(String version, String file) throws IOException;
}
