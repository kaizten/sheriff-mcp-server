package com.kaizten.sheriff.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for the configuration layer, with the environment supplied as a map. */
class ConfigurationTests {

    private static final Path AGENT = Path.of("/repo/sheriff-mcp-java");

    private static Configuration with(Map<String, String> environment) {
        return new Configuration(environment, AGENT);
    }

    @Test
    void defaultsMatchTheReferenceImplementation() {
        Configuration configuration = with(Map.of());
        assertEquals("kaizten/sheriff:latest", configuration.image());
        assertEquals("JAVA", configuration.testType());
        assertEquals("sheriff-mcp-java", configuration.component());
        assertEquals("claude_cli", configuration.backend());
        assertEquals(5, configuration.loopSettings().maxFilesPerBatch());
        assertTrue(configuration.loopSettings().useGitSafety());
        assertNull(configuration.loopSettings().maxIterations());
    }

    @Test
    @DisplayName("the target repository is the agent's parent until it is pointed elsewhere")
    void targetRepositoryFollowsTheAgentOrTheEnvironment() {
        assertEquals(Path.of("/repo").toAbsolutePath(), with(Map.of()).targetRepository());
        assertEquals(Path.of("/elsewhere").toAbsolutePath(),
                with(Map.of("TARGET_REPO", "/elsewhere")).targetRepository());
    }

    @Test
    @DisplayName("the catalog follows the agent, never the analyzed project")
    void catalogAndLogsAreAnchoredToTheAgent() {
        Configuration pointedElsewhere = with(Map.of("TARGET_REPO", "/elsewhere"));
        assertEquals(AGENT.resolve("rules_catalog.json").toAbsolutePath(),
                pointedElsewhere.rulesCatalog().toAbsolutePath());
        assertEquals(AGENT.resolve("logs").toAbsolutePath(), pointedElsewhere.promptLogDirectory().toAbsolutePath());
    }

    @Test
    void aMarkdownRuleListCanReplaceTheCatalog() {
        assertEquals(Path.of("/rules.md"), with(Map.of("RULES_CATALOG", "/rules.md")).rulesCatalog());
    }

    @Test
    @DisplayName("the verification command follows the component, so pointing elsewhere moves the gate too")
    void verificationCommandFollowsTheComponent() {
        assertEquals("cd sheriff-mcp-java && mvn -q test", with(Map.of()).verificationCommand());
        assertEquals("cd other && mvn -q test", with(Map.of("SHERIFF_COMPONENT", "other")).verificationCommand());
        assertEquals("make test", with(Map.of("VERIFICATION_TEST_CMD", "make test")).verificationCommand());
    }

    @Test
    void everyKnobIsOverridable() {
        Configuration configuration = with(Map.of(
                "SHERIFF_MAX_ITERATIONS", "3",
                "SHERIFF_MAX_FILES_PER_BATCH", "0",
                "SHERIFF_AGENT_GIT_SAFETY", "0",
                "SHERIFF_TIMEOUT", "42",
                "SHERIFF_MAX_RULES_IN_PROMPT", "10",
                "ANTHROPIC_MAX_TOKENS", "1234"));
        assertEquals(3, configuration.loopSettings().maxIterations());
        assertEquals(0, configuration.loopSettings().maxFilesPerBatch());
        assertFalse(configuration.loopSettings().useGitSafety());
        assertEquals(42, configuration.sheriffTimeout().toSeconds());
        assertEquals(10, configuration.maxRulesInPrompt());
        assertEquals(1234L, configuration.apiMaxTokens());
    }

    @Test
    @DisplayName("the agent's own directory comes from where its code is, not from the caller's cwd")
    void theModuleRootIsDerivedFromTheCodeLocation() {
        assertEquals(Path.of("/repo/sheriff-mcp-java"),
                Configuration.moduleRootOf(Path.of("/repo/sheriff-mcp-java/target/sheriff-mcp.jar")));
        assertEquals(Path.of("/repo/sheriff-mcp-java"),
                Configuration.moduleRootOf(Path.of("/repo/sheriff-mcp-java/target/classes")));
    }

    @Test
    @DisplayName("an installed jar's home is its own folder, where the catalog was put beside it")
    void anInstalledJarLivesInItsOwnFolder() {
        assertEquals(Path.of("/home/u/.local/share/sheriff-agent"),
                Configuration.moduleRootOf(Path.of("/home/u/.local/share/sheriff-agent/sheriff-mcp.jar")));
    }

    @Test
    @DisplayName("a real run found this: the log went into the analyzed repository and cut the run")
    void theDefaultAgentDirectoryIsNotTheCurrentOne() {
        assertFalse(Configuration.defaultAgentDirectory().equals(Path.of(".")),
                "deriving the agent's directory from the cwd is what wrote its logs into the target repo");
    }

