package com.kaizten.sheriff.infrastructure.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaizten.sheriff.domain.valueobject.LoopSettings;
import com.kaizten.sheriff.infrastructure.config.Composition;
import com.kaizten.sheriff.infrastructure.config.Configuration;
import com.kaizten.sheriff.infrastructure.process.FakeProcessRunner;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the command line: the modes it offers, the exit codes it returns,
 * and the reports it writes. The decisions behind those answers are tested
 * where they live; this is about the wiring and the contract a pipeline sees.
 */
class AgentTests {

    private static final String SHERIFF_OUTPUT = """
            Execution results:
            \tErrors: 1
            [ {"file":"/data/app/A.java","description":"Method 'a' has no JavaDoc",
               "howToSolve":"Add one.","referenceCode":"","type":"ERROR"} ]
            """;

    @TempDir
    private Path workspace;

    private String printed = "";

    private int run(Map<String, String> environment, ProcessOutcome outcome, List<String> arguments) {
        Configuration configuration = new Configuration(environment, workspace);
        Agent agent = new Agent(configuration,
                new Composition(configuration, FakeProcessRunner.always(outcome)));
        PrintStream original = System.out;
        PrintStream originalError = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream both = new PrintStream(captured, true, StandardCharsets.UTF_8);
        System.setOut(both);
        System.setErr(both);
        try {
            return agent.run(arguments);
        } finally {
            System.setOut(original);
            System.setErr(originalError);
            printed = captured.toString(StandardCharsets.UTF_8);
        }
    }

    @Test
    @DisplayName("inside the loop's own assistant, both hooks stand aside without running Sheriff")
    void hooksStandAsideForTheAgentsOwnAssistant() {
        ProcessOutcome failing = ProcessOutcome.completed(0, SHERIFF_OUTPUT, "");
        Map<String, String> insideTheLoop = Map.of("SHERIFF_AGENT_RUNNING", "1");
        Configuration configuration = new Configuration(insideTheLoop, workspace);
        FakeProcessRunner processes = FakeProcessRunner.always(failing);
        Agent agent = new Agent(configuration, new Composition(configuration, processes));
        assertEquals(0, agent.run(List.of("--hook-gate")));
        assertEquals(0, agent.run(List.of("--hook-stop")));
        assertEquals(List.of(), processes.commands());
    }

    @Test
    @DisplayName("with no image here, a hook lets the edit through at once rather than stall on a 4 GB pull")
    void aHookWithNoImageLetsTheEditThrough() {
        assertEquals(0, run(Map.of(), ProcessOutcome.completed(1, "", "Error: No such image"), List.of("--hook-gate")));
        assertTrue(printed.contains("is not on this machine"), printed);
    }

    @Test
    @DisplayName("a hook that cannot run lets the edit through, because exit 2 is how a hook blocks")
    void aMisconfiguredHookFailsOpen() {
        java.io.InputStream originalIn = System.in;
        PrintStream originalErr = System.err;
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        String edit = "{\"tool_name\":\"Edit\",\"tool_input\":{\"file_path\":\""
                + workspace.getParent().resolve("app/src/A.java").toString().replace("\\", "\\\\") + "\"}}";
        System.setIn(new java.io.ByteArrayInputStream(edit.getBytes(StandardCharsets.UTF_8)));
        System.setErr(new PrintStream(errors, true, StandardCharsets.UTF_8));
        try {
            Map<String, String> broken = Map.of("SHERIFF_TIMEOUT", "abc");
            assertEquals(0, run(broken, ProcessOutcome.completed(0, SHERIFF_OUTPUT, ""), List.of("--hook-gate")));
        } finally {
            System.setIn(originalIn);
            System.setErr(originalErr);
        }
        assertTrue(printed.contains("SHERIFF_TIMEOUT"), printed);
    }

    @Test
    void aFlagThatIsNotANumberNamesItself() {
        assertEquals(Agent.MISUSE, run(Map.of(), ProcessOutcome.completed(0, "", ""),
                List.of("--max-iterations", "many")));
        assertTrue(printed.contains("--max-iterations"));
    }

    @Test
    @DisplayName("a clean check exits 0, which is what a pipeline gate reads")
    void checkOnlyOnACleanComponent() {
        assertEquals(Agent.SUCCESS, run(Map.of(), ProcessOutcome.completed(0, "", ""), List.of("--check-only")));
        assertTrue(printed.contains("no errors"));
    }

    @Test
    void checkOnlyReportsTheFindingsAndExitsOne() {
        assertEquals(Agent.FAILURE,
                run(Map.of(), ProcessOutcome.completed(0, SHERIFF_OUTPUT, ""), List.of("--check-only")));
        assertTrue(printed.contains("1 error"));
        assertTrue(printed.contains("app/A.java"));
    }

    @Test
    @DisplayName("Sheriff failing to run is a failure, never a clean result")
    void checkOnlyWhenSheriffCannotRun() {
        assertEquals(Agent.FAILURE, run(Map.of(),
                ProcessOutcome.unavailable("'docker' is not installed or not on the PATH."),
                List.of("--check-only")));
        assertTrue(printed.contains("could not be downloaded: 'docker' is not installed"), printed);
    }

