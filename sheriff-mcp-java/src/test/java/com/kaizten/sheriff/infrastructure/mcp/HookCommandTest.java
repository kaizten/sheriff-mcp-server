package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;
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
        Map<String, String> environment = HookCommand.agentEnvironment(Map.of(), new McpConfig(Map.of(), project),
                project);

        assertEquals(project.toString(), environment.get("CLAUDE_PROJECT_DIR"));
    }
}
