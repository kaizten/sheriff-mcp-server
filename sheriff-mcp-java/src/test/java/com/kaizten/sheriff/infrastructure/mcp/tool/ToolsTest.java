package com.kaizten.sheriff.infrastructure.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.enumerate.StopReason;
import com.kaizten.sheriff.domain.valueobject.RunSummary;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import com.kaizten.sheriff.domain.valueobject.VerificationResult;
import com.kaizten.sheriff.infrastructure.docker.ImageProvisioning;
import com.kaizten.sheriff.infrastructure.docker.SheriffImage;
import com.kaizten.sheriff.infrastructure.mcp.McpConfig;
import com.kaizten.sheriff.infrastructure.mcp.ServerVersion;
import com.kaizten.sheriff.infrastructure.mcp.SheriffRunner;
import com.kaizten.sheriff.infrastructure.mcp.protocol.ToolOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The read-only/deterministic tools end to end, without Docker: {@link
 * SheriffRunner} is given a {@link ProcessRunner} test double instead of a
 * real one. {@code sheriff_autofix} is covered separately, since it builds a
 * real {@link com.kaizten.sheriff.infrastructure.config.Composition}
 * rather than going through {@link SheriffRunner}.
 */
final class ToolsTest {

    @TempDir
    Path repository;

    @TempDir
    Path state;

    /**
     * The component most tests name, as a folder: a component that is not one
     * is refused before Sheriff runs.
     */
    @org.junit.jupiter.api.BeforeEach
    void layOutTheComponent() throws IOException {
        Files.createDirectories(repository.resolve("app"));
    }

    private Tools tools(List<SheriffRule> catalog, ProcessOutcome... outcomes) {
        McpConfig config = new McpConfig(Map.of("SHERIFF_REPO", repository.toString(), "XDG_STATE_HOME", state.toString()));
        SheriffRunner runner = new SheriffRunner(new QueuedProcessRunner(outcomes), config.image(), repository, Duration.ofSeconds(30));
        return new Tools(config, runner, () -> catalog);
    }

    @Test
    void listsTheFiveToolsUnderTheDefaultPrefix() {
        List<Map<String, Object>> definitions = tools(List.of()).definitions();

        List<Object> names = definitions.stream().map(definition -> definition.get("name")).toList();
        assertEquals(List.of("sheriff_test", "sheriff_fix", "sheriff_guidelines", "sheriff_autofix", "sheriff_task"),
                names);
    }

    @Test
    void theToolsThatOnlyReadSaySoAndTheOthersSayHowTheyWrite() {
        Map<Object, Object> hints = new LinkedHashMap<>();
        for (Map<String, Object> definition : tools(List.of()).definitions()) {
            hints.put(definition.get("name"), definition.get("annotations"));
        }

        for (String reader : List.of("sheriff_test", "sheriff_guidelines", "sheriff_task")) {
            Map<?, ?> annotations = (Map<?, ?>) hints.get(reader);
            assertEquals(true, annotations.get("readOnlyHint"), reader);
            assertEquals(false, annotations.get("openWorldHint"), reader);
            assertFalse(annotations.containsKey("destructiveHint"), reader);
        }
        Map<?, ?> fix = (Map<?, ?>) hints.get("sheriff_fix");
        assertEquals(List.of(false, true, false), List.of(fix.get("readOnlyHint"), fix.get("destructiveHint"),
                fix.get("openWorldHint")));
        Map<?, ?> autofix = (Map<?, ?>) hints.get("sheriff_autofix");
        assertEquals(List.of(false, false, true), List.of(autofix.get("readOnlyHint"), autofix.get("destructiveHint"),
                autofix.get("openWorldHint")));
        assertTrue(hints.values().stream().allMatch(annotations -> ((Map<?, ?>) annotations).get("title") != null));
    }

    @Test
    void anUnknownToolFailsWithTheNamesOfTheAvailableOnes() {
        ToolOutcome outcome = tools(List.of()).call("sheriff_bogus", Map.of());

        assertTrue(outcome.isError());
        assertTrue(outcome.text().contains("sheriff_test"));
    }

    @Test
    void testingWithNoComponentAndNoDefaultFails() {
        ToolOutcome outcome = tools(List.of()).call("sheriff_test", Map.of());

        assertTrue(outcome.isError());
        assertTrue(outcome.text().contains("No component given"));
    }

    @Test
    void guidelinesWithNoCatalogSaysSoRatherThanAnsweringEmpty() {
        ToolOutcome outcome = tools(List.of()).call("sheriff_guidelines", Map.of());

        assertFalse(outcome.isError());
        assertTrue(outcome.text().contains("No rule catalog is available"));
    }

