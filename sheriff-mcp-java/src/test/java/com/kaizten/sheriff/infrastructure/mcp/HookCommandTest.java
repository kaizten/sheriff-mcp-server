package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The jar as the Claude Code hooks: the paths that decide without Docker.
 */
final class HookCommandTest {

    @TempDir
    Path project;

    @Test
    void theLoopsOwnAssistantIsLetThrough() {
        assertEquals(0, HookCommand.run("--hook-gate", Map.of("SHERIFF_AGENT_RUNNING", "1"), project));
    }

    @Test
    void aHookThatCannotStartFailsOpenAndSaysWhy() {
        PrintStream original = System.err;
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
        int exit;
        try {
            exit = HookCommand.run("--hook-gate", Map.of("SHERIFF_TIMEOUT", "soon"), project);
        } finally {
            System.setErr(original);
        }
        assertEquals(0, exit);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("SHERIFF_TIMEOUT"));
    }

    @Test
    @DisplayName("Codex names no project: the Stop hook asked git from the mount and let every turn end")
    void theProjectReachesTheAgentWhenTheAssistantDoesNotNameIt() {
        Map<String, String> environment = HookCommand.agentEnvironment(new McpConfig(Map.of(), project), project);

        assertEquals(project.toString(), environment.get("CLAUDE_PROJECT_DIR"));
    }

    @Test
    @DisplayName("the hooks read their settings from the same project's file as the tools, even from a folder "
            + "inside a module")
    void theHooksReadTheToolsSettings() throws Exception {
        McpConfig config = new McpConfig(Map.of(), project);
        Files.writeString(config.settingsProject().resolve(".sheriff.properties"),
                "SHERIFF_PROTOTYPE=1\n");
        Path inside = Files.createDirectories(project.resolve("src/main"));

        McpConfig hooks = new McpConfig(Map.of(), project);
        Map<String, String> environment = HookCommand.agentEnvironment(hooks, inside);

        assertEquals("1", environment.get("SHERIFF_PROTOTYPE"));
        assertEquals(hooks.repository().toString(), environment.get("TARGET_REPO"));
        assertEquals("kaizten/sheriff:latest", environment.get("SHERIFF_IMAGE"));
        assertTrue(new com.kaizten.sheriff.infrastructure.config.Configuration(environment, project).prototype());
    }

    @Test
    @DisplayName("the hooks guard a component under the profile the tools check it under, and no other")
    void theHooksGuardWhatTheToolsCheck() throws Exception {
        Files.createDirectories(project.resolve("app/src"));
        Files.createDirectories(project.resolve("other/src"));
        Files.writeString(project.resolve("sheriff_history.json"),
                "{\"file:/data\":[{\"components\":[\"app\"],\"test\":\"JAVA_HEXAGONAL\"}]}");

        UnaryOperator<String> profiles = HookCommand.guardedProfiles(
                new McpConfig(Map.of("SHERIFF_REPO", project.toString()), project));

        assertEquals("JAVA,JAVA_HEXAGONAL", profiles.apply("app"));
        assertEquals("", profiles.apply("other"));
    }
}
