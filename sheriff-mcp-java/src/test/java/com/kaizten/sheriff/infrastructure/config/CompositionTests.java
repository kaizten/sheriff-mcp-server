package com.kaizten.sheriff.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.BatchGrouping;
import com.kaizten.sheriff.domain.valueobject.LoopSettings;
import com.kaizten.sheriff.infrastructure.antigravity.AntigravityCliFixer;
import com.kaizten.sheriff.infrastructure.api.AnthropicApiFixer;
import com.kaizten.sheriff.infrastructure.api.OpenAiApiFixer;
import com.kaizten.sheriff.infrastructure.catalog.JsonRuleCatalog;
import com.kaizten.sheriff.infrastructure.catalog.MarkdownRuleCatalog;
import com.kaizten.sheriff.infrastructure.claude.ClaudeCliFixer;
import com.kaizten.sheriff.infrastructure.codex.CodexCliFixer;
import com.kaizten.sheriff.infrastructure.docker.SheriffDockerAnalyzer;
import com.kaizten.sheriff.infrastructure.mock.MockSheriffAnalyzer;
import com.kaizten.sheriff.infrastructure.process.FakeProcessRunner;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the composition root: the one place that decides which
 * implementation satisfies each port.
 */
class CompositionTests {

    private static Composition with(Map<String, String> environment) {
        return new Composition(
                new Configuration(environment, Path.of("/repo/sheriff-mcp-java")),
                FakeProcessRunner.always(ProcessOutcome.completed(0, "", "")));
    }

    @Test
    void realSheriffByDefault() {
        assertInstanceOf(SheriffDockerAnalyzer.class, with(Map.of()).analyzer());
    }

    @Test
    void theMockWhenDockerIsNotWanted() {
        assertInstanceOf(MockSheriffAnalyzer.class, with(Map.of("SHERIFF_MOCK", "1")).analyzer());
    }

    @Test
    void theCliBackendByDefault() {
        assertInstanceOf(ClaudeCliFixer.class, with(Map.of()).fixer());
    }

    @Test
    void theApiBackendWhenSelected() {
        assertInstanceOf(AnthropicApiFixer.class, with(Map.of("AI_BACKEND", "anthropic_api")).fixer());
    }

    @Test
    void theCodexBackendWhenSelected() {
        assertInstanceOf(CodexCliFixer.class, with(Map.of("AI_BACKEND", "codex_cli")).fixer());
    }

    @Test
    void theAntigravityBackendWhenSelected() {
        assertInstanceOf(AntigravityCliFixer.class, with(Map.of("AI_BACKEND", "antigravity_cli")).fixer());
    }

    @Test
    void theOpenAiBackendWhenSelected() {
        assertInstanceOf(OpenAiApiFixer.class, with(Map.of("AI_BACKEND", "openai_api")).fixer());
    }

    @Test
    @DisplayName("a model served on this machine is the same adapter, pointed somewhere else")
    void theLocalBackendIsTheOpenAiOne() {
        assertInstanceOf(OpenAiApiFixer.class, with(Map.of("AI_BACKEND", "local")).fixer());
    }

    @Test
    @DisplayName("an unknown backend is one clear line, not a stack trace")
    void anUnknownBackendNamesTheValidOnes() {
        IllegalArgumentException thrown = assertThrows(
                IllegalArgumentException.class, () -> with(Map.of("AI_BACKEND", "made-up")).fixer());
        assertTrue(thrown.getMessage().contains("made-up"));
        assertTrue(thrown.getMessage().contains("claude_cli"));
        assertTrue(thrown.getMessage().contains("anthropic_api"));
        assertTrue(thrown.getMessage().contains("codex_cli"));
        assertTrue(thrown.getMessage().contains("antigravity_cli"));
        assertTrue(thrown.getMessage().contains("openai_api"));
        assertTrue(thrown.getMessage().contains("local"));
    }