    @Test
    void guidelinesListsWhatAProfileEnforces() {
        SheriffRule rule = new SheriffRule("RULE_A", "template", "solve it", "java", "", "", List.of("JAVA"));
        Tools tools = tools(List.of(rule));

        ToolOutcome outcome = tools.call("sheriff_guidelines", Map.of("profile", "JAVA"));

        assertFalse(outcome.isError());
        assertTrue(outcome.text().contains("RULE_A"));
    }

    @Test
    void guidelinesForAnUnknownReferenceCodePointsAtSearchingInstead() {
        SheriffRule rule = new SheriffRule("RULE_A", "template", "", "java", "", "", List.of("JAVA"));
        Tools tools = tools(List.of(rule));

        ToolOutcome outcome = tools.call("sheriff_guidelines", Map.of("reference_code", "NO_SUCH_RULE"));

        assertFalse(outcome.isError());
        assertTrue(outcome.text().contains("sheriff_guidelines"));
    }

    @Test
    void testingAComponentWithNoMatchingSourcesIsReportedAsNothingAnalyzedRatherThanAsAPass() throws IOException {
        Files.createDirectories(repository.resolve("empty-component"));
        Tools tools = tools(List.of(), ProcessOutcome.completed(0, "", ""));

        ToolOutcome outcome = tools.call("sheriff_test", Map.of("component", "empty-component", "profile", "JAVA"));

        assertTrue(outcome.isError());
        assertTrue(outcome.text().contains("Nothing was analyzed"));
    }

    @Test
    void testingAComponentWithSourcesAndNoErrorsIsAPass() throws IOException {
        Path component = repository.resolve("clean-component");
        Files.createDirectories(component);
        Files.writeString(component.resolve("Clean.java"), "class Clean {}");
        Tools tools = tools(List.of(), ProcessOutcome.completed(0, "", ""));

        ToolOutcome outcome = tools.call("sheriff_test", Map.of("component", "clean-component", "profile", "JAVA"));

        assertFalse(outcome.isError());
        assertTrue(outcome.text().contains("0 errors"));
    }

    /**
     * A {@link ProcessRunner} that answers with one canned outcome per call,
     * in the order given.
     */
    @Test
    void withSeveralComponentsAndNoneNamedTheAnswerListsThem() throws IOException {
        Files.createDirectories(repository.resolve("api/src"));
        Files.writeString(repository.resolve("api/src/A.java"), "class A {}");
        Files.createDirectories(repository.resolve("web/src"));
        Files.writeString(repository.resolve("web/src/a.ts"), "export {}");
        ToolOutcome outcome = tools(List.of()).call("sheriff_test", Map.of());
        assertTrue(outcome.isError());
        assertTrue(outcome.text().contains("api, web"), outcome.text());
    }

    /**
     * A component with a Java source and a Maven build, so it has something
     * to analyze and tests to run.
     */
    private void mavenComponent(String name) throws IOException {
        Path component = repository.resolve(name);
        Files.createDirectories(component);
        Files.writeString(component.resolve("A.java"), "class A {}");
        Files.writeString(component.resolve("pom.xml"), "<project/>");
    }

    private Tools toolsWithTests(VerificationResult testResult, ProcessOutcome... outcomes) {
        McpConfig config = new McpConfig(Map.of("SHERIFF_REPO", repository.toString(), "XDG_STATE_HOME", state.toString()));
        SheriffRunner runner = new SheriffRunner(new QueuedProcessRunner(outcomes), config.image(), repository,
                Duration.ofSeconds(30));
        return new Tools(config, runner, List::of, config::rulesCatalog, component -> testResult);
    }

    @Test
    @DisplayName("sheriff_fix with verify says when the repair broke the project's tests")
    void aRepairThatBreaksTheTestsIsReported() throws IOException {
        mavenComponent("app");
        String before = "[{\"file\":\"/data/app/A.java\",\"description\":\"d\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"R\",\"type\":\"ERROR\"}]";
        Tools tools = toolsWithTests(VerificationResult.failed("COMPILATION ERROR: cannot find symbol"),
                ProcessOutcome.completed(0, before, ""), ProcessOutcome.completed(1, "fixed", ""),
                ProcessOutcome.completed(1, "fixed", ""), ProcessOutcome.completed(0, "", ""));
        ToolOutcome outcome = tools.call("sheriff_fix", Map.of("component", "app", "verify", "true"));
        assertTrue(outcome.text().contains("FAIL after the repair"), outcome.text());
        assertTrue(outcome.text().contains("cannot find symbol"));
    }