    @Test
    void checkOnlyWritesAJsonReport() throws IOException {
        Path report = workspace.resolve("report.json");
        run(Map.of(), ProcessOutcome.completed(0, SHERIFF_OUTPUT, ""),
                List.of("--check-only", "--json-report", report.toString()));
        JsonNode written = new ObjectMapper().readTree(Files.readString(report));
        assertFalse(written.get("ok").asBoolean());
        assertEquals(1, written.get("total").asInt());
        assertEquals(1, written.get("errors").size());
    }

    @Test
    void rulesModeNeedsNoSheriffAtAll() throws IOException {
        Path catalog = workspace.resolve("rules_catalog.json");
        Files.writeString(catalog, """
                {"rules":[
                  {"code":"InProfile","description":"yes","profiles":["JAVA"]},
                  {"code":"Elsewhere","description":"no","profiles":["TYPESCRIPT"]}]}
                """);
        assertEquals(Agent.SUCCESS, run(Map.of(), ProcessOutcome.unavailable("no docker here"), List.of("--rules")));
        assertTrue(printed.contains("InProfile"));
        assertFalse(printed.contains("Elsewhere"));
    }

    @Test
    void rulesModeSearchesTheWholeCatalogWhenGivenAQuery() throws IOException {
        Files.writeString(workspace.resolve("rules_catalog.json"), """
                {"rules":[
                  {"code":"InProfile","description":"javadoc thing","profiles":["JAVA"]},
                  {"code":"Elsewhere","description":"javadoc other","profiles":["TYPESCRIPT"]}]}
                """);
        run(Map.of(), ProcessOutcome.completed(0, "", ""), List.of("--rules", "javadoc"));
        assertTrue(printed.contains("Elsewhere"));
    }

    private void catalogWithAFixer() throws IOException {
        Files.writeString(workspace.resolve("rules_catalog.json"), """
                {"rules":[
                  {"code":"WithScript","description":"a","fixer":"java/tests/a/a.py","profiles":["JAVA"]},
                  {"code":"NoFixer","description":"b","fixer":"","profiles":["JAVA"]},
                  {"code":"CompiledFixer","description":"c","fixer":"java/f/target/t.jar","profiles":["JAVA"]}]}
                """);
    }

    @Test
    @DisplayName("--fixer prints the accepted code, which no description states")
    void fixerModePrintsTheScriptAndWhereItCameFrom() throws IOException {
        catalogWithAFixer();
        assertEquals(Agent.SUCCESS, run(Map.of(),
                ProcessOutcome.completed(0, "print('the canonical snippet')\n", ""),
                List.of("--fixer", "WithScript")));
        assertTrue(printed.contains("/sheriff-fixers/java/tests/a/a.py"));
        assertTrue(printed.contains("the canonical snippet"));
    }

    @Test
    void fixerModeMatchesACodeTypedInAnyCase() throws IOException {
        catalogWithAFixer();
        assertEquals(Agent.SUCCESS,
                run(Map.of(), ProcessOutcome.completed(0, "x", ""), List.of("--fixer", "withscript")));
    }

    @Test
    void fixerModeSaysSoWhenTheRuleHasNoFixer() throws IOException {
        catalogWithAFixer();
        assertEquals(Agent.FAILURE,
                run(Map.of(), ProcessOutcome.completed(0, "x", ""), List.of("--fixer", "NoFixer")));
        assertTrue(printed.contains("no fixer script"));
    }

    @Test
    @DisplayName("one fixer ships as a jar, and cat would spray binary at the terminal")
    void fixerModeDoesNotPrintACompiledFixer() throws IOException {
        catalogWithAFixer();
        assertEquals(Agent.FAILURE,
                run(Map.of(), ProcessOutcome.completed(0, "x", ""), List.of("--fixer", "CompiledFixer")));
        assertTrue(printed.contains("not a script"));
    }

    @Test
    void fixerModePointsAtTheSearchModeForAnUnknownCode() throws IOException {
        catalogWithAFixer();
        assertEquals(Agent.FAILURE,
                run(Map.of(), ProcessOutcome.completed(0, "x", ""), List.of("--fixer", "NoSuchRule")));
        assertTrue(printed.contains("--rules NoSuchRule"));
    }

    @Test
    void fixerModeReportsAReadFailureInsteadOfCrashing() throws IOException {
        catalogWithAFixer();
        assertEquals(Agent.FAILURE, run(Map.of(),
                ProcessOutcome.unavailable("docker is not installed"),
                List.of("--fixer", "WithScript")));
        assertTrue(printed.contains("docker is not installed"));
    }

    @Test
    void fixerModeWithoutACatalog() {
        assertEquals(Agent.FAILURE,
                run(Map.of(), ProcessOutcome.completed(0, "", ""), List.of("--fixer", "WithScript")));
        assertTrue(printed.contains("No rule catalog"));
    }

    @Test
    @DisplayName("no catalog says how to make one instead of pretending there are no rules")
    void rulesModeWithoutACatalog() {
        assertEquals(Agent.FAILURE, run(Map.of(), ProcessOutcome.completed(0, "", ""), List.of("--rules")));
        assertTrue(printed.contains("No rule catalog"));
    }

