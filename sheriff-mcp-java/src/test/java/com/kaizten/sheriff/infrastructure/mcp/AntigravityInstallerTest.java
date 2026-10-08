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
import org.junit.jupiter.api.io.TempDir;

/**
 * Setting Antigravity up: the server where {@code agy mcp add} puts it, and
 * the four tools that neither commit nor spend tokens allowed, once however
 * often it runs and without touching anything else in either file.
 */
final class AntigravityInstallerTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> RULES = List.of("mcp(sheriff/sheriff_test)", "mcp(sheriff/sheriff_fix)",
            "mcp(sheriff/sheriff_guidelines)", "mcp(sheriff/sheriff_task)");

    @TempDir
    Path home;

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private Path jar() {
        return home.resolve(".local/share/sheriff-agent/sheriff-mcp.jar");
    }

    private AntigravityInstaller installer() {
        return AntigravityInstaller.forUser(home, jar(), new PrintStream(output, true, StandardCharsets.UTF_8));
    }

    private Path servers() {
        return home.resolve(".gemini/config/mcp_config.json");
    }

    private Path settings() {
        return home.resolve(".gemini/antigravity-cli/settings.json");
    }

    private static List<String> allowed(JsonNode settings) {
        List<String> rules = new ArrayList<>();
        settings.path("permissions").path("allow").forEach(rule -> rules.add(rule.asText()));
        return rules;
    }

    @Test
    @DisplayName("the entry is the one agy mcp add writes, naming the jar by its absolute path")
    void writesTheServerAsAgyDoes() throws Exception {
        assertEquals(0, installer().install());

        JsonNode entry = JSON.readTree(servers().toFile()).path("mcpServers").path("sheriff");
        assertEquals("java", entry.path("command").asText());
        assertEquals("-jar", entry.path("args").get(0).asText());
        assertEquals(jar().toAbsolutePath().normalize().toString(), entry.path("args").get(1).asText());
        assertFalse(entry.path("disabled").asBoolean(true));
        assertTrue(entry.path("env").isMissingNode(), entry.toString());
    }

    @Test
    @DisplayName("headless agy refuses an MCP tool no rule allows: the four that write nothing risky are allowed")
    void allowsTheFourToolsAndNotAutofix() throws Exception {
        installer().install();

        List<String> rules = allowed(JSON.readTree(settings().toFile()));
        assertEquals(RULES, rules);
        assertFalse(rules.contains("mcp(sheriff/sheriff_autofix)"), rules.toString());
    }

    @Test
    @DisplayName("an entry written by hand is replaced, other servers and settings are kept, and nothing doubles")
    void replacesAnEarlierEntryAndKeepsTheRest() throws Exception {
        Files.createDirectories(servers().getParent());
        Files.writeString(servers(), """
                {"mcpServers": {"sheriff": {"command": "java", "args": ["-jar", "/old/sheriff-mcp.jar"]},
                                "other": {"command": "other"}}}
                """);
        Files.createDirectories(settings().getParent());
        Files.writeString(settings(), """
                {"model": "Gemini", "permissions": {"allow": ["command(ls)", "mcp(sheriff/sheriff_test)"]}}
                """);

        installer().install();
        installer().install();

        JsonNode configured = JSON.readTree(servers().toFile()).path("mcpServers");
        assertEquals("other", configured.path("other").path("command").asText());
        assertFalse(configured.toString().contains("/old/sheriff-mcp.jar"), configured.toString());
        JsonNode written = JSON.readTree(settings().toFile());
        assertEquals("Gemini", written.path("model").asText());
        List<String> expected = new ArrayList<>(List.of("command(ls)"));
        expected.addAll(RULES);
        assertEquals(expected, allowed(written));
    }

    @Test
    @DisplayName("--pull-always reaches the server through its entry, as for Claude Code and Codex")
    void pullingAlwaysSetsTheVariableInTheEntry() throws Exception {
        assertEquals(0, installer().pullingAlways().install());

        JsonNode entry = JSON.readTree(servers().toFile()).path("mcpServers").path("sheriff");
        assertEquals("always", entry.path("env").path("SHERIFF_PULL").asText());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("pulled again"), output.toString());
    }

    @Test
    void theEntryNamesThisJar() {
        JsonNode entry = installer().serverEntry();

        assertEquals(jar().toAbsolutePath().normalize().toString(), entry.path("args").get(1).asText());
    }

    @Test
    @DisplayName("uninstall takes out only what install put in")
    void uninstallKeepsEverythingElse() throws Exception {
        Files.createDirectories(settings().getParent());
        Files.writeString(settings(), "{\"model\": \"Gemini\", \"permissions\": {\"allow\": [\"command(ls)\"]}}");
        Files.createDirectories(servers().getParent());
        Files.writeString(servers(), "{\"mcpServers\": {\"other\": {\"command\": \"other\"}}}");
        installer().install();

        assertEquals(0, installer().uninstall());

        JsonNode configured = JSON.readTree(servers().toFile()).path("mcpServers");
        assertTrue(configured.path("sheriff").isMissingNode(), configured.toString());
        assertEquals("other", configured.path("other").path("command").asText());
        JsonNode written = JSON.readTree(settings().toFile());
        assertEquals(List.of("command(ls)"), allowed(written));
        assertEquals("Gemini", written.path("model").asText());
    }

    @Test
    @DisplayName("uninstalling what was never installed creates no file")
    void uninstallWithNothingInstalledWritesNothing() {
        assertEquals(0, installer().uninstall());

        assertFalse(Files.exists(servers()));
        assertFalse(Files.exists(settings()));
    }

    @Test
    @DisplayName("rules left empty by uninstall go, with an emptied permissions object")
    void uninstallDropsWhatItLeavesEmpty() throws Exception {
        installer().install();

        installer().uninstall();

        assertTrue(JSON.readTree(settings().toFile()).path("permissions").isMissingNode());
    }

    @Test
    @DisplayName("a settings file that is not a JSON object is reported and left as it was")
    void leavesAFileThatIsNotAnObjectAlone() throws Exception {
        Files.createDirectories(settings().getParent());
        Files.writeString(settings(), "[1, 2]");

        assertEquals(2, installer().install());

        assertEquals("[1, 2]", Files.readString(settings()));
        assertFalse(Files.exists(servers()));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("not a JSON object"), output.toString());
    }

    @Test
    @DisplayName("given the Java install.sh checked, the server runs it rather than the PATH's")
    void theServerRunsTheJavaItIsGiven() throws Exception {
        Path java = home.resolve("jdk/bin/java");

        assertEquals(0, installer().runningWith(java).pullingAlways().install());

        JsonNode entry = JSON.readTree(servers().toFile()).path("mcpServers").path("sheriff");
        assertEquals(java.toAbsolutePath().normalize().toString(), entry.path("command").asText());
        assertEquals("always", entry.path("env").path("SHERIFF_PULL").asText(), entry.toString());
    }
}
