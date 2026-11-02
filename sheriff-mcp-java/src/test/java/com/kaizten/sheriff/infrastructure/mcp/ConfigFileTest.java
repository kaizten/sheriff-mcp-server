package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.LinkedHashMap;
import java.util.Map;
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
    @DisplayName("Claude Code's settings come out as Claude Code writes them, empty arrays and objects included")
    void writesJsonAsClaudeCodeDoes() throws Exception {
        ObjectMapper json = new ObjectMapper();
        String lf = System.lineSeparator();

        String written = ConfigFile.json(json, json.readTree(
                "{\"permissions\":{\"allow\":[\"Bash(ls)\",\"Read\"],\"deny\":[]},\"env\":{},\"model\":\"opus\"}"));

        assertEquals(String.join(lf, "{",
                "  \"permissions\": {",
                "    \"allow\": [",
                "      \"Bash(ls)\",",
                "      \"Read\"",
                "    ],",
                "    \"deny\": []",
                "  },",
                "  \"env\": {},",
                "  \"model\": \"opus\"",
                "}") + lf, written);
        assertEquals("{}" + lf, ConfigFile.json(json, json.createObjectNode()));
    }

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

    @Test
    @DisplayName("an uninstall writes the file back byte for byte when nothing else in it changed")
    void anUninstallGivesTheFileBackAsItWas() throws Exception {
        Path servers = home.resolve(".gemini/config/mcp_config.json");
        Files.createDirectories(servers.getParent());
        String before = "{\"mcpServers\":{\"other\":{\"command\":\"x\"}}}";
        Files.writeString(servers, before);
        Path originals = ConfigFile.originalsBeside(home.resolve("agent/sheriff-mcp.jar"));

        ConfigFile.installing(servers, "{\n  \"mcpServers\" : {\"other\":{\"command\":\"x\"},\"sheriff\":{}}\n}\n", originals);
        ConfigFile.installing(servers, "{\"mcpServers\":{\"other\":{\"command\":\"x\"},\"sheriff\":{}}}", originals);
        ConfigFile.uninstalling(servers, "{\n  \"mcpServers\" : {\n    \"other\" : {\"command\" : \"x\"}\n  }\n}\n", originals);

        assertEquals(before, Files.readString(servers), "the first install's original, not the reinstall's");
        assertEquals(home.resolve("agent/originals"), originals);
        assertTrue(isEmptyFolder(originals), "nothing is kept once it is given back");
    }

    @Test
    @DisplayName("a file the install created is removed, and one the user changed since loses only Sheriff's lines")
    void anUninstallRemovesWhatItCreatedAndKeepsTheUsersChanges() throws Exception {
        Path hooks = home.resolve(".codex/hooks.json");
        Path settings = home.resolve(".claude/settings.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "{\"model\":\"opus\"}");
        Path originals = home.resolve("originals");

        ConfigFile.installing(hooks, "{\"hooks\":{\"Stop\":[]}}", originals);
        ConfigFile.installing(settings, "{\"model\":\"opus\",\"hooks\":{}}", originals);
        ConfigFile.uninstalling(hooks, "{ }", originals);
        ConfigFile.uninstalling(settings, "{\"model\":\"sonnet\"}", originals);

        assertFalse(Files.exists(hooks));
        assertEquals("{\"model\":\"sonnet\"}", Files.readString(settings));
        assertTrue(isEmptyFolder(originals));
        ConfigFile.uninstalling(settings, "", originals);
        assertFalse(Files.exists(settings), "with no record, a file left with nothing in it goes too");
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Windows has no POSIX permissions")
    @DisplayName("the file as it was is kept where only its owner can read it, as it can hold tokens")
    void theOriginalIsKeptForItsOwnerOnly() throws Exception {
        Path settings = home.resolve(".claude/settings.json");
        Files.createDirectories(settings.getParent());
        Files.writeString(settings, "{\"env\": {\"TOKEN\": \"secret\"}}");
        Path originals = home.resolve("originals");

        ConfigFile.installing(settings, "{\"env\": {\"TOKEN\": \"secret\"}, \"hooks\": {}}", originals);

        assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(originals)));
        try (var kept = Files.list(originals)) {
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(
                    kept.findFirst().orElseThrow())));
        }
    }

    private static boolean isEmptyFolder(Path folder) throws IOException {
        try (var entries = Files.list(folder)) {
            return entries.findAny().isEmpty();
        }
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "creating a symbolic link needs a privilege there")
    @DisplayName("a file behind a link is kept by where it really is, and the link is never removed")
    void aLinkedFileIsKeptByWhereItReallyIs() throws Exception {
        Path real = Files.createDirectories(home.resolve("dotfiles")).resolve("settings.json");
        Files.writeString(real, "{\"model\": \"opus\"}");
        Path link = Files.createDirectories(home.resolve(".claude")).resolve("settings.json");
        Files.createSymbolicLink(link, real);
        Path originals = home.resolve("originals");

        ConfigFile.installing(link, "{\"model\": \"opus\", \"hooks\": {}}", originals);
        ConfigFile.uninstalling(real, "{\"model\":\"opus\"}", originals);

        assertEquals("{\"model\": \"opus\"}", Files.readString(real), "the record is the real file's");
        ConfigFile.uninstalling(link, "{}", originals);
        assertTrue(Files.isSymbolicLink(link), "a link left with nothing behind it stays a link");
        assertEquals("{}", Files.readString(real));
    }

    @Test
    @DisplayName("where there are no POSIX permissions, as in a zip, the file as it was is kept all the same")
    void theOriginalIsKeptWithoutPosixPermissions() throws Exception {
        Path settings = home.resolve("settings.json");
        Files.writeString(settings, "model = 1");
        URI zip = URI.create("jar:" + home.resolve("originals.zip").toUri());
        try (FileSystem archive = FileSystems.newFileSystem(zip,
                Map.of("create", "true"))) {
            Path originals = archive.getPath("/originals");

            ConfigFile.installing(settings, "model = 1\nsheriff = 2", originals);
            ConfigFile.uninstalling(settings, "model = 1", originals);

            assertEquals("model = 1", Files.readString(settings));
            assertTrue(isEmptyFolder(originals));
        }
    }

    @Test
    @DisplayName("what is left is written as it is when it says something else, JSON or not")
    void whatIsLeftIsWrittenWhenItDiffers() throws Exception {
        Path config = home.resolve("config.toml");
        Path blank = home.resolve("blank.toml");
        Path scalar = home.resolve("scalar.json");
        Files.writeString(config, "a = 1");
        Files.writeString(blank, "");
        Path originals = home.resolve("originals");
        ConfigFile.installing(config, "a = 1\nsheriff = 1", originals);
        ConfigFile.installing(blank, "sheriff = 1", originals);

        ConfigFile.uninstalling(config, "b = 2", originals);
        ConfigFile.uninstalling(blank, "c = 3", originals);
        ConfigFile.uninstalling(scalar, "1", originals);

        assertEquals("b = 2", Files.readString(config));
        assertEquals("c = 3", Files.readString(blank), "an empty file as it was is not the same as text");
        assertEquals("1", Files.readString(scalar), "a JSON value with nothing to hold is not empty");
    }

    @Test
    @DisplayName("a file the install created goes when only empty objects and lists are left in it, and stays otherwise")
    void aCreatedFileGoesOnlyWhenNothingIsLeft() throws Exception {
        Path originals = home.resolve("originals");
        Map<String, Boolean> kept = new LinkedHashMap<>();
        kept.put("{\"mcpServers\":{\"x\":[]}}", false);
        kept.put("{\"mcpServers\":{\"other\":{\"command\":\"x\"}}}", true);
        kept.put("{\"allow\":[1]}", true);
        kept.put("not JSON", true);
        int index = 0;
        for (Map.Entry<String, Boolean> left : kept.entrySet()) {
            Path file = home.resolve("created-" + index++ + ".json");
            ConfigFile.installing(file, "{\"sheriff\":1}", originals);

            ConfigFile.uninstalling(file, left.getKey(), originals);

            assertEquals(left.getValue(), Files.exists(file), left.getKey());
        }
    }
}