    @Test
    @DisplayName("tests that fail after a repair that changed no file were failing before it, and are not blamed on it")
    void failingTestsAfterAnUnchangedRepairAreNotBlamedOnIt() throws IOException {
        mavenComponent("app");
        String before = "[{\"file\":\"/data/app/A.java\",\"description\":\"d\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"R\",\"type\":\"ERROR\"}]";
        String sameHashes = "{\"/data/app/A.java\": {\"hash\": \"aaa\"}}";
        McpConfig config = new McpConfig(Map.of("SHERIFF_REPO", repository.toString(), "XDG_STATE_HOME", state.toString()));
        SheriffRunner runner = new SheriffRunner(new QueuedProcessRunner(sameHashes,
                ProcessOutcome.completed(0, before, ""), ProcessOutcome.completed(1, "", ""),
                ProcessOutcome.completed(1, "", ""), ProcessOutcome.completed(0, before, "")), config.image(),
                repository, Duration.ofSeconds(30));
        Tools tools = new Tools(config, runner, List::of, config::rulesCatalog,
                component -> VerificationResult.failed("expected: <25.0> but was: <0.0>"));

        String text = tools.call("sheriff_fix", Map.of("component", "app", "verify", "true")).text();

        assertTrue(text.contains("changed no file, so they were already failing before it"), text);
        assertFalse(text.contains("FAIL after the repair"), text);
    }

    @Test
    @DisplayName("a fixer that failed is not offered again in the same answer, or a model would loop on it")
    void aFailedFixerIsNotOfferedAgain() {
        String remaining = "[{\"file\":\"/data/app/A.java\",\"description\":\"d\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"BracesForStatements\",\"type\":\"ERROR\"}]";
        String failed = "Could not apply fixer for error 'BracesForStatements' in file '/data/app/A.java'\n"
                + "Error: Fixer script does not exist: 'braces.jar'\n";
        SheriffRule braces = new SheriffRule("BracesForStatements", "Braces are not used", "", "java", "",
                "braces.jar", List.of("JAVA"));
        McpConfig config = new McpConfig(Map.of("SHERIFF_REPO", repository.toString(), "XDG_STATE_HOME", state.toString()));
        SheriffRunner runner = new SheriffRunner(new QueuedProcessRunner(
                ProcessOutcome.completed(0, remaining, ""), ProcessOutcome.completed(1, failed, ""),
                ProcessOutcome.completed(0, remaining, "")), config.image(), repository, Duration.ofSeconds(30));
        Tools tools = new Tools(config, runner, () -> List.of(braces));

        String text = tools.call("sheriff_fix", Map.of("component", "app", "reference_code", "BracesForStatements"))
                .text();

        assertTrue(text.contains("its fixer did not repair it"), text);
        assertFalse(text.contains("reference_code set to one of: BracesForStatements"), text);
    }

    @Test
    @DisplayName("a fixer that ran and left its rule is not offered again either: it says nothing when it does nothing")
    void aFixerThatRepairedNothingIsNotOfferedAgain() {
        String remaining = "[{\"file\":\"/data/app/A.java\",\"description\":\"d\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"JavaHardcodedValueInComparisonMessage1\",\"type\":\"ERROR\"}]";
        SheriffRule comparison = new SheriffRule("JavaHardcodedValueInComparisonMessage1", "Hardcoded value", "",
                "java", "", "java/comparison.py", List.of("JAVA"));
        McpConfig config = new McpConfig(Map.of("SHERIFF_REPO", repository.toString(), "XDG_STATE_HOME", state.toString()));
        SheriffRunner runner = new SheriffRunner(new QueuedProcessRunner(
                ProcessOutcome.completed(0, remaining, ""), ProcessOutcome.completed(1, "Fixing process completed.", ""),
                ProcessOutcome.completed(0, remaining, "")), config.image(), repository, Duration.ofSeconds(30));
        Tools tools = new Tools(config, runner, () -> List.of(comparison));

        String text = tools.call("sheriff_fix",
                Map.of("component", "app", "reference_code", "JavaHardcodedValueInComparisonMessage1")).text();

        assertTrue(text.contains("its fixer did not repair it, edit it by hand"), text);
        assertFalse(text.contains("can repair this one"), text);
    }

    @Test
    @DisplayName("an answer names one next step from the state: repair first while Sheriff can, never a choice")
    void anAnalysisEndsWithTheRepairWhenSheriffCanRepair() {
        String found = "[{\"file\":\"/data/app/A.java\",\"description\":\"d\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"EmptyLinesInMethod\",\"type\":\"ERROR\"}]";
        SheriffRule emptyLines = new SheriffRule("EmptyLinesInMethod", "Empty line", "", "java", "",
                "java/empty-lines.py", List.of("JAVA"));

        String text = tools(List.of(emptyLines), ProcessOutcome.completed(0, found, ""))
                .call("sheriff_test", Map.of("component", "app")).text();

        assertTrue(text.contains("Next step: call the sheriff_fix tool with component 'app'."), text);
        assertFalse(text.contains(" or "), "an alternative is a choice a model makes differently: " + text);
    }

