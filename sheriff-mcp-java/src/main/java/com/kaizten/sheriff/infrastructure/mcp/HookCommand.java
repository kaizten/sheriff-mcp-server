package com.kaizten.sheriff.infrastructure.mcp;

import com.kaizten.sheriff.infrastructure.cli.Agent;
import com.kaizten.sheriff.infrastructure.config.Composition;
import com.kaizten.sheriff.infrastructure.config.Configuration;
import com.kaizten.sheriff.infrastructure.hook.HookExit;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * The same jar as the two Claude Code hooks, {@code --hook-gate} and
 * {@code --hook-stop}, working the project out the way the server does.
 *
 * <p>The hooks themselves are the agent's: the gate that refuses an edit to a
 * component that already fails, and the Stop hook that refuses to end a turn
 * leaving errors behind. What this adds is what made them unusable outside a
 * Java project. Run from the agent's jar they analyzed every component under
 * one configured profile, {@code JAVA} unless someone set another, so in a
 * TypeScript project a component with errors read as zero errors and the
 * guardrail let everything through. Here each component is analyzed under
 * the profile its own sources call for, and what to mount comes from the
 * project Claude Code has open, so the hook script no longer has to guess.
 */
public final class HookCommand {

    private static final String PROJECT_VARIABLE = "CLAUDE_PROJECT_DIR";
    private static final String NO_COMPONENT = "";
    private static final String COULD_NOT_START =
            "The code standards hook could not start (%s) -- letting the work through unchecked.%n";
    private static final String ERROR_UTILITY_CLASS = "This is a utility class and cannot be instantiated.";

    /**
     * Refuses to be instantiated: every member here is static.
     */
    private HookCommand() {
        throw new UnsupportedOperationException(ERROR_UTILITY_CLASS);
    }

    /**
     * Runs one hook against the project the assistant has open.
     *
     * @param hook {@code --hook-gate} or {@code --hook-stop}
     * @param environment the process environment
     * @param workingDirectory where the hook was started, used when the
     *     assistant did not say which project is open
     * @return the hook's exit code: 0 lets the work through, 2 blocks it
     */
    public static int run(String hook, Map<String, String> environment, Path workingDirectory) {
        try {
            String named = environment.get(PROJECT_VARIABLE);
            Path project = named == null || named.isBlank() ? workingDirectory : Path.of(named);
            McpConfig config = new McpConfig(environment, project);
            if (config.problem().isPresent()) {
                System.err.printf(COULD_NOT_START, config.problem().get());
                return HookExit.ALLOW;
            }
            Map<String, String> agentEnvironment = agentEnvironment(environment, config, project);
            Configuration configuration = new Configuration(agentEnvironment, config.cachedCatalog().getParent());
            return new Agent(configuration, Composition.real(configuration, config::profileFor,
                    config.layout()::verificationCommand, config.layout()::testCommandForModel)).run(List.of(hook));
        } catch (RuntimeException exception) {
            System.err.printf(COULD_NOT_START, exception.getMessage());
            return HookExit.ALLOW;
        }
    }

    /**
     * The environment the agent runs a hook with: the repository, image and
     * timeout as the server resolved them, and the project that is open.
     *
     * <p>The project is set even when the assistant did not set it. Codex
     * runs a hook in the session's directory and names no project, and the
     * agent then fell back to the mount, which for a repository that is one
     * module is its parent and no repository at all: the Stop hook asked git
     * from there, saw nothing changed and let every turn end.
     *
     * @param environment the process environment
     * @param config the server's configuration, for that project
     * @param project the project the assistant has open
     * @return the environment for the agent
     */
    static Map<String, String> agentEnvironment(Map<String, String> environment, McpConfig config, Path project) {
        Map<String, String> agentEnvironment = config.agentEnvironment(environment, NO_COMPONENT);
        agentEnvironment.put(PROJECT_VARIABLE, project.toString());
        return agentEnvironment;
    }
}