    @Test
    void theCatalogReaderIsChosenByExtension() {
        assertInstanceOf(JsonRuleCatalog.class, with(Map.of()).ruleCatalog());
        assertInstanceOf(MarkdownRuleCatalog.class, with(Map.of("RULES_CATALOG", "/rules.md")).ruleCatalog());
        assertInstanceOf(MarkdownRuleCatalog.class, with(Map.of("RULES_CATALOG", "/RULES.MARKDOWN")).ruleCatalog());
    }

    @Test
    @DisplayName("with no catalog the prompt falls back to the findings, as it did before catalogs existed")
    void rulesContextSurvivesAMissingCatalog() {
        String context = with(Map.of("RULES_CATALOG", "/nowhere/rules.json")).rulesContext();
        assertTrue(context.contains("JAVA"));
        assertTrue(context.contains("howToSolve"));
    }

    @Test
    void everythingElseIsWiredWithoutComplaint() {
        Composition composition = with(Map.of());
        assertEquals(true, composition.versionControl() != null);
        assertEquals(true, composition.testRunner() != null);
        assertEquals(true, composition.fixLoop() != null);
    }

    @Test
    @DisplayName("the hooks can analyze each component under the profile its own sources call for")
    void theProfileCanBeChosenPerComponent() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "", ""));
        Configuration configuration = new Configuration(Map.of(), Path.of("/repo/sheriff-mcp-java"));
        Composition composition = new Composition(configuration, runner,
                component -> component.equals("web") ? "TYPESCRIPT" : "JAVA");
        composition.analyzerFor("web").analyze();
        assertTrue(runner.command().contains("TYPESCRIPT"), runner.command().toString());
    }

    @Test
    @DisplayName("the profile a component is analyzed under is the one its verdicts are filed under")
    void namesTheProfileOfEachComponent() {
        Configuration configuration = new Configuration(Map.of(), Path.of("/repo/sheriff-mcp-java"));
        Composition composition = new Composition(configuration,
                FakeProcessRunner.always(ProcessOutcome.completed(0, "", "")),
                component -> component.equals("web") ? "TYPESCRIPT" : "JAVA");
        assertEquals("TYPESCRIPT", composition.profileFor("web"));
        assertEquals("JAVA", composition.profileFor("api"));
        assertEquals("JAVA", composition.profileFor(""));
        Configuration mock = new Configuration(Map.of("SHERIFF_MOCK", "1", "SHERIFF_TEST_TYPE", "VUEJS"),
                Path.of("/repo/sheriff-mcp-java"));
        assertEquals("VUEJS", new Composition(mock, FakeProcessRunner.always(ProcessOutcome.completed(0, "", "")),
                component -> "JAVA").profileFor("web"), "the mock analyzes under the configured profile");
    }

    @Test
    void withoutAChoiceEveryComponentGetsTheConfiguredProfile() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "", ""));
        Configuration configuration = new Configuration(
                Map.of("SHERIFF_TEST_TYPE", "VUEJS"), Path.of("/repo/sheriff-mcp-java"));
        new Composition(configuration, runner).analyzerFor("web").analyze();
        assertTrue(runner.command().contains("VUEJS"));
    }

    @Test
    @DisplayName("a catalog named by hand, or no home to cache in, leaves the catalog as configured")
    void provisioningLeavesAConfiguredCatalogAlone(@TempDir Path cache) {
        Composition byHand = with(Map.of("RULES_CATALOG", "/mine.json", "XDG_CACHE_HOME", cache.toString()));
        byHand.provisionCatalog();
        assertEquals(Path.of("/mine.json"), byHand.catalogPath());
        Composition homeless = with(Map.of());
        homeless.provisionCatalog();
        assertEquals(Path.of("/repo/sheriff-mcp-java/rules_catalog.json").toAbsolutePath(), homeless.catalogPath());
    }

    @Test
    @DisplayName("with no catalog beside the agent, the one already in the shared cache is read")
    void provisioningFindsTheSharedCache(@TempDir Path cacheHome) throws IOException {
        Path cached = cacheHome.resolve("sheriff-mcp/rules_catalog.json");
        Files.createDirectories(cached.getParent());
        Files.writeString(cached, "{\"rules\":[{\"code\":\"CACHED\",\"description\":\"d\"}]}");
        Composition composition = with(Map.of("XDG_CACHE_HOME", cacheHome.toString()));
        composition.provisionCatalog();
        assertEquals(cached, composition.catalogPath());
        assertEquals("CACHED", composition.ruleCatalog().allRules().get(0).code());
    }

    @Test
    @DisplayName("the measure of a change's tests is the composition's own, and its Stop hook is built with it")
    void theChangeCoverageIsWired() {
        Composition composition = with(Map.of("SHERIFF_STOP_RUNS_TESTS", "1"));
        assertSame(composition, composition.withChangeCoverage(component -> null));
        assertNotNull(composition.stopHook());
    }

    @Test
    @DisplayName("while an idea is tried out, neither the Stop hook nor the repair loop runs the tests, even when asked to")
    void aPrototypeRunsNoTestsAtTheStop() {
        Configuration configuration = new Configuration(Map.of("SHERIFF_STOP_RUNS_TESTS", "1", "SHERIFF_PROTOTYPE",
                "1"), Path.of("/repo/sheriff-mcp-java"));
        assertTrue(configuration.prototype());
        assertFalse(configuration.stopRunsTests());
        Configuration checking = new Configuration(Map.of("SHERIFF_STOP_RUNS_TESTS", "1", "SHERIFF_PROTOTYPE", "0"),
                Path.of("/repo/sheriff-mcp-java"));
        assertFalse(checking.prototype());
        assertTrue(checking.stopRunsTests());
        assertFalse(new Configuration(Map.of(), Path.of("/repo/sheriff-mcp-java")).stopRunsTests());
        Composition composition = new Composition(configuration,
                FakeProcessRunner.always(ProcessOutcome.completed(0, "", "")));
        assertNotNull(composition.stopHook());
        assertFalse(composition.testRunner().run().ran(), "the repair loop leaves the tests alone");
        assertTrue(new Composition(checking, FakeProcessRunner.always(ProcessOutcome.completed(0, "", "")))
                .testRunner().run().ran());
    }

    @Test
    @DisplayName("a run's report goes where it was asked, or with SHERIFF_REPORT to a file of its own, or nowhere")
    void theReportGoesWhereItWasAskedOrToTheReportsFolder() {
        Path asked = Path.of("/tmp/run.json");
        assertEquals(Optional.of(asked), with(Map.of()).loopReportFile(Optional.of(asked)));
        assertEquals(Optional.empty(), with(Map.of("HOME", "/home/a")).loopReportFile(Optional.empty()));
        Path own = with(Map.of("SHERIFF_REPORT", "1", "HOME", "/home/a", "SHERIFF_COMPONENT", "web/app",
                "AI_BACKEND", "codex_cli")).loopReportFile(Optional.empty()).orElseThrow();
        assertEquals(Path.of("/home/a/.local/state/sheriff-mcp/reports"), own.getParent());
        assertTrue(own.getFileName().toString().matches("loop-\\d{8}-\\d{6}-web_app-codex_cli\\.json"),
                own.toString());
    }

    @Test
    @DisplayName("a report says what the run was asked to do with, the mock's run included")
    void theReportsContextNamesTheRunsKnobs() {
        Map<String, Object> context = with(Map.of("SHERIFF_MOCK", "1", "SHERIFF_MAX_ERRORS_PER_BATCH", "20"))
                .reportContext(new LoopSettings(null, true, 0, BatchGrouping.RULE, 20), true);
        @SuppressWarnings("unchecked")
        Map<String, Object> knobs = (Map<String, Object>) context.get("configuration");
        assertEquals("rule", knobs.get("batch_by"));
        assertEquals(20, knobs.get("max_errors_per_batch"));
        assertEquals(false, knobs.get("sheriff_fixers"), "the mock has no fixers");
        assertEquals(true, knobs.get("mock"));
        assertEquals(Map.of(), context.get("sheriff"));
        assertTrue(context.containsKey("project"));
    }
}
