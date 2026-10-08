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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Setting Codex up: the server with what {@code codex mcp add} cannot give it,
 * the order of work in the global {@code AGENTS.md}, and the Stop hook alone,
 * once however often it runs and without touching anything else.
 */
final class CodexInstallerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String INSTRUCTIONS = "Call sheriff_test first.\nThen fix.\n";

    @TempDir
    Path home;

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private Path codexHome() {
        return home.resolve(".codex");
    }

    private CodexInstaller installer(boolean failFast) {
        return new CodexInstaller(codexHome(), home.resolve(".local/share/sheriff-agent/sheriff-mcp.jar"), home,
                new PrintStream(output, true, StandardCharsets.UTF_8), INSTRUCTIONS, failFast);
    }

    private String read(String file) throws Exception {
        return Files.readString(codexHome().resolve(file));
    }

    @Test
    @DisplayName("Codex cuts a call at 60 s, and codex exec refuses sheriff_fix unless the entry approves it")
    void writesTheServerWithItsTimeAndApproval() throws Exception {
        assertEquals(0, installer(false).install());

        String config = read("config.toml");
        assertTrue(config.contains("[mcp_servers.sheriff]"), config);
        assertTrue(config.contains("tool_timeout_sec = 600"), config);
        assertTrue(config.contains("[mcp_servers.sheriff.tools.sheriff_fix]"), config);
        assertTrue(config.contains("approval_mode = \"approve\""), config);
        assertTrue(config.contains("sheriff-mcp.jar\"]"), config);
    }

    @Test
    @DisplayName("an entry codex mcp add wrote is replaced, with its subtables, and the rest is kept")
    void replacesAnEarlierEntryAndKeepsTheRest() throws Exception {
        Files.createDirectories(codexHome());
        Files.writeString(codexHome().resolve("config.toml"), """
                [projects."/home/me/app"]
                trust_level = "trusted"

                [mcp_servers.sheriff]
                command = "java"
                args = ["-jar", "/old/sheriff-mcp.jar"]

                [mcp_servers.sheriff.tools.sheriff_autofix]
                approval_mode = "approve"

                [mcp_servers.other]
                command = "other"
                """);

        installer(false).install();
        installer(false).install();

        String config = read("config.toml");
        assertTrue(config.contains("trust_level = \"trusted\""), config);
        assertTrue(config.contains("[mcp_servers.other]\ncommand = \"other\"")
                || config.contains("[mcp_servers.other]" + System.lineSeparator() + "command = \"other\""), config);
        assertFalse(config.contains("/old/sheriff-mcp.jar"), config);
        assertFalse(config.contains("sheriff_autofix"), config);
        assertEquals(1, config.split("\\[mcp_servers\\.sheriff\\]", -1).length - 1, config);
    }

    private String entryFor(Path jar) {
        return new CodexInstaller(codexHome(), jar, home, new PrintStream(output, true, StandardCharsets.UTF_8),
                INSTRUCTIONS, false).serverEntry();
    }

    private static String tomlString(Path jar) {
        return jar.toAbsolutePath().normalize().toString().replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Test
    @DisplayName("Codex does not expand ~ in a server's arguments, and Windows paths carry backslashes")
    void namesTheJarByItsAbsolutePathEscapedForToml() {
        Path jar = Path.of("/opt/my tools/sheriff-mcp.jar");

        assertTrue(entryFor(jar).contains("args = [\"-jar\", \"" + tomlString(jar) + "\"]"), entryFor(jar));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Windows does not allow a quote in a path")
    void escapesAQuoteInThePath() {
        Path jar = Path.of("/opt/a \"b\"/sheriff-mcp.jar");

        assertTrue(entryFor(jar).contains("/opt/a \\\"b\\\"/sheriff-mcp.jar"), entryFor(jar));
    }

    @Test
    @DisplayName("asked for a feature without the order of work, Codex called no Sheriff tool at all")
    void putsTheOrderOfWorkInTheGlobalAgentsFileOnce() throws Exception {
        Files.createDirectories(codexHome());
        Files.writeString(codexHome().resolve("AGENTS.md"), "Answer in Spanish.\n");

        installer(false).install();
        installer(false).install();

        String agents = read("AGENTS.md");
        assertTrue(agents.startsWith("Answer in Spanish."), agents);
        assertTrue(agents.contains("Call sheriff_test first."), agents);
        assertEquals(1, agents.split("Call sheriff_test first\\.", -1).length - 1, agents);
    }

    @Test
    @DisplayName("Codex edits with apply_patch, which carries no file path, so the gate stays out")
    void writesTheTurnAndStopHooksWithoutTheGate() throws Exception {
        installer(false).install();

        JsonNode written = JSON.readTree(codexHome().resolve("hooks.json").toFile());
        assertTrue(written.path("permissions").isMissingNode(), "Claude Code's rules mean nothing to Codex: " + written);
        JsonNode hooks = written.path("hooks");
        assertTrue(hooks.path("PreToolUse").isMissingNode(), hooks.toString());
        assertTrue(hooks.at("/UserPromptSubmit/0/hooks/0/command").asText().endsWith("--hook-turn"), hooks.toString());
        assertTrue(hooks.at("/Stop/0/hooks/0/command").asText().endsWith("--hook-stop"), hooks.toString());
        assertFalse(hooks.at("/Stop/0/hooks/0/command").asText().contains("--fail-fast"), hooks.toString());
    }

    @Test
    @DisplayName("Codex passes a hook almost none of the shell's variables, so fail-fast is an option of the command")
    void putsFailFastInTheHookCommand() throws Exception {
        installer(true).install();

        JsonNode hooks = JSON.readTree(codexHome().resolve("hooks.json").toFile()).path("hooks");
        assertTrue(hooks.at("/Stop/0/hooks/0/command").asText().endsWith("--hook-stop --fail-fast"),
                hooks.toString());
        assertTrue(hooks.at("/UserPromptSubmit/0/hooks/0/command").asText().endsWith("--hook-turn --fail-fast"),
                "the turn's fingerprints must be taken as the Stop hook takes them, or none ever matches");
    }

    @Test
    void canLeaveTheHookOut() throws Exception {
        assertEquals(0, installer(false).withoutStopHook().install());

        assertTrue(Files.exists(codexHome().resolve("config.toml")));
        assertFalse(Files.exists(codexHome().resolve("hooks.json")));
    }

    @Test
    @DisplayName("Codex starts its servers with almost none of the shell's variables, so SHERIFF_PULL is in the entry")
    void canHaveTheServerPullANewerImageOnStart() throws Exception {
        assertEquals(0, installer(false).pullingAlways().install());

        String config = read("config.toml");
        int environment = config.indexOf("env = { SHERIFF_PULL = \"always\" }");
        assertTrue(environment > config.indexOf("[mcp_servers.sheriff]"), config);
        assertTrue(environment < config.indexOf("[mcp_servers.sheriff.tools.sheriff_fix]"),
                "an env after the subtable would belong to the subtable: " + config);
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("each time the server starts"), output.toString());
    }

    @Test
    @DisplayName("by default a newer image is only reported, so new rules do not arrive unasked")
    void leavesThePullPolicyToTheServerByDefault() throws Exception {
        installer(false).install();

        assertFalse(read("config.toml").contains("SHERIFF_PULL"), read("config.toml"));
    }

    @Test
    void pullsAlwaysWithoutTheHookToo() throws Exception {
        assertEquals(0, installer(false).withoutStopHook().pullingAlways().install());

        assertTrue(read("config.toml").contains("SHERIFF_PULL = \"always\""), read("config.toml"));
        assertFalse(Files.exists(codexHome().resolve("hooks.json")));
    }

    @Test
    void uninstallingTakesTheEnvironmentOutWithTheEntry() throws Exception {
        installer(false).pullingAlways().install();

        installer(false).uninstall();

        assertFalse(read("config.toml").contains("SHERIFF_PULL"), read("config.toml"));
    }

    @Test
    void uninstallingLeavesOnlyWhatWasThereBefore() throws Exception {
        Files.createDirectories(codexHome());
        Files.writeString(codexHome().resolve("config.toml"), "[tui]\nscreen_reader_detection_done = true\n");

        installer(false).install();
        assertEquals(0, installer(false).uninstall());

        assertEquals(List.of("[tui]", "screen_reader_detection_done = true"),
                Files.readAllLines(codexHome().resolve("config.toml")));
        assertFalse(Files.exists(codexHome().resolve("AGENTS.md")), "the file only ever held Sheriff's block");
        assertFalse(read("hooks.json").contains("--hook-stop"));
    }

    @Test
    void findsCodexsHomeWhereCodexDoes() throws Exception {
        Path elsewhere = home.resolve("custom-codex");
        CodexInstaller installer = CodexInstaller.forUser(Map.of("CODEX_HOME", elsewhere.toString()), home,
                home.resolve("sheriff-mcp.jar"), new PrintStream(output, true, StandardCharsets.UTF_8), INSTRUCTIONS,
                false);

        installer.install();

        assertTrue(Files.exists(elsewhere.resolve("config.toml")));
        assertFalse(Files.exists(codexHome()));
    }
}