    @Test
    void theFullLoopReportsWhyItStopped() throws IOException {
        Path report = workspace.resolve("run.json");
        int code = run(Map.of("SHERIFF_AGENT_GIT_SAFETY", "0", "SHERIFF_MAX_ITERATIONS", "1"),
                ProcessOutcome.completed(0, SHERIFF_OUTPUT, ""),
                List.of("--json-report", report.toString()));
        assertEquals(Agent.FAILURE, code);
        JsonNode written = new ObjectMapper().readTree(Files.readString(report));
        assertEquals("iterations_exhausted", written.get("stopped_reason").asText());
        assertFalse(written.get("ok").asBoolean());
    }

    @Test
    @DisplayName("the command line overrides configuration, and nothing else does")
    void commandLineOverridesTheConfiguredSettings() {
        Configuration configuration = new Configuration(
                Map.of("SHERIFF_MAX_FILES_PER_BATCH", "5"), workspace);
        Agent agent = new Agent(configuration,
                new Composition(configuration, FakeProcessRunner.always(ProcessOutcome.completed(0, "", ""))));
        LoopSettings overridden = agent.settingsFor(
                List.of("--max-iterations", "2", "--max-files-per-batch", "1", "--no-git-safety"));
        assertEquals(2, overridden.maxIterations());
        assertEquals(1, overridden.maxFilesPerBatch());
        assertFalse(overridden.useGitSafety());
        LoopSettings untouched = agent.settingsFor(List.of());
        assertEquals(5, untouched.maxFilesPerBatch());
        assertTrue(untouched.useGitSafety());
    }

    @Test
    @DisplayName("the three-way check is green only when all three are")
    void fullCheckReportsEachOfItsThreeParts() {
        assertEquals(Agent.SUCCESS, run(Map.of("VERIFICATION_TEST_CMD", "true"),
                ProcessOutcome.completed(0, "", ""), List.of("--full-check")));
        assertTrue(printed.contains("ALL GREEN"));
        assertTrue(printed.contains("1/3"));
        assertTrue(printed.contains("3/3"));
    }

    @Test
    @DisplayName("a red Sheriff fails the whole check even when everything builds")
    void fullCheckFailsWhenSheriffIsUnhappy() {
        assertEquals(Agent.FAILURE, run(Map.of("VERIFICATION_TEST_CMD", "true"),
                ProcessOutcome.completed(0, SHERIFF_OUTPUT, ""), List.of("--full-check")));
        assertTrue(printed.contains("SOMETHING IS RED"));
        assertTrue(printed.contains("Sheriff: FAIL"));
    }

    @Test
    void helpIsAModeOfItsOwn() {
        assertEquals(Agent.SUCCESS, run(Map.of(), ProcessOutcome.completed(0, "", ""), List.of("--help")));
        assertTrue(printed.contains("--check-only"));
    }

    @Test
    @DisplayName("an option's value is not swallowed from the next option")
    void readsOptionValues() {
        assertEquals("javadoc", Agent.valueAfter(List.of("--rules", "javadoc"), "--rules"));
        assertEquals("", Agent.valueAfter(List.of("--rules", "--json-report", "r.json"), "--rules"));
        assertEquals("", Agent.valueAfter(List.of("--rules"), "--rules"));
        assertEquals("", Agent.valueAfter(List.of(), "--rules"));
    }

    @Test
    @DisplayName("a clean component with sources in it passes, as it always did")
    void aCleanComponentStillPasses() throws IOException {
        Files.createDirectories(workspace.resolve("app"));
        Files.writeString(workspace.resolve("app").resolve("A.java"), "class A {}");
        assertEquals(Agent.SUCCESS, run(Map.of("TARGET_REPO", workspace.toString(), "SHERIFF_COMPONENT", "app"),
                ProcessOutcome.completed(0, "", ""), List.of("--check-only")));
        assertTrue(printed.contains("no errors"), printed);
    }

    @Test
    @DisplayName("zero errors on a component with nothing of that language is not a pass")
    void nothingToAnalyseIsNotACleanComponent() throws IOException {
        Files.createDirectories(workspace.resolve("app"));
        Files.writeString(workspace.resolve("app").resolve("index.ts"), "export const a = 1;");
        assertEquals(Agent.FAILURE, run(Map.of("TARGET_REPO", workspace.toString(), "SHERIFF_COMPONENT", "app"),
                ProcessOutcome.completed(0, "", ""), List.of("--check-only")));
        assertTrue(printed.contains("Nothing was analyzed"), printed);
        assertTrue(printed.contains("not the same as passing"), printed);
    }

    @Test
    @DisplayName("a misconfigured backend exits 2: the request was wrong, not the code")
    void anUnknownBackendIsMisuseNotFailure() {
        assertEquals(Agent.MISUSE,
                run(Map.of("AI_BACKEND", "nope"), ProcessOutcome.completed(0, "", ""), List.of()));
        assertTrue(printed.contains("error:"));
    }
}