    @Test
    @DisplayName("without a catalog, an analysis with errors still sends the model to the repair, not to its own hands")
    void anAnalysisAlwaysEndsWithTheRepair() {
        String found = "[{\"file\":\"/data/app/A.java\",\"description\":\"d\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"JavaLineCommentsChecker\",\"type\":\"ERROR\"}]";

        String text = tools(List.of(), ProcessOutcome.completed(0, found, ""))
                .call("sheriff_test", Map.of("component", "app")).text();

        assertTrue(text.contains("Next step: call the sheriff_fix tool with component 'app'."),
                "with no component named, the call failed in a repository of several modules: " + text);
    }

    @Test
    @DisplayName("the step is the answer's last line, after the task's id: a model reads an answer from its end")
    void theStepIsTheLastLine() {
        String found = "[{\"file\":\"/data/app/A.java\",\"description\":\"d\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"JavaLineCommentsChecker\",\"type\":\"ERROR\"}]";

        String text = tools(List.of(), ProcessOutcome.completed(0, found, ""))
                .call("sheriff_test", Map.of("component", "app")).text();

        assertTrue(text.strip().lines().reduce((first, second) -> second).orElseThrow().startsWith("Next step:"), text);
        assertTrue(text.contains("Task "), text);
        assertFalse(text.contains(state.toString()),
                "named in every answer, the folder sent a model to read Sheriff's raw JSON: " + text);
    }

    @Test
    @DisplayName("an answer that could not check anything names a step too")
    void aFailureEndsWithAStep() throws IOException {
        Path component = repository.resolve("python");
        Files.createDirectories(component);
        Files.writeString(component.resolve("suma.py"), "def suma(a, b):\n    return a + b\n");

        String text = tools(List.of(), ProcessOutcome.completed(0, "", ""))
                .call("sheriff_test", Map.of("component", "python", "profile", "JAVA")).text();

        assertTrue(text.contains("Next step: none: tell the user nothing was analyzed"), text);
    }

    @Test
    @DisplayName("a clean component's next step is its own tests, by the command that runs them")
    void aCleanAnalysisEndsWithTheTests() throws IOException {
        mavenComponent("app");

        String text = tools(List.of(), ProcessOutcome.completed(0, "", ""))
                .call("sheriff_test", Map.of("component", "app")).text();

        String absolute = repository.resolve("app").toAbsolutePath().normalize().toString();
        assertTrue(text.contains("Next step: run the project's tests (") && text.contains(absolute), text);
        assertFalse(text.contains(" -q "), "-q hides Tests run and BUILD SUCCESS from the model: " + text);
    }

    @Test
    @DisplayName("after a repair with no rule named every fixer has run, so nothing left is offered to it again")
    void aRepairWithNoRuleLeavesTheRestToHand() {
        String remaining = "[{\"file\":\"/data/app/A.java\",\"description\":\"d\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"EmptyLinesInMethod\",\"type\":\"ERROR\"}]";
        SheriffRule emptyLines = new SheriffRule("EmptyLinesInMethod", "Empty line", "", "java", "",
                "java/empty-lines.py", List.of("JAVA"));

        String text = tools(List.of(emptyLines), ProcessOutcome.completed(0, remaining, ""),
                ProcessOutcome.completed(1, "", ""), ProcessOutcome.completed(1, "", ""),
                ProcessOutcome.completed(0, remaining, "")).call("sheriff_fix", Map.of("component", "app")).text();

        assertFalse(text.contains("can repair this one"), text);
        assertTrue(text.contains("Next step: fix by hand every error listed above"), text);
    }

    @Test
    @DisplayName("what is left by hand comes with the code Sheriff accepts for it, before the step")
    void aRepairShowsTheAcceptedCodeForWhatIsLeft() {
        String remaining = "[{\"file\":\"/data/app/A.java\",\"description\":\"d\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"JavaJavaDocCommentInMethodMessage6\",\"type\":\"ERROR\"}]";

        String text = tools(List.of(), ProcessOutcome.completed(0, remaining, ""),
                ProcessOutcome.completed(1, "", ""), ProcessOutcome.completed(1, "", ""),
                ProcessOutcome.completed(0, remaining, "")).call("sheriff_fix", Map.of("component", "app")).text();

        assertTrue(text.contains("What Sheriff accepts") && text.contains("@param page"), text);
        assertTrue(text.indexOf("What Sheriff accepts") < text.indexOf("Next step:"), text);
    }

    @Test
    void withoutVerifyNoTestsRun() throws IOException {
        mavenComponent("app");
        Tools tools = toolsWithTests(VerificationResult.failed("should not be run"),
                ProcessOutcome.completed(0, "", ""), ProcessOutcome.completed(1, "fixed", ""),
                ProcessOutcome.completed(0, "", ""));
        String text = tools.call("sheriff_fix", Map.of("component", "app")).text();
        assertFalse(text.contains("should not be run") || text.contains("tests still pass"), text);
    }

