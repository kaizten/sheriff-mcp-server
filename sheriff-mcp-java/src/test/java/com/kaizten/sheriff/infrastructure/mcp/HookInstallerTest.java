package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Wiring the hooks into a project's settings: once however often it runs,
 * portable through {@code $HOME}, and without touching anyone else's entries.
 */
final class HookInstallerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path home;

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private HookInstaller installer(Path project, Path jar) {
        return new HookInstaller(project, jar, home, new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    private Path project() throws Exception {
        return Files.createDirectories(home.resolve("work/app"));
    }

    private Path installedJar() {
        return home.resolve(".local/share/sheriff-agent/sheriff-mcp.jar");
    }

    private JsonNode settings(Path project) throws Exception {
        return JSON.readTree(project.resolve(".claude/settings.json").toFile());
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "on Windows the hooks name the jar by its absolute path")
    void writesBothHooksThroughHomeWhenTheJarIsUnderIt() throws Exception {
        Path project = project();

        assertEquals(0, installer(project, installedJar()).install());

        JsonNode hooks = settings(project).path("hooks");
        assertEquals("Edit|Write|MultiEdit|NotebookEdit", hooks.at("/PreToolUse/0/matcher").asText());
        assertEquals("java -jar \"$HOME/.local/share/sheriff-agent/sheriff-mcp.jar\" --hook-gate",
                hooks.at("/PreToolUse/0/hooks/0/command").asText());
        assertEquals("java -jar \"$HOME/.local/share/sheriff-agent/sheriff-mcp.jar\" --hook-stop",
                hooks.at("/Stop/0/hooks/0/command").asText());
        assertEquals("java -jar \"$HOME/.local/share/sheriff-agent/sheriff-mcp.jar\" --hook-turn",
                hooks.at("/UserPromptSubmit/0/hooks/0/command").asText());
        assertEquals(900, hooks.at("/Stop/0/hooks/0/timeout").asInt(),
                "with the tests on, the Stop hook outlives the client's default and was cut short");
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "on Windows the hooks name the jar by its absolute path")
    void failingFastPutsTheOptionInBothCommands() throws Exception {
        Path project = project();

        assertEquals(0, installer(project, installedJar()).failingFast().install());

        JsonNode hooks = settings(project).path("hooks");
        assertEquals("java -jar \"$HOME/.local/share/sheriff-agent/sheriff-mcp.jar\" --hook-gate --fail-fast",
                hooks.at("/PreToolUse/0/hooks/0/command").asText());
        assertEquals("java -jar \"$HOME/.local/share/sheriff-agent/sheriff-mcp.jar\" --hook-stop --fail-fast",
                hooks.at("/Stop/0/hooks/0/command").asText());
    }

    @Test
    void namesAJarOutsideHomeByItsAbsolutePath() throws Exception {
        Path elsewhere = Path.of("/opt/tools/sheriff-mcp.jar");

        String absolute = elsewhere.toAbsolutePath().normalize().toString().replace('\\', '/');
        assertEquals("\"" + absolute + "\"", installer(project(), elsewhere).jarReference());
    }

    @Test
    void installingTwiceLeavesOneCopy() throws Exception {
        Path project = project();

        installer(project, installedJar()).install();
        installer(project, installedJar()).install();

        assertEquals(1, settings(project).at("/hooks/PreToolUse").size());
        assertEquals(1, settings(project).at("/hooks/Stop").size());
        assertEquals(1, settings(project).at("/hooks/UserPromptSubmit").size());
    }

    @Test
    void replacesTheOldScriptAndKeepsEverythingElse() throws Exception {
        Path project = project();
        Files.createDirectories(project.resolve(".claude"));
        Files.writeString(project.resolve(".claude/settings.json"), """
                {"model": "opus", "hooks": {"PreToolUse": [
                  {"matcher": "Edit", "hooks": [
                    {"type": "command", "command": "sheriff-hook.sh --hook-gate"},
                    {"type": "command", "command": "prettier --check"}]},
                  {"matcher": "Bash", "hooks": [{"type": "command", "command": "audit.sh"}]}]}}
                """);

        installer(project, installedJar()).install();

        JsonNode written = settings(project);
        assertEquals("opus", written.path("model").asText());
        JsonNode groups = written.at("/hooks/PreToolUse");
        assertEquals(3, groups.size());
        assertEquals("prettier --check", groups.at("/0/hooks/0/command").asText());
        assertEquals(1, groups.at("/0/hooks").size());
        assertEquals("audit.sh", groups.at("/1/hooks/0/command").asText());
        assertTrue(groups.at("/2/hooks/0/command").asText().endsWith("--hook-gate"));
    }

    @Test
    void uninstallingRemovesOnlySheriff() throws Exception {
        Path project = project();
        Files.createDirectories(project.resolve(".claude"));
        Files.writeString(project.resolve(".claude/settings.json"), """
                {"hooks": {"Stop": [{"hooks": [{"type": "command", "command": "notify.sh"}]}]}}
                """);
        installer(project, installedJar()).install();

        assertEquals(0, installer(project, installedJar()).uninstall());

        JsonNode hooks = settings(project).path("hooks");
        assertFalse(hooks.has("PreToolUse"));
        assertFalse(hooks.has("UserPromptSubmit"));
        assertEquals(1, hooks.path("Stop").size());
        assertEquals("notify.sh", hooks.at("/Stop/0/hooks/0/command").asText());
    }

    @Test
    @DisplayName("install.sh wires them for every project, so nobody has a step per project to forget")
    void forTheUserTheyGoIntoTheUsersOwnSettings() throws Exception {
        HookInstaller user = HookInstaller.forUser(installedJar(), home,
                new PrintStream(output, true, StandardCharsets.UTF_8));

        assertEquals(0, user.install());

        JsonNode hooks = settings(home).path("hooks");
        assertTrue(hooks.at("/PreToolUse/0/hooks/0/command").asText().endsWith("--hook-gate"));
        assertTrue(hooks.at("/Stop/0/hooks/0/command").asText().endsWith("--hook-stop"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("--uninstall-hooks --user"));
        assertEquals(0, user.uninstall());
        assertFalse(settings(home).has("hooks"));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "creating a symbolic link needs a privilege there")
    @DisplayName("user settings kept in a dotfiles repository are a link, and replacing the link broke it")
    void writesThroughALinkRatherThanReplacingIt() throws Exception {
        Path real = Files.createDirectories(home.resolve("dotfiles")).resolve("claude-settings.json");
        Files.writeString(real, "{\"model\": \"opus\"}");
        Path link = Files.createDirectories(home.resolve(".claude")).resolve("settings.json");
        Files.createSymbolicLink(link, real);

        assertEquals(0, HookInstaller.forUser(installedJar(), home,
                new PrintStream(output, true, StandardCharsets.UTF_8)).install());

        assertTrue(Files.isSymbolicLink(link));
        assertTrue(Files.readString(real).contains("--hook-stop"));
        assertTrue(Files.readString(real).contains("opus"));
    }

    @Test
    void refusesASettingsFileThatIsNotAnObject() throws Exception {
        Path project = project();
        Files.createDirectories(project.resolve(".claude"));
        Files.writeString(project.resolve(".claude/settings.json"), "[1, 2]");

        assertEquals(2, installer(project, installedJar()).install());

        assertEquals("[1, 2]", Files.readString(project.resolve(".claude/settings.json")));
    }

    @Test
    @DisplayName("the tools the order of work goes through run without a prompt, whatever the model's permission mode")
    void allowsTheSheriffToolsAndKeepsEveryOtherRule() throws Exception {
        Path project = project();
        Files.createDirectories(project.resolve(".claude"));
        Files.writeString(project.resolve(".claude/settings.json"), """
                {"permissions": {"allow": ["Bash(ls)"], "deny": ["Bash(rm *)"]}}
                """);

        installer(project, installedJar()).install();
        installer(project, installedJar()).install();

        JsonNode permissions = settings(project).path("permissions");
        List<String> allowed = new ArrayList<>();
        permissions.path("allow").forEach(rule -> allowed.add(rule.asText()));
        assertEquals(List.of("Bash(ls)", "mcp__sheriff__sheriff_test", "mcp__sheriff__sheriff_fix",
                "mcp__sheriff__sheriff_guidelines", "mcp__sheriff__sheriff_task"), allowed);
        assertFalse(allowed.contains("mcp__sheriff__sheriff_autofix"), "it commits and spends tokens: it asks");
        assertEquals("Bash(rm *)", permissions.at("/deny/0").asText());

        installer(project, installedJar()).uninstall();

        assertEquals("[\"Bash(ls)\"]", settings(project).at("/permissions/allow").toString());
    }

    @Test
    @DisplayName("a settings file that had no rules has none left once they are taken out")
    void leavesNoEmptyPermissionsBehind() throws Exception {
        Path project = project();
        installer(project, installedJar()).install();
        installer(project, installedJar()).uninstall();

        assertFalse(settings(project).has("permissions"), settings(project).toString());
    }
}

