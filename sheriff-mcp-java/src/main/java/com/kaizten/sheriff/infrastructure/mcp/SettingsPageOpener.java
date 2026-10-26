package com.kaizten.sheriff.infrastructure.mcp;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * What opens a settings page, as {@link SettingsPage#open} does: the seam a
 * test opens a page that cannot be opened through.
 */
@FunctionalInterface
public interface SettingsPageOpener {

    /**
     * Opens a page for a project's settings and starts serving it.
     *
     * @param environment the environment the tools were started with
     * @param project the project whose file the page changes
     * @param idleLimit how long it waits unused before it closes
     * @return the page, serving
     * @throws IOException when it cannot be opened
     */
    SettingsPage open(Map<String, String> environment, Path project, Duration idleLimit) throws IOException;
}
