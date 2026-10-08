package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * How every installer writes an assistant's configuration: whole, where a
 * link points, and readable by no one more than before.
 */
final class ConfigFileTest {

    @TempDir
    Path home;

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Windows has no POSIX permissions")
    @DisplayName("a settings file only its owner could read stays that way: it can hold tokens")
    void keepsTheOwnerOnlyPermissions() throws Exception {
        Path settings = home.resolve(".claude/settings.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "{\"env\": {\"TOKEN\": \"secret\"}}");
        Files.setPosixFilePermissions(settings, PosixFilePermissions.fromString("rw-------"));

        ConfigFile.replace(settings, "{\"env\": {\"TOKEN\": \"secret\"}, \"hooks\": {}}");

        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(settings)));
        assertTrue(Files.readString(settings).contains("hooks"));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Windows has no POSIX permissions")
    @DisplayName("a file its owner made read-only is still written, and left read-only")
    void writesAReadOnlyFileAndLeavesItReadOnly() throws Exception {
        Path config = home.resolve(".codex/config.toml");
        Files.createDirectories(config.getParent());
        Files.writeString(config, "model = \"x\"\n");
        Files.setPosixFilePermissions(config, PosixFilePermissions.fromString("r--------"));

        ConfigFile.replace(config, "model = \"y\"\n");

        assertEquals("r--------", PosixFilePermissions.toString(Files.getPosixFilePermissions(config)));
        assertEquals("model = \"y\"\n", Files.readString(config));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "creating a symbolic link needs privileges on Windows")
    @DisplayName("a settings file behind a link is written where it points, and the link is kept")
    void writesThroughALink() throws Exception {
        Path real = Files.createDirectories(home.resolve("dotfiles")).resolve("settings.json");
        Files.writeString(real, "{}");
        Path link = Files.createDirectories(home.resolve(".claude")).resolve("settings.json");
        Files.createSymbolicLink(link, real);

        ConfigFile.replace(link, "{\"hooks\": {}}");

        assertTrue(Files.isSymbolicLink(link));
        assertEquals("{\"hooks\": {}}", Files.readString(real));
    }

    @Test
    @DisplayName("a file that is not there yet is created, with its folder, and nothing is left beside it")
    void createsAFileAndItsFolder() throws Exception {
        Path agents = home.resolve(".codex/AGENTS.md");

        ConfigFile.replace(agents, "order of work\n");

        assertEquals("order of work\n", Files.readString(agents));
        assertFalse(Files.exists(home.resolve(".codex/AGENTS.md.tmp")));
    }
}