    @Test
    @DisplayName("a setting that is not a number names itself, rather than saying 'For input string'")
    void aMalformedNumberNamesTheVariable() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> with(Map.of("SHERIFF_TIMEOUT", "abc")).sheriffTimeout());
        assertTrue(refused.getMessage().contains("SHERIFF_TIMEOUT"));
        assertTrue(refused.getMessage().contains("abc"));
    }

    @Test
    @DisplayName("an empty iteration cap means 'size it automatically', not a crash")
    void anEmptyNumberIsUnset() {
        assertNull(with(Map.of("SHERIFF_MAX_ITERATIONS", "")).loopSettings().maxIterations());
        assertEquals(7, with(Map.of("SHERIFF_MAX_ITERATIONS", " 7 ")).loopSettings().maxIterations());
    }

    @Test
    void theAgentsOwnAssistantIsRecognised() {
        assertFalse(with(Map.of()).insideAgentRun());
        assertTrue(with(Map.of("SHERIFF_AGENT_RUNNING", "1")).insideAgentRun());
    }

    @Test
    @DisplayName("the hooks ask git from the project Claude Code has open, when it says which")
    void theProjectDirectoryComesFromClaudeCode() {
        assertEquals(Path.of("/work/app").toAbsolutePath(),
                with(Map.of("TARGET_REPO", "/work", "CLAUDE_PROJECT_DIR", "/work/app")).projectDirectory()
                        .toAbsolutePath());
        assertEquals(Path.of("/work").toAbsolutePath(),
                with(Map.of("TARGET_REPO", "/work")).projectDirectory().toAbsolutePath());
    }

    @Test
    void mockModeIsOffUnlessAskedFor() {
        assertFalse(with(Map.of()).mockMode());
        assertTrue(with(Map.of("SHERIFF_MOCK", "1")).mockMode());
    }

    @Test
    @DisplayName("the default model is the current one, not whatever was current when this was written")
    void apiDefaults() {
        assertEquals("claude-opus-5", with(Map.of()).apiModel());
        assertEquals("", with(Map.of()).apiKey());
        assertEquals(16000L, with(Map.of()).apiMaxTokens());
        assertEquals("", with(Map.of()).apiBaseUrl());
    }

    @Test
    @DisplayName("a timeout or a cap below 1 is refused up front, not discovered when every run fails")
    void numbersThatCannotWorkAreRefused() {
        IllegalArgumentException timeout = assertThrows(IllegalArgumentException.class,
                () -> with(Map.of("SHERIFF_TIMEOUT", "0")).sheriffTimeout());
        assertTrue(timeout.getMessage().contains("at least 1"), timeout.getMessage());
        assertThrows(IllegalArgumentException.class,
                () -> with(Map.of("SHERIFF_MAX_ITERATIONS", "-1")).loopSettings());
        assertEquals(5, with(Map.of("SHERIFF_MAX_ITERATIONS", "5")).loopSettings().maxIterations());
    }

    @Test
    @DisplayName("the catalog cache is found through the environment given, and nowhere else")
    void theCatalogCacheFollowsTheEnvironment() {
        assertEquals(Path.of("/c/sheriff-mcp/rules_catalog.json"),
                with(Map.of("XDG_CACHE_HOME", "/c", "HOME", "/h")).cachedCatalog().orElseThrow());
        assertEquals(Path.of("/h/.cache/sheriff-mcp/rules_catalog.json"),
                with(Map.of("HOME", "/h")).cachedCatalog().orElseThrow());
        assertEquals(Path.of("/u/.cache/sheriff-mcp/rules_catalog.json"),
                with(Map.of("USERPROFILE", "/u")).cachedCatalog().orElseThrow());
        assertTrue(with(Map.of()).cachedCatalog().isEmpty());
        assertTrue(with(Map.of("RULES_CATALOG", "/r.json")).catalogConfigured());
        assertFalse(with(Map.of()).catalogConfigured());
    }

    @Test
    @DisplayName("Antigravity's command is accept-edits by default, and configurable as a whole, model included")
    void antigravitysCommandIsConfigurableAsAWhole() {
        assertEquals(List.of("agy", "--mode", "accept-edits"), with(Map.of()).antigravityCommand());
        assertEquals(List.of("agy", "--mode", "accept-edits"), with(Map.of("ANTIGRAVITY_COMMAND", "")).antigravityCommand());
        assertEquals(List.of("agy", "--mode", "accept-edits", "--model", "claude-sonnet-5-5-high"),
                with(Map.of("ANTIGRAVITY_COMMAND", " agy --mode accept-edits  --model claude-sonnet-5-5-high "))
                        .antigravityCommand());
    }

    @Test
    void antigravitysTimeoutIsTheClaudeOneByDefaultAndNeverZero() {
        assertEquals(600, with(Map.of()).antigravityTimeout().toSeconds());
        assertEquals(90, with(Map.of("SHERIFF_AGENT_ANTIGRAVITY_TIMEOUT", "90")).antigravityTimeout().toSeconds());
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> with(Map.of("SHERIFF_AGENT_ANTIGRAVITY_TIMEOUT", "0")).antigravityTimeout());
        assertTrue(refused.getMessage().contains("SHERIFF_AGENT_ANTIGRAVITY_TIMEOUT"), refused.getMessage());
    }

    @Test
    @DisplayName("the user's home comes from this environment only, HOME before USERPROFILE")
    void theUsersHomeComesFromTheEnvironment() {
        assertEquals(Optional.of(Path.of("/home/me")), with(Map.of("HOME", "/home/me")).userHome());
        assertEquals(Optional.of(Path.of("C:/Users/me")), with(Map.of("HOME", " ", "USERPROFILE", "C:/Users/me"))
                .userHome());
        assertEquals(Optional.empty(), with(Map.of()).userHome());
    }

    @Test
    @DisplayName("a process run with another HOME is pointed back to the build tools' homes it did not set")
    void theBuildToolsAreToldWhereTheirHomesAre() {
        Map<String, String> homes = with(Map.of("HOME", "/home/me", "GRADLE_USER_HOME", "/opt/gradle")).buildToolHomes();
        assertEquals(Path.of("/home/me", ".m2").toString(), homes.get("MAVEN_USER_HOME"));
        assertEquals(Path.of("/home/me", ".npm").toString(), homes.get("npm_config_cache"));
        assertEquals(Path.of("/home/me", ".npmrc").toString(), homes.get("npm_config_userconfig"));
        assertFalse(homes.containsKey("GRADLE_USER_HOME"), "one the environment already sets is left as it is");
        assertEquals(Map.of(), with(Map.of()).buildToolHomes());
    }
}