    @Test
    @DisplayName("with no build tool, a repair says no tests were run, never that they pass")
    void withNoBuildToolNoTestsAreClaimed() throws IOException {
        Path component = repository.resolve("loose");
        Files.createDirectories(component);
        Files.writeString(component.resolve("A.java"), "class A {}");
        Tools tools = toolsWithTests(VerificationResult.passed("echo"),
                ProcessOutcome.completed(0, "", ""), ProcessOutcome.completed(1, "", ""),
                ProcessOutcome.completed(0, "", ""));
        String text = tools.call("sheriff_fix", Map.of("component", "loose", "verify", "true")).text();
        assertTrue(text.contains("no tests were run"), text);
        assertFalse(text.contains("still pass"), text);
    }

    @Test
    @DisplayName("sheriff_fix on a project in a language Sheriff does not check says nothing was analyzed")
    void fixingAProjectSheriffCannotReadSaysSo() throws IOException {
        Path component = repository.resolve("python");
        Files.createDirectories(component);
        Files.writeString(component.resolve("suma.py"), "def suma(a, b):\n    return a + b\n");
        Tools tools = toolsWithTests(VerificationResult.passed(""),
                ProcessOutcome.completed(0, "", ""), ProcessOutcome.completed(1, "", ""),
                ProcessOutcome.completed(0, "", ""));
        ToolOutcome outcome = tools.call("sheriff_fix", Map.of("component", "python", "profile", "JAVA"));
        assertTrue(outcome.isError());
        assertTrue(outcome.text().contains("Sheriff checks Java, TypeScript, Vue, Perl and Python"), outcome.text());
    }

    @Test
    void theAutofixSummaryWithNoTestsSaysSo() {
        RunSummary done = new RunSummary(true, StopReason.SUCCESS, 1, 3, false, List.of(), "", true);
        String text = SheriffAutofixTool.renderSummary("app", "JAVA", "claude_cli", done, false);
        assertTrue(text.contains("no tests were run"), text);
        assertFalse(text.contains("tests pass"), text);
    }

    @Test
    void theAutofixSummarySaysHowTheTestsStand() {
        RunSummary failing = new RunSummary(false, StopReason.STALLED, 2, 3, false, List.of(), "", false);
        assertTrue(SheriffAutofixTool.renderSummary("app", "JAVA", "claude_cli", failing).contains("FAIL"));
    }

    @Test
    void theAutofixSummaryNamesTheBranchTheRepositoryWasLeftOn() {
        RunSummary summary = new RunSummary(false, StopReason.STALLED, 2, 3, false, List.of(),
                "sheriff-agent/20260924-231558");
        String text = SheriffAutofixTool.renderSummary("app", "JAVA", "claude_cli", summary);
        assertTrue(text.contains("sheriff-agent/20260924-231558"));
    }

    @Test
    void anAutofixWithoutGitSafetyMentionsNoBranch() {
        RunSummary summary = new RunSummary(true, StopReason.SUCCESS, 1, 3, false, List.of());
        assertFalse(SheriffAutofixTool.renderSummary("app", "JAVA", "claude_cli", summary).contains("branch"));
    }

    @Test
    @DisplayName("every sheriff_test is a task: its answer carries the id, and sheriff_task can read it back")
    void aTestIsRecordedAsATask() throws IOException {
        Path component = repository.resolve("clean-component");
        Files.createDirectories(component);
        Files.writeString(component.resolve("Clean.java"), "class Clean {}");
        Tools tools = tools(List.of(), ProcessOutcome.completed(0, "", ""));

        String answer = tools.call("sheriff_test", Map.of("component", "clean-component", "profile", "JAVA")).text();
        String id = answer.substring(answer.indexOf("Task ") + "Task ".length(), answer.indexOf("Task ") + 13);
        String described = tools.call("sheriff_task", Map.of("id", id)).text();

        assertTrue(described.contains("sheriff_test component=clean-component profile=JAVA"), described);
        assertTrue(described.contains("State: completed"), described);
        assertTrue(Files.isRegularFile(state.resolve("sheriff-mcp/tasks").resolve(id).resolve("task.json")));
        assertTrue(Files.isRegularFile(state.resolve("sheriff-mcp/tasks").resolve(id).resolve("sheriff_errors.json")));
    }

    @Test
    @DisplayName("every task records the jar's version and the digest of the image it ran, to trace a result back")
    void aTaskRecordsWhatItRanWith() throws IOException {
        Path component = repository.resolve("clean-component");
        Files.createDirectories(component);
        Files.writeString(component.resolve("Clean.java"), "class Clean {}");
        ProcessRunner docker = (command, directory, environment, timeout) ->
                ProcessOutcome.completed(0, "[\"kaizten/sheriff@sha256:abc\"]", "");
        ImageProvisioning image = new ImageProvisioning(new SheriffImage(docker, "kaizten/sheriff:latest",
                name -> Optional.empty()), "never", new PrintStream(OutputStream.nullOutputStream()));
        image.now();
        McpConfig config = new McpConfig(Map.of("SHERIFF_REPO", repository.toString(), "XDG_STATE_HOME", state.toString()));
        SheriffRunner runner = new SheriffRunner(new QueuedProcessRunner(ProcessOutcome.completed(0, "", "")),
                config.image(), repository, Duration.ofSeconds(30));
        Tools tools = new Tools(config, runner, List::of, config::rulesCatalog, name -> VerificationResult.passed(""),
                image);

        String answer = tools.call("sheriff_test", Map.of("component", "clean-component", "profile", "JAVA")).text();
        String id = answer.substring(answer.indexOf("Task ") + "Task ".length(), answer.indexOf("Task ") + 13);
        String record = Files.readString(state.resolve("sheriff-mcp/tasks").resolve(id).resolve("task.json"));

        assertTrue(record.contains("\"server_version\" : \"" + ServerVersion.current() + "\""), record);
        assertTrue(record.contains("\"sheriff_image\" : \"kaizten/sheriff:latest\""), record);
        assertTrue(record.contains("\"sheriff_image_digest\" : \"sha256:abc\""), record);
    }

    @Test
    void sheriffTaskWithNoIdListsTheSessionsTasks() throws IOException {
        Files.createDirectories(repository.resolve("empty-component"));
        Tools tools = tools(List.of(), ProcessOutcome.completed(0, "", ""));
        tools.call("sheriff_test", Map.of("component", "empty-component", "profile", "JAVA"));

        String listing = tools.call("sheriff_task", Map.of()).text();

        assertTrue(listing.contains("failed"), listing);
        assertTrue(listing.contains("sheriff_test component=empty-component"), listing);
    }

    @Test
    @DisplayName("a sheriff_test that fails names its task, so the client can find what Sheriff exported")
    void aFailedTestNamesItsTask() throws IOException {
        Files.createDirectories(repository.resolve("empty-component"));
        Tools tools = tools(List.of(), ProcessOutcome.completed(0, "", ""));

        ToolOutcome outcome = tools.call("sheriff_test", Map.of("component", "empty-component", "profile", "JAVA"));
        String marker = "Recorded as task ";
        int start = outcome.text().indexOf(marker) + marker.length();
        String id = outcome.text().substring(start, start + 8);

        assertTrue(outcome.isError());
        assertTrue(outcome.text().startsWith("Nothing was analyzed"), outcome.text());
        assertTrue(outcome.text().contains(state.resolve("sheriff-mcp/tasks").resolve(id).toString()), outcome.text());
        assertTrue(tools.call("sheriff_task", Map.of("id", id)).text().contains("State: failed"));
    }

    @Test
    @DisplayName("a clean sheriff_test answers without waiting for the rule catalog, which only renders findings")
    void aCleanTestDoesNotReadTheCatalog() throws IOException {
        Path component = repository.resolve("clean-component");
        Files.createDirectories(component);
        Files.writeString(component.resolve("Clean.java"), "class Clean {}");
        McpConfig config = new McpConfig(Map.of("SHERIFF_REPO", repository.toString(), "XDG_STATE_HOME", state.toString()));
        SheriffRunner runner = new SheriffRunner(new QueuedProcessRunner(ProcessOutcome.completed(0, "", "")),
                config.image(), repository, Duration.ofSeconds(30));
        Tools tools = new Tools(config, runner, () -> {
            throw new AssertionError("the catalog was read for a run with nothing to render");
        });

        ToolOutcome outcome = tools.call("sheriff_test", Map.of("component", "clean-component", "profile", "JAVA"));

        assertFalse(outcome.isError(), outcome.text());
        assertTrue(outcome.text().contains("0 errors"), outcome.text());
    }

    @Test
    @DisplayName("while the image is being pulled, the tools say so instead of failing, and sheriff_task still answers")
    void whileTheImageIsMissingTheToolsSaySo() {
        ProcessRunner noDocker = (command, directory, environment, timeout) ->
                ProcessOutcome.completed(1, "", "Cannot connect to the Docker daemon");
        ImageProvisioning image = new ImageProvisioning(
                new SheriffImage(noDocker, "kaizten/sheriff:latest", name -> Optional.empty()), "check",
                new PrintStream(OutputStream.nullOutputStream()));
        image.now();
        McpConfig config = new McpConfig(Map.of("SHERIFF_REPO", repository.toString(), "XDG_STATE_HOME", state.toString()));
        SheriffRunner runner = new SheriffRunner(new QueuedProcessRunner(), config.image(), repository, Duration.ofSeconds(30));
        Tools tools = new Tools(config, runner, List::of, config::rulesCatalog, component -> VerificationResult.passed(""),
                image);

        ToolOutcome test = tools.call("sheriff_test", Map.of("component", "app"));

        assertTrue(test.isError());
        assertTrue(test.text().contains("could not be downloaded: Cannot connect to the Docker daemon"), test.text());
        assertFalse(tools.call("sheriff_task", Map.of()).isError());
    }

    @Test
    @DisplayName("that a newer image is published is said once, on the first answer, not on every one")
    void theOutdatedNoteIsGivenOnce() throws IOException {
        Path component = repository.resolve("clean-component");
        Files.createDirectories(component);
        Files.writeString(component.resolve("Clean.java"), "class Clean {}");
        ProcessRunner outdated = (command, directory, environment, timeout) ->
                ProcessOutcome.completed(0, "[\"kaizten/sheriff@sha256:old\"]", "");
        ImageProvisioning image = new ImageProvisioning(
                new SheriffImage(outdated, "kaizten/sheriff:latest", name -> Optional.of("sha256:new")), "check",
                new PrintStream(OutputStream.nullOutputStream()));
        image.now();
        McpConfig config = new McpConfig(Map.of("SHERIFF_REPO", repository.toString(), "XDG_STATE_HOME", state.toString()));
        SheriffRunner runner = new SheriffRunner(new QueuedProcessRunner(ProcessOutcome.completed(0, "", ""),
                ProcessOutcome.completed(0, "", "")), config.image(), repository, Duration.ofSeconds(30));
        Tools tools = new Tools(config, runner, List::of, config::rulesCatalog, component2 -> VerificationResult.passed(""),
                image);
        Map<String, Object> call = Map.of("component", "clean-component", "profile", "JAVA");

        String first = tools.call("sheriff_test", call).text();
        String second = tools.call("sheriff_test", call).text();

        assertTrue(first.contains("a newer kaizten/sheriff:latest has been published"), first);
        assertFalse(second.contains("has been published"), second);
    }

    @Test
    @DisplayName("a misconfigured server starts and says what to fix; what runs no Sheriff keeps working")
    void aMisconfiguredServerSaysWhatToFix() {
        McpConfig config = new McpConfig(Map.of("SHERIFF_REPO", repository.toString(),
                "XDG_STATE_HOME", state.toString(), "SHERIFF_TIMEOUT", "soon"));
        SheriffRunner runner = new SheriffRunner(new QueuedProcessRunner(), config.image(), repository, config.timeout());
        SheriffRule rule = new SheriffRule("RULE_A", "template", "", "java", "", "", List.of("JAVA"));
        Tools tools = new Tools(config, runner, () -> List.of(rule));

        ToolOutcome test = tools.call("sheriff_test", Map.of("component", "app"));

        assertTrue(test.isError());
        assertTrue(test.text().contains("misconfigured: SHERIFF_TIMEOUT"), test.text());
        assertFalse(tools.call("sheriff_task", Map.of()).isError());
        assertTrue(tools.call("sheriff_guidelines", Map.of("profile", "JAVA")).text().contains("RULE_A"));
    }

    @Test
    @DisplayName("while the image is pulled, sheriff_guidelines still answers from a catalog it can read now")
    void guidelinesAnswerWhileTheImageIsPulled() {
        ProcessRunner noDocker = (command, directory, environment, timeout) ->
                ProcessOutcome.completed(1, "", "Cannot connect to the Docker daemon");
        ImageProvisioning image = new ImageProvisioning(
                new SheriffImage(noDocker, "kaizten/sheriff:latest", name -> Optional.empty()), "check",
                new PrintStream(OutputStream.nullOutputStream()));
        image.now();
        McpConfig config = new McpConfig(Map.of("SHERIFF_REPO", repository.toString(), "XDG_STATE_HOME", state.toString()));
        SheriffRunner runner = new SheriffRunner(new QueuedProcessRunner(), config.image(), repository, Duration.ofSeconds(30));
        SheriffRule rule = new SheriffRule("RULE_A", "template", "", "java", "", "", List.of("JAVA"));
        Tools tools = new Tools(config, runner, () -> List.of(rule), config::rulesCatalog,
                component -> VerificationResult.passed(""), image);

        assertTrue(tools.call("sheriff_test", Map.of("component", "app")).isError());
        assertTrue(tools.call("sheriff_guidelines", Map.of("profile", "JAVA")).text().contains("RULE_A"));
    }

    @Test
    void anUnknownTaskIdIsAnError() {
        ToolOutcome outcome = tools(List.of()).call("sheriff_task", Map.of("id", "nope"));

        assertTrue(outcome.isError());
        assertTrue(outcome.text().contains("No task with id 'nope'"), outcome.text());
        assertFalse(outcome.text().contains(".."), outcome.text());
    }

    private static final class QueuedProcessRunner implements ProcessRunner {

        private final Deque<ProcessOutcome> outcomes;
        private final String tracked;

        QueuedProcessRunner(ProcessOutcome... outcomes) {
            this(null, outcomes);
        }

        QueuedProcessRunner(String tracked, ProcessOutcome... outcomes) {
            this.tracked = tracked;
            this.outcomes = new ArrayDeque<>(List.of(outcomes));
        }

        @Override
        public ProcessOutcome run(List<String> command, Path workingDirectory, Map<String, String> environment,
                Duration timeout) {
            ProcessOutcome outcome = outcomes.poll();
            int test = command.indexOf("test");
            int profile = command.indexOf("--test");
            int component = command.indexOf("--component");
            if (outcome != null && outcome.succeeded() && test >= 0 && profile >= 0 && component >= 0) {
                String output = outcome.standardOutput();
                String findings = output.indexOf('[') < 0 ? "[]" : output.substring(output.indexOf('['));
                try {
                    Files.writeString(workingDirectory.resolve("sheriff_errors.json"),
                            "{\"file:/data\":{\"" + command.get(component + 1) + "\":{\""
                                    + command.get(profile + 1) + "\":" + findings + "}}}");
                    if (tracked != null) {
                        Files.writeString(workingDirectory.resolve("sheriff_tracked_files.json"), tracked);
                    }
                } catch (IOException ignored) {
                    // Model Sheriff's report when the test mount can be written.
                }
            }
            return outcome;
        }
    }

    @Test
    @DisplayName("a repair lists whole files up to twenty errors, counts the rest, and the step is those files")
    void aRepairListsAFewFiles() {
        String error = "{\"file\":\"/data/app/%s.java\",\"description\":\"fault %d of %s\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"JavaLineCommentsChecker\",\"type\":\"ERROR\"}";
        List<String> errors = new ArrayList<>();
        for (int line = 0; line < 20; line++) {
            errors.add(String.format(error, "A", line, "A"));
        }
        errors.add(String.format(error, "B", 0, "B"));
        String found = "[" + String.join(",", errors) + "]";
        Tools tools = tools(List.of(), ProcessOutcome.completed(0, found, ""), ProcessOutcome.completed(1, "", ""),
                ProcessOutcome.completed(1, "", ""), ProcessOutcome.completed(0, found, ""),
                ProcessOutcome.completed(0, found, ""), ProcessOutcome.completed(1, "", ""),
                ProcessOutcome.completed(1, "", ""), ProcessOutcome.completed(0, found, ""));

        String first = tools.call("sheriff_fix", Map.of("component", "app")).text();
        String second = tools.call("sheriff_fix", Map.of("component", "app")).text();

        assertTrue(first.contains("fault 19 of A"), first);
        assertFalse(first.contains("fault 0 of B"), "the whole list in every answer was 8 to 11 KB a call: " + first);
        assertTrue(first.contains("Left in the other files, for the calls after this one (1): B.java 1"), first);
        assertFalse(first.contains("exactly the ones"), first);
        assertTrue(second.contains("exactly the ones the last answer of this tool listed"), second);
        assertFalse(second.contains("in that answer"), "sent back to an earlier answer, a model went digging: "
                + second);
        for (String answer : List.of(first, second)) {
            String step = answer.strip().lines().reduce((line, next) -> next).orElseThrow();
            assertTrue(step.startsWith("Next step: fix by hand every error listed above, all of them, in "), step);
            assertTrue(step.contains("A.java") && !step.contains("B.java"), step);
        }
    }

    @Test
    @DisplayName("files with few errors are listed together, so a file of one error is not a call of its own")
    void smallFilesShareAnAnswer() {
        String two = "[{\"file\":\"/data/app/A.java\",\"description\":\"first fault\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"JavaLineCommentsChecker\",\"type\":\"ERROR\"},"
                + "{\"file\":\"/data/app/B.java\",\"description\":\"second fault\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"JavaLineCommentsChecker\",\"type\":\"ERROR\"}]";
        Tools tools = tools(List.of(), ProcessOutcome.completed(0, two, ""), ProcessOutcome.completed(1, "", ""),
                ProcessOutcome.completed(1, "", ""), ProcessOutcome.completed(0, two, ""));

        String answer = tools.call("sheriff_fix", Map.of("component", "app")).text();

        assertTrue(answer.contains("first fault") && answer.contains("second fault"), answer);
        assertFalse(answer.contains("Left in the other files"), answer);
    }

    @Test
    @DisplayName("a path inside a component is not one: the answer names the component, as a step")
    void aPathInsideAComponentNamesTheComponent() {
        String text = tools(List.of()).call("sheriff_test",
                Map.of("component", "app/src/main/java/demo")).text();

        assertTrue(text.contains("'app/src/main/java/demo' is not a component"), text);
        assertTrue(text.contains("Next step: call this tool again with component 'app'."), text);
    }
}
