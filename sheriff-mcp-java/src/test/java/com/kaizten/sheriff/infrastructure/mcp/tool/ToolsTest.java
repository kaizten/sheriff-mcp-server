package com.kaizten.sheriff.infrastructure.mcp.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.enumerate.StopReason;
import com.kaizten.sheriff.domain.valueobject.RunSummary;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.domain.valueobject.SheriffRule;
import com.kaizten.sheriff.domain.valueobject.VerificationResult;
import com.kaizten.sheriff.infrastructure.coverage.ChangeCoverage;
import com.kaizten.sheriff.infrastructure.coverage.CoverageThresholds;
import com.kaizten.sheriff.infrastructure.docker.ImageProvisioning;
import com.kaizten.sheriff.infrastructure.docker.SheriffImage;
import com.kaizten.sheriff.infrastructure.mcp.McpConfig;
import com.kaizten.sheriff.infrastructure.mcp.ServerVersion;
import com.kaizten.sheriff.infrastructure.mcp.SheriffRunner;
import com.kaizten.sheriff.infrastructure.mcp.protocol.ToolOutcome;
import com.kaizten.sheriff.infrastructure.process.Platform;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import com.kaizten.sheriff.infrastructure.process.SystemProcessRunner;
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
    void listsTheSixToolsUnderTheDefaultPrefix() {
        List<Map<String, Object>> definitions = tools(List.of()).definitions();

        List<Object> names = definitions.stream().map(definition -> definition.get("name")).toList();
        assertEquals(List.of("sheriff_test", "sheriff_fix", "sheriff_guidelines", "sheriff_autofix", "sheriff_task",
                "sheriff_config"), names);
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
    @DisplayName("a profile the catalog does not record gets its language's rules, said to be that and not more")
    void guidelinesSaysWhenAProfileIsNotInTheCatalog() {
        SheriffRule base = new SheriffRule("RULE_A", "template", "solve it", "java", "", "", List.of("JAVA"));
        SheriffRule other = new SheriffRule("RULE_B", "template", "solve it", "java", "", "", List.of());
        Tools tools = tools(List.of(base, other));

        String known = tools.call("sheriff_guidelines", Map.of("profile", "JAVA")).text();
        String unknown = tools.call("sheriff_guidelines", Map.of("profile", "JAVA_HEXAGONAL_REST")).text();

        assertTrue(known.startsWith("The 1 rule(s) the 'JAVA' profile enforces"), known);
        assertTrue(unknown.startsWith("The catalog does not record which rules the 'JAVA,JAVA_HEXAGONAL_REST' "
                + "profile runs, so these are all 2 rule(s) of its language"), unknown);
        assertTrue(unknown.contains("RULE_A") && unknown.contains("RULE_B"), unknown);
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
    @DisplayName("tests that fail after a repair that changed no file, with none before it, are not blamed on it")
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

        assertTrue(text.contains("The project's own tests FAIL (cd 'app' && mvn -q test). This repair changed no "
                + "file, so it did not break them.\nexpected: <25.0> but was: <0.0>"), text);
        assertFalse(text.contains("FAIL after the repair"), text);
    }

    @Test
    @DisplayName("tests failing after a repair that changed nothing name the file an earlier repair changed")
    void failingTestsNameAnEarlierRepair() throws IOException {
        mavenComponent("app");
        String before = "[{\"file\":\"/data/app/pom.xml\",\"description\":\"outdated\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"JavaPomOutdatedDependency\",\"type\":\"ERROR\"}]";
        String old = "{\"/data/app/pom.xml\": {\"hash\": \"aaa\"}}";
        String raised = "{\"/data/app/pom.xml\": {\"hash\": \"bbb\"}}";
        McpConfig config = new McpConfig(Map.of("SHERIFF_REPO", repository.toString(), "XDG_STATE_HOME", state.toString()));
        SheriffRunner runner = new SheriffRunner(new QueuedProcessRunner(List.of(old, raised),
                ProcessOutcome.completed(0, before, ""), ProcessOutcome.completed(0, "", ""),
                ProcessOutcome.completed(0, "", ""), ProcessOutcome.completed(0, "[]", ""),
                ProcessOutcome.completed(0, "[]", "")), config.image(), repository, Duration.ofSeconds(30));
        Tools tools = new Tools(config, runner, List::of, config::rulesCatalog,
                component -> VerificationResult.failed("[ERROR] Failed to execute goal checkstyle: class file version 65.0"));

        String repair = tools.call("sheriff_fix", Map.of("component", "app")).text();
        String verified = tools.call("sheriff_fix", Map.of("component", "app", "verify", "true")).text();

        assertTrue(repair.contains("Files the fixers changed (1): pom.xml"), repair);
        assertTrue(verified.contains("This repair changed no file, but an earlier one changed app/pom.xml, which may be "
                + "what broke them: fix what broke, or undo that change; a repair of Sheriff's that is undone is not "
                + "made again."), verified);
        assertTrue(verified.contains("class file version 65.0"), verified);
        assertFalse(verified.contains("already failing"), verified);
    }

    @Test
    @DisplayName("a repair that was undone is not made again: its error is left for the end, then told to the user")
    void anUndoneRepairIsNotMadeAgain() throws IOException {
        mavenComponent("app");
        String outdated = "[{\"file\":\"/data/app/pom.xml\",\"description\":\"Maven dependency "
                + "'com.puppycrawl.tools:checkstyle' is outdated: 12.3.1 -> 14.3.0.\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"JavaPomOutdatedDependency\",\"type\":\"ERROR\"}]";
        McpConfig config = new McpConfig(Map.of("SHERIFF_REPO", repository.toString(), "XDG_STATE_HOME", state.toString()));
        SheriffRunner runner = new SheriffRunner(new QueuedProcessRunner(
                ProcessOutcome.completed(0, outdated, ""), ProcessOutcome.completed(0, "", ""),
                ProcessOutcome.completed(0, "", ""), ProcessOutcome.completed(0, "[]", ""),
                ProcessOutcome.completed(0, outdated, ""), ProcessOutcome.completed(0, outdated, "")),
                config.image(), repository, Duration.ofSeconds(30));
        Tools tools = new Tools(config, runner, List::of, config::rulesCatalog, component -> VerificationResult.passed("ok"));

        String repaired = tools.call("sheriff_fix", Map.of("component", "app")).text();
        String undone = tools.call("sheriff_fix", Map.of("component", "app")).text();
        String told = tools.call("sheriff_fix", Map.of("component", "app")).text();

        assertTrue(repaired.contains("1 error(s) before, 0 after (1 repaired)"), repaired);
        assertFalse(repaired.contains("undone"), repaired);
        assertTrue(undone.contains("1 error(s) before, 1 after (0 repaired)"),
                "one analysis and no fixer, or the runner would have run out: " + undone);
        assertTrue(undone.contains("Sheriff's fixers repaired 1 of these error(s) before, and the repair was undone "
                + "since, so no fixer makes it again and they are left for the end: app/pom.xml: Maven dependency "
                + "'com.puppycrawl.tools:checkstyle' is outdated: 12.3.1 -> 14.3.0. [JavaPomOutdatedDependency]"),
                undone);
        assertTrue(undone.contains("this answer is their last round"), undone);
        assertTrue(told.contains("Next step: none: the 1 error(s) above stayed through every repair"), told);
    }

    private static final String POM_OUTDATED = "{\"file\":\"/data/app/pom.xml\",\"description\":\"outdated:\\n"
            + "12.3.1 -> 14.3.0.\",\"howToSolve\":\"h\",\"referenceCode\":\"JavaPomOutdatedDependency\","
            + "\"type\":\"ERROR\"}";
    private static final String UNSORTED = "{\"file\":\"/data/app/A.java\",\"description\":\"unsorted\","
            + "\"howToSolve\":\"h\",\"referenceCode\":\"SortedImport\",\"type\":\"ERROR\"}";
    private static final String OLD_POM = "{\"/data/app/pom.xml\": {\"hash\": \"aaa\"}}";
    private static final String RAISED_POM = "{\"/data/app/pom.xml\": {\"hash\": \"bbb\"}}";

    private Tools fixTools(List<String> tracked, List<VerificationResult> results, ProcessOutcome... outcomes)
            throws IOException {
        mavenComponent("app");
        McpConfig config = new McpConfig(Map.of("SHERIFF_REPO", repository.toString(), "XDG_STATE_HOME", state.toString()));
        SheriffRunner runner = new SheriffRunner(new QueuedProcessRunner(tracked, outcomes), config.image(), repository,
                Duration.ofSeconds(30));
        Deque<VerificationResult> queue = new ArrayDeque<>(results);
        return new Tools(config, runner, List::of, config::rulesCatalog, component -> queue.poll());
    }

    private static ProcessOutcome found(String... errors) {
        return ProcessOutcome.completed(0, "[" + String.join(",", errors) + "]", "");
    }

    private static final ProcessOutcome FIXER_RAN = ProcessOutcome.completed(0, "", "");

    @Test
    @DisplayName("only the errors whose repair was undone are named as undone, each on one line")
    void onlyUndoneErrorsAreNamed() throws IOException {
        Tools tools = fixTools(List.of(), List.of(), found(POM_OUTDATED, UNSORTED), FIXER_RAN, FIXER_RAN,
                found(UNSORTED), FIXER_RAN, FIXER_RAN, found(UNSORTED), found(POM_OUTDATED, UNSORTED), FIXER_RAN,
                FIXER_RAN, found(POM_OUTDATED, UNSORTED));

        tools.call("sheriff_fix", Map.of("component", "app"));
        String undone = tools.call("sheriff_fix", Map.of("component", "app")).text();

        assertTrue(undone.contains("Sheriff's fixers repaired 1 of these error(s) before, and the repair was undone "
                + "since, so no fixer makes it again and they are left for the end: app/pom.xml: outdated: 12.3.1 -> "
                + "14.3.0. [JavaPomOutdatedDependency]\n"), undone);
    }

    @Test
    @DisplayName("the files a repair changed are named until the tests pass, through calls that changed nothing")
    void anEarlierRepairIsRememberedUntilTheTestsPass() throws IOException {
        VerificationResult failing = VerificationResult.failed("BUILD FAILURE");
        Tools tools = fixTools(List.of(OLD_POM, RAISED_POM), List.of(failing, failing,
                VerificationResult.passed("ok"), failing), found(POM_OUTDATED), FIXER_RAN, FIXER_RAN, found(),
                found(), found(), found(), found(), found());
        String earlier = "This repair changed no file, but an earlier one changed app/pom.xml";

        tools.call("sheriff_fix", Map.of("component", "app"));
        tools.call("sheriff_fix", Map.of("component", "app"));
        String first = tools.call("sheriff_fix", Map.of("component", "app", "verify", "true")).text();
        String second = tools.call("sheriff_fix", Map.of("component", "app", "verify", "true")).text();
        String passing = tools.call("sheriff_fix", Map.of("component", "app", "verify", "true")).text();
        String after = tools.call("sheriff_fix", Map.of("component", "app", "verify", "true")).text();

        assertTrue(first.contains(earlier), "a call that changed nothing keeps what the one before it changed: " + first);
        assertTrue(second.contains(earlier), "failing tests do not forget it: " + second);
        assertTrue(passing.contains("still pass"), passing);
        assertTrue(after.contains("This repair changed no file, so it did not break them."),
                "passing tests forget it: " + after);
    }

    @Test
    @DisplayName("tests failing after a repair that changed a file are put on that repair")
    void failingTestsAfterAChangeArePutOnIt() throws IOException {
        Tools tools = fixTools(List.of(OLD_POM, RAISED_POM), List.of(VerificationResult.failed("BUILD FAILURE")),
                found(POM_OUTDATED), FIXER_RAN, FIXER_RAN, found());

        String text = tools.call("sheriff_fix", Map.of("component", "app", "verify", "true")).text();

        assertTrue(text.contains("Files the fixers changed (1): pom.xml"), text);
        assertTrue(text.contains("The project's own tests FAIL after the repair (cd 'app' && mvn -q test)."), text);
        assertFalse(text.contains("changed no file"), text);
    }

    @Test
    @DisplayName("with no hashes to compare, failing tests are put on the repair, and a long output by its cause")
    void withNoHashesTheRepairIsReviewed() throws IOException {
        String output = "java.lang.IllegalStateException: the cause\n" + "MIDDLE\n".repeat(400) + "the end";
        Tools tools = fixTools(List.of(), List.of(VerificationResult.failed(output)), found());

        String text = tools.call("sheriff_fix", Map.of("component", "app", "verify", "true")).text();

        assertTrue(text.contains("The project's own tests FAIL after the repair (cd 'app' && mvn -q test). The repair is "
                + "left in place for you to review: fix what broke, or undo it; a repair of Sheriff's that is undone is "
                + "not made again.\njava.lang.IllegalStateException: the cause\n[...]\n"), text);
        assertTrue(text.contains("MIDDLE\nthe end"), text);
        assertTrue(text.length() < output.length(), "the output is cut");
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

        String absolute = Platform.slashes(repository.resolve("app").toAbsolutePath().normalize());
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
    @DisplayName("asked which version was installed, Codex searched the project: the handshake's version never reaches it")
    void sheriffTaskWithNoIdSaysWhichVersionIsRunning() {
        String listing = tools(List.of()).call("sheriff_task", Map.of()).text();

        assertTrue(listing.startsWith("sheriff-mcp " + ServerVersion.current() + ", running Sheriff's image "),
                listing);
        assertTrue(listing.contains("kaizten/sheriff"), listing);
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
    @DisplayName("what is wrong in the settings is said once a session, on the first answer that is not "
            + "sheriff_config's, before its step")
    void whatIsWrongInTheSettingsIsSaidOnce() throws IOException {
        Path config = state.resolve("config");
        Files.createDirectories(config.resolve("sheriff-mcp"));
        Files.writeString(config.resolve("sheriff-mcp/sheriff.properties"), "SHERIFF_PULL=sometimes\n");
        McpConfig settings = new McpConfig(Map.of("SHERIFF_REPO", repository.toString(),
                "XDG_STATE_HOME", state.toString(), "XDG_CONFIG_HOME", config.toString(),
                "APPDATA", config.toString()));
        SheriffRunner runner = new SheriffRunner(new QueuedProcessRunner(), settings.image(), repository,
                Duration.ofSeconds(30));
        Tools tools = new Tools(settings, runner, List::of);

        String listing = tools.call("sheriff_config", Map.of()).text();
        String first = tools.call("sheriff_task", Map.of()).text();
        String second = tools.call("sheriff_task", Map.of()).text();

        assertTrue(listing.contains("SHERIFF_PULL takes one of always, check, never, not 'sometimes'."), listing);
        assertFalse(listing.contains("Sheriff's settings have something wrong"), listing);
        assertTrue(first.contains("Sheriff's settings have something wrong"), first);
        assertTrue(first.contains("SHERIFF_PULL takes one of always, check, never, not 'sometimes'. It is ignored."),
                first);
        assertFalse(second.contains("Sheriff's settings have something wrong"), second);
        assertEquals("always", settings.pullPolicy(), "the value that is wrong is not used");
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
        private final Deque<String> trackedRecords;
        private String tracked;

        QueuedProcessRunner(ProcessOutcome... outcomes) {
            this((String) null, outcomes);
        }

        QueuedProcessRunner(String tracked, ProcessOutcome... outcomes) {
            this(tracked == null ? List.of() : List.of(tracked), outcomes);
        }

        /**
         * A runner whose analyses record these hashes in turn, the last one
         * from then on.
         */
        QueuedProcessRunner(List<String> trackedRecords, ProcessOutcome... outcomes) {
            this.trackedRecords = new ArrayDeque<>(trackedRecords);
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
                    tracked = trackedRecords.isEmpty() ? tracked : trackedRecords.poll();
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

    @Test
    @DisplayName("every tool lists its arguments in one order, the component first, whatever the JVM")
    void theArgumentsKeepTheirOrder() {
        Map<Object, Object> properties = new LinkedHashMap<>();
        for (Map<String, Object> definition : tools(List.of()).definitions()) {
            Map<?, ?> schema = (Map<?, ?>) definition.get("inputSchema");
            properties.put(definition.get("name"), List.copyOf(((Map<?, ?>) schema.get("properties")).keySet()));
        }

        assertEquals(List.of("component", "profile"), properties.get("sheriff_test"));
        assertEquals(List.of("component", "profile", "reference_code", "verify"), properties.get("sheriff_fix"));
        assertEquals(List.of("query", "reference_code", "profile"), properties.get("sheriff_guidelines"));
        assertEquals(List.of("component", "profile", "max_iterations"), properties.get("sheriff_autofix"));
        assertEquals(List.of("id"), properties.get("sheriff_task"));
    }

    @Test
    @DisplayName("verify is declared a flag and max_iterations a whole number of at least one")
    void theFlagAndTheCapAreDeclaredByType() {
        Map<Object, Map<?, ?>> properties = new LinkedHashMap<>();
        for (Map<String, Object> definition : tools(List.of()).definitions()) {
            Map<?, ?> schema = (Map<?, ?>) definition.get("inputSchema");
            properties.put(definition.get("name"), (Map<?, ?>) schema.get("properties"));
        }

        Map<?, ?> verify = (Map<?, ?>) properties.get("sheriff_fix").get("verify");
        Map<?, ?> cap = (Map<?, ?>) properties.get("sheriff_autofix").get("max_iterations");
        assertEquals("boolean", verify.get("type"));
        assertEquals("integer", cap.get("type"));
        assertEquals(1, cap.get("minimum"));
    }

    @Test
    @DisplayName("verify sent as a JSON flag runs the tests, as the string 'true' does")
    void verifyAsAFlagRunsTheTests() throws IOException {
        mavenComponent("app");
        String before = "[{\"file\":\"/data/app/A.java\",\"description\":\"d\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"R\",\"type\":\"ERROR\"}]";
        Tools tools = toolsWithTests(VerificationResult.failed("COMPILATION ERROR: cannot find symbol"),
                ProcessOutcome.completed(0, before, ""), ProcessOutcome.completed(1, "fixed", ""),
                ProcessOutcome.completed(1, "fixed", ""), ProcessOutcome.completed(0, "", ""));

        ToolOutcome outcome = tools.call("sheriff_fix", Map.of("component", "app", "verify", true));

        assertTrue(outcome.text().contains("FAIL after the repair"), outcome.text());
    }

    @Test
    @DisplayName("sheriff_fix with verify and passing tests still names a new method no test calls, rather than "
            + "saying the component is done")
    void passingTestsDoNotCoverAnUntestedMethod() throws IOException, InterruptedException {
        mavenComponent("app");
        Path app = repository.resolve("app");
        Path source = Files.createDirectories(app.resolve("src/main/java/demo")).resolve("Calc.java");
        Files.writeString(source, "package demo;\n\npublic class Calc {\n}\n");
        for (List<String> step : List.of(List.of("init", "-q"), List.of("add", "-A"), List.of("commit", "-q", "-m", "seed"))) {
            List<String> command = new ArrayList<>(List.of("git", "-c", "user.email=t@example.com", "-c", "user.name=t"));
            command.addAll(step);
            Process git = new ProcessBuilder(command).directory(app.toFile()).redirectErrorStream(true).start();
            git.getInputStream().readAllBytes();
            assertEquals(0, git.waitFor(), String.join(" ", command));
        }
        Files.writeString(source, "package demo;\n\npublic class Calc {\n\n    public int minus(int a, int b) {\n"
                + "        return a - b;\n    }\n}\n");
        String before = "[{\"file\":\"/data/app/A.java\",\"description\":\"d\",\"howToSolve\":\"h\","
                + "\"referenceCode\":\"R\",\"type\":\"ERROR\"}]";
        Tools tools = toolsWithTests(VerificationResult.passed("Tests run: 1, Failures: 0"),
                ProcessOutcome.completed(0, before, ""), ProcessOutcome.completed(1, "fixed", ""),
                ProcessOutcome.completed(1, "fixed", ""), ProcessOutcome.completed(0, "", ""));

        String text = tools.call("sheriff_fix", Map.of("component", "app", "verify", true)).text();

        assertTrue(text.contains("src/main/java/demo/Calc.java: minus"), text);
        assertFalse(text.contains("the component is done"), text);
    }

    /**
     * A Maven component with a repository, whose Calc gained a method since
     * its first commit, and a changed test that calls it.
     */
    private Path measurableChange() throws IOException, InterruptedException {
        mavenComponent("app");
        Path app = repository.resolve("app");
        Path source = Files.createDirectories(app.resolve("src/main/java/demo")).resolve("Calc.java");
        Files.writeString(source, "package demo;\n\npublic class Calc {\n}\n");
        for (List<String> step : List.of(List.of("init", "-q"), List.of("add", "-A"), List.of("commit", "-q", "-m", "seed"))) {
            List<String> command = new ArrayList<>(List.of("git", "-c", "user.email=t@example.com", "-c", "user.name=t"));
            command.addAll(step);
            Process git = new ProcessBuilder(command).directory(app.toFile()).redirectErrorStream(true).start();
            git.getInputStream().readAllBytes();
            assertEquals(0, git.waitFor(), String.join(" ", command));
        }
        Files.writeString(source, "package demo;\n\npublic class Calc {\n\n    public int abs(int a) {\n"
                + "        return a < 0 ? -a : a;\n    }\n}\n");
        Files.writeString(Files.createDirectories(app.resolve("src/test/java/demo")).resolve("CalcTest.java"),
                "class CalcTest {\n    void abs() {\n        new Calc().abs(1);\n    }\n}\n");
        return app;
    }

    /**
     * What measures the change: git as it is, and a Maven that writes the
     * reports given, none when null.
     */
    private ChangeCoverage coverageWriting(Path app, String jacoco, String pit) {
        ProcessRunner real = new SystemProcessRunner();
        ProcessRunner maven = (command, directory, environment, timeout) -> {
            if (command.get(0).equals("git")) {
                return real.run(command, directory, environment, timeout);
            }
            String line = command.get(command.size() - 1);
            try {
                if (line.contains("prepare-agent") && jacoco != null) {
                    Files.createDirectories(app.resolve("target/site/jacoco"));
                    Files.writeString(app.resolve("target/site/jacoco/jacoco.xml"), jacoco);
                }
                if (line.contains("mutationCoverage") && pit != null) {
                    Files.createDirectories(app.resolve("target/pit-reports"));
                    Files.writeString(app.resolve("target/pit-reports/mutations.xml"), pit);
                }
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            return ProcessOutcome.completed(1, "", "[ERROR] Could not resolve org.jacoco");
        };
        return new ChangeCoverage(maven, repository, (component, arguments) -> Optional.of("mvn " + String.join(" ", arguments)),
                component -> CoverageThresholds.defaults(), Duration.ofMinutes(1), Duration.ofMinutes(1));
    }

    private static final String COVERED = "<report><package name=\"demo\"><sourcefile name=\"Calc.java\">"
            + "<line nr=\"6\" mi=\"0\" ci=\"5\" mb=\"0\" cb=\"2\"/></sourcefile></package></report>";

    private static String mutants(String... statuses) {
        StringBuilder xml = new StringBuilder("<mutations>");
        for (String status : statuses) {
            xml.append("<mutation detected='").append(status.equals("KILLED")).append("' status='").append(status)
                    .append("'><sourceFile>Calc.java</sourceFile><mutatedClass>demo.Calc</mutatedClass><lineNumber>6"
                    + "</lineNumber><description>removed negation</description></mutation>");
        }
        return xml.append("</mutations>").toString();
    }

    private Tools toolsMeasuring(ChangeCoverage coverage, ProcessOutcome... outcomes) {
        McpConfig config = new McpConfig(Map.of("SHERIFF_REPO", repository.toString(), "XDG_STATE_HOME", state.toString()));
        SheriffRunner runner = new SheriffRunner(new QueuedProcessRunner(outcomes), config.image(), repository,
                Duration.ofSeconds(30));
        return new Tools(config, runner, List::of, config::rulesCatalog,
                component -> VerificationResult.passed("Tests run: 1, Failures: 0"), ImageProvisioning.alreadyReady(),
                ToolCall.throughMcp(), coverage);
    }

    private static final String ONE_ERROR = "[{\"file\":\"/data/app/A.java\",\"description\":\"d\","
            + "\"howToSolve\":\"h\",\"referenceCode\":\"R\",\"type\":\"ERROR\"}]";

    private ProcessOutcome[] repairedToClean() {
        return new ProcessOutcome[] {ProcessOutcome.completed(0, ONE_ERROR, ""), ProcessOutcome.completed(1, "fixed", ""),
            ProcessOutcome.completed(1, "fixed", ""), ProcessOutcome.completed(0, "", "")};
    }

    @Test
    @DisplayName("sheriff_config lists every setting with its source, changes one in the user's file, and refuses "
            + "a value the tools could not use")
    void sheriffConfigListsAndChangesSettings() throws IOException {
        mavenComponent("app");
        Map<String, String> environment = Map.of("SHERIFF_REPO", repository.toString(), "XDG_STATE_HOME",
                state.toString(), "XDG_CONFIG_HOME", state.toString(), "APPDATA", state.toString());
        Tools tools = new Tools(new McpConfig(environment), null, List::of);

        String listing = tools.call("sheriff_config", Map.of()).text();
        ToolOutcome changed = tools.call("sheriff_config", Map.of("setting", "SHERIFF_STOP_MAX_STALLS", "value", "0"));
        ToolOutcome refused = tools.call("sheriff_config", Map.of("setting", "SHERIFF_PULL", "value", "sometimes"));

        assertTrue(listing.contains("SHERIFF_STOP_MAX_STALLS = 1 (default)"), listing);
        assertTrue(listing.contains("an empty value puts the default back. To change them in a page in the browser, "
                + "call sheriff_config with page true, or run this in a terminal on this machine:"), listing);
        assertTrue(listing.endsWith(" --config-ui"), listing);
        assertFalse(changed.isError(), changed.text());
        assertTrue(Files.readString(state.resolve("sheriff-mcp/sheriff.properties"))
                .contains("\nSHERIFF_STOP_MAX_STALLS=0\n"));
        assertTrue(refused.isError());
        assertEquals("SHERIFF_PULL takes one of always, check, never, not 'sometimes'.", refused.text(),
                "a value refused is no check that could not run");
        assertTrue(tools.answersAtOnce("sheriff_config"), "it runs neither Sheriff nor the tests");
    }

    @Test
    @DisplayName("sheriff_config with page opens one settings page a session, answers with its address, and the "
            + "page closes with the tools")
    void sheriffConfigOpensThePage() throws Exception {
        mavenComponent("app");
        Map<String, String> environment = Map.of("SHERIFF_REPO", repository.toString(), "XDG_STATE_HOME",
                state.toString(), "XDG_CONFIG_HOME", state.toString(), "APPDATA", state.toString());
        Tools tools = new Tools(new McpConfig(environment), null, List::of);

        String first = tools.call("sheriff_config", Map.of("page", true)).text();
        String again = tools.call("sheriff_config", Map.of("page", "true")).text();
        java.util.regex.Matcher address = java.util.regex.Pattern.compile("http://127\\.0\\.0\\.1:\\d+/[0-9a-f]+/")
                .matcher(first);

        assertTrue(address.find(), first);
        assertTrue(again.contains(address.group()), "the same page, not a second one: " + again);
        assertTrue(first.contains("Give the user this address"), first);
        assertTrue(first.contains("To open it again from a terminal on this machine: "), first);
        assertTrue(first.endsWith(" --config-ui"), first);
        java.net.http.HttpClient http = java.net.http.HttpClient.newHttpClient();
        java.net.URI before = java.net.URI.create(address.group() + "settings");
        assertEquals(200, http.send(java.net.http.HttpRequest.newBuilder(before).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(200, http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(address.group() + "close"))
                .header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString("{}"))
                .build(), java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode());
        Thread.sleep(200);
        String reopened = tools.call("sheriff_config", Map.of("page", true)).text();
        assertFalse(reopened.contains(address.group()), "a page closed with its button is opened anew: " + reopened);
        java.util.regex.Matcher second = java.util.regex.Pattern.compile("http://127\\.0\\.0\\.1:\\d+/[0-9a-f]+/")
                .matcher(reopened);
        assertTrue(second.find());
        java.net.URI listing = java.net.URI.create(second.group() + "settings");
        tools.close();
        assertThrowsIo(() -> http.send(java.net.http.HttpRequest.newBuilder(listing).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString()));
        assertTrue(tools.call("sheriff_config", Map.of()).text().startsWith("Settings, each from"),
                "without page it lists them as before");
    }

    /**
     * Checks that a request fails, as one to a page that is closed does.
     *
     * @param request the request
     */
    private static void assertThrowsIo(org.junit.jupiter.api.function.Executable request) {
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class, request);
    }

    @Test
    @DisplayName("a settings page that cannot be opened is a failure of sheriff_config, saying why")
    void sheriffConfigWithoutAPort() {
        Map<String, String> environment = Map.of("SHERIFF_REPO", repository.toString(), "XDG_STATE_HOME",
                state.toString());
        ToolContext context = new ToolContext(new McpConfig(environment), null, null, null, null, null, null, null,
                null, null, null, null);
        SheriffConfigTool tool = new SheriffConfigTool(context, (given, project, idle) -> {
            throw new IOException("no port");
        });
        java.io.UncheckedIOException refused = org.junit.jupiter.api.Assertions.assertThrows(
                java.io.UncheckedIOException.class, () -> tool.definition().handler().handle(Map.of("page", true)));
        assertEquals("no port", refused.getCause().getMessage());
        tool.close();
    }

    @Test
    @DisplayName("closing the tools with no settings page open closes nothing else")
    void closingWithoutAPage() {
        Map<String, String> environment = Map.of("SHERIFF_REPO", repository.toString(), "XDG_STATE_HOME",
                state.toString());
        Tools tools = new Tools(new McpConfig(environment), null, List::of);
        tools.close();
        tools.close();
        assertTrue(tools.answersAtOnce("sheriff_config"));
    }

    /**
     * A component of Java with no build tool, in a repository, whose source
     * changed since its first commit.
     */
    private void changedComponentWithNoBuild() throws IOException, InterruptedException {
        Path lib = Files.createDirectories(repository.resolve("lib/src/main/java/demo"));
        Files.writeString(lib.resolve("A.java"), "package demo;\n\nclass A {\n}\n");
        commitAll(repository.resolve("lib"));
        Files.writeString(lib.resolve("A.java"), "package demo;\n\nclass A {\n    int a;\n}\n");
    }

    private static void commitAll(Path directory) throws IOException, InterruptedException {
        for (List<String> step : List.of(List.of("init", "-q"), List.of("add", "-A"), List.of("commit", "-q", "-m", "seed"))) {
            List<String> command = new ArrayList<>(List.of("git", "-c", "user.email=t@example.com", "-c", "user.name=t"));
            command.addAll(step);
            Process git = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
            git.getInputStream().readAllBytes();
            assertEquals(0, git.waitFor(), String.join(" ", command));
        }
    }

    @Test
    @DisplayName("a clean component with a new method no test calls is sent to write that test first")
    void aCleanComponentWithAnUntestedMethodNeedsItsTest() throws IOException, InterruptedException {
        Path app = measurableChange();
        Files.delete(app.resolve("src/test/java/demo/CalcTest.java"));

        String text = toolsMeasuring(coverageWriting(app, COVERED, mutants("KILLED")),
                ProcessOutcome.completed(0, "", "")).call("sheriff_test", Map.of("component", "app")).text();

        assertTrue(text.contains("src/main/java/demo/Calc.java: abs"), text);
        assertFalse(text.contains("verify true"), text);
        org.junit.jupiter.api.Assumptions.assumeFalse(Platform.windows(), "the command is cmd's there");
        assertTrue(text.endsWith("Then run cd '" + app.toAbsolutePath().normalize() + "' && mvn test, and call the "
                + "sheriff_test tool with component 'app' again."), text);
    }

    @Test
    @DisplayName("a clean component with a change and no build tool is done: there are no tests to measure")
    void aChangeWithNoBuildIsDone() throws IOException, InterruptedException {
        changedComponentWithNoBuild();

        String text = toolsMeasuring(coverageWriting(repository.resolve("lib"), COVERED, mutants("KILLED")),
                ProcessOutcome.completed(0, "", "")).call("sheriff_test", Map.of("component", "lib")).text();

        assertTrue(text.endsWith("Next step: none, the component is done: it meets the standards and has no tests to "
                + "run."), text);
    }

    @Test
    @DisplayName("a clean component with nothing to measure is sent to its tests, by the command and the next call")
    void aCleanComponentWithNothingToMeasureRunsItsTests() throws IOException {
        mavenComponent("app");
        String command = "cd '" + repository.resolve("app").toAbsolutePath().normalize() + "' && mvn test";

        String text = toolsMeasuring(coverageWriting(repository.resolve("app"), COVERED, mutants("KILLED")),
                ProcessOutcome.completed(0, "", "")).call("sheriff_test", Map.of("component", "app")).text();

        org.junit.jupiter.api.Assumptions.assumeFalse(Platform.windows(), "the command is cmd's there");
        assertTrue(text.endsWith("Next step: run the project's tests (" + command + "). If they pass, the component is "
                + "done. If you change any file to make them pass, call the sheriff_test tool with component 'app' "
                + "again."), text);
    }

    @Test
    @DisplayName("passing tests with nothing to measure are done, with no measure in the answer")
    void passingTestsWithNothingToMeasureAreDone() throws IOException {
        mavenComponent("app");

        String text = toolsMeasuring(coverageWriting(repository.resolve("app"), COVERED, mutants("KILLED")),
                repairedToClean()).call("sheriff_fix", Map.of("component", "app", "verify", true)).text();

        assertTrue(text.endsWith("Next step: none, the component is done: it meets the standards and its tests "
                + "pass."), text);
        assertFalse(text.contains("The tests run"), text);
    }

    @Test
    @DisplayName("sheriff_config asks for a value rather than putting the default back unasked, changes a project's "
            + "file when told, and lists how to change one")
    void sheriffConfigNeedsAValueAndTakesTheProject() throws IOException {
        Map<String, String> environment = Map.of("SHERIFF_REPO", repository.toString(), "XDG_STATE_HOME",
                state.toString(), "XDG_CONFIG_HOME", state.toString(), "APPDATA", state.toString());
        Tools tools = new Tools(new McpConfig(environment), null, List::of);

        ToolOutcome noValue = tools.call("sheriff_config", Map.of("setting", "SHERIFF_GATE_TTL"));
        String changed = tools.call("sheriff_config", Map.of("setting", "SHERIFF_GATE_TTL", "value", "60",
                "project", true)).text();

        assertEquals("Give the value to set SHERIFF_GATE_TTL to, or an empty one to put its default back.",
                noValue.text());
        assertTrue(changed.startsWith("SHERIFF_GATE_TTL is 60 in " + repository.resolve(".sheriff.properties")),
                changed);
        assertEquals("SHERIFF_GATE_TTL=60\n", Files.readString(repository.resolve(".sheriff.properties")));
        assertTrue(tools.call("sheriff_config", Map.of()).text().contains(String.format("%n%nTo change one, call "
                + "sheriff_config with setting and value, and project true for this project only")));
    }

    @Test
    @DisplayName("a server whose settings stop Sheriff still answers sheriff_config, where they are changed")
    void aMisconfiguredServerStillAnswersSheriffConfig() {
        McpConfig config = new McpConfig(Map.of("SHERIFF_REPO", repository.toString(), "XDG_STATE_HOME",
                state.toString(), "SHERIFF_TIMEOUT", "soon"));
        Tools tools = new Tools(config, null, List::of);

        assertTrue(tools.call("sheriff_test", Map.of("component", "app")).isError());
        ToolOutcome listing = tools.call("sheriff_config", Map.of());
        assertFalse(listing.isError(), listing.text());
        assertTrue(listing.text().contains("SHERIFF_TIMEOUT = soon (the environment)"), listing.text());
    }

    @Test
    @DisplayName("a tool other than sheriff_config that fails still ends with what to do next")
    void otherFailuresKeepTheirStep() throws IOException {
        mavenComponent("app");
        Tools tools = new Tools(new McpConfig(Map.of("SHERIFF_REPO", repository.toString(), "XDG_STATE_HOME",
                state.toString())), null, List::of);

        ToolOutcome failed = tools.call("sheriff_test", Map.of("component", "app"));

        assertTrue(failed.isError());
        assertTrue(failed.text().contains("Next step: tell the user the code standards check could not run"),
                failed.text());
    }

    @Test
    @DisplayName("a tool that throws outside a task still ends with what to do next")
    void aFailureOutsideATaskKeepsItsStep() {
        McpConfig config = new McpConfig(Map.of("SHERIFF_REPO", repository.toString(), "XDG_STATE_HOME",
                state.toString()));
        Tools tools = new Tools(config, null, () -> {
            throw new IllegalStateException("the catalog broke");
        });
        ToolOutcome failed = tools.call("sheriff_guidelines", Map.of("query", "braces"));
        assertTrue(failed.isError());
        assertTrue(failed.text().contains("Next step:"), failed.text());
    }

    @Test
    @DisplayName("the tools that run neither Sheriff nor the tests answer at once, and only they")
    void whichToolsAnswerAtOnce() {
        Tools tools = tools(List.of());
        assertEquals(List.of(true, true, true, false, false, false), List.of(tools.answersAtOnce("sheriff_task"),
                tools.answersAtOnce("sheriff_guidelines"), tools.answersAtOnce("sheriff_config"),
                tools.answersAtOnce("sheriff_test"), tools.answersAtOnce("sheriff_fix"),
                tools.answersAtOnce("sheriff_autofix")));
    }

    @Test
    @DisplayName("sheriff_config changes settings and nothing else: not read-only, the same call twice as once")
    void sheriffConfigIsAnnotated() {
        Map<?, ?> annotations = (Map<?, ?>) tools(List.of()).definitions().stream()
                .filter(definition -> "sheriff_config".equals(definition.get("name"))).findFirst().orElseThrow()
                .get("annotations");
        assertEquals(false, annotations.get("readOnlyHint"), annotations.toString());
        assertEquals(true, annotations.get("idempotentHint"), annotations.toString());
        assertEquals(false, annotations.get("destructiveHint"), annotations.toString());
    }

    @Test
    @DisplayName("the project's tests run by its build tool, and a component with none runs nothing")
    void theProjectsTestsNeedABuildTool() throws IOException {
        Files.createDirectories(repository.resolve("plain/src"));
        Files.writeString(repository.resolve("plain/src/A.java"), "class A {}");
        McpConfig config = new McpConfig(Map.of("SHERIFF_REPO", repository.toString()));
        assertFalse(Tools.projectTests(config).apply("plain").ran());
    }

    @Test
    @DisplayName("sheriff_autofix runs the loop with the user's own settings: the backend from the user's file")
    void autofixTakesTheUsersSettings() throws IOException, InterruptedException {
        Files.createDirectories(repository.resolve("app/src"));
        Files.writeString(repository.resolve("app/src/A.java"), "class A {}");
        Files.createDirectories(state.resolve("sheriff-mcp"));
        Files.writeString(state.resolve("sheriff-mcp/sheriff.properties"), "AI_BACKEND=local\n");
        Map<String, String> environment = new LinkedHashMap<>(Map.of("SHERIFF_REPO", repository.toString(),
                "XDG_STATE_HOME", state.toString(), "XDG_CONFIG_HOME", state.toString(), "APPDATA",
                state.toString(), "SHERIFF_MOCK", "1", "SHERIFF_AGENT_GIT_SAFETY", "0"));
        environment.put("VERIFICATION_TEST_CMD", "true");
        McpConfig config = new McpConfig(environment);
        Tools tools = new Tools(config, null, List::of, config::rulesCatalog, component -> VerificationResult.passed(""));

        String accepted = tools.call("sheriff_autofix", Map.of("component", "app")).text();
        String id = accepted.substring("Task ".length(), accepted.indexOf(' ', "Task ".length()));
        tools.close();

        String result = tools.call("sheriff_task", Map.of("id", id)).text();
        assertTrue(result.contains("via AI_BACKEND 'local'"), result);
    }

    private static String errors(String... descriptions) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < descriptions.length; index++) {
            String[] parts = descriptions[index].split(":", 2);
            json.append(index == 0 ? "" : ",").append("{\"file\":\"/data/app/").append(parts[0])
                    .append("\",\"description\":\"").append(parts[1])
                    .append("\",\"howToSolve\":\"h\",\"referenceCode\":\"R\",\"type\":\"ERROR\"}");
        }
        return json.append("]").toString();
    }

    private static ProcessOutcome[] repairLeaving(String errors) {
        return new ProcessOutcome[] {ProcessOutcome.completed(0, errors, ""), ProcessOutcome.completed(1, "fixed", ""),
            ProcessOutcome.completed(1, "fixed", ""), ProcessOutcome.completed(0, errors, "")};
    }

    @Test
    @DisplayName("a repair that took several rounds says how many; one that took one says nothing of rounds")
    void aRepairSaysHowManyRoundsItTook() throws IOException {
        mavenComponent("app");
        String two = errors("A.java:one", "A.java:two");
        String one = errors("A.java:two");
        Tools tools = toolsWithTests(VerificationResult.passed(""), ProcessOutcome.completed(0, two, ""),
                ProcessOutcome.completed(1, "fixed", ""), ProcessOutcome.completed(1, "fixed", ""),
                ProcessOutcome.completed(0, one, ""), ProcessOutcome.completed(1, "fixed", ""),
                ProcessOutcome.completed(1, "fixed", ""), ProcessOutcome.completed(0, one, ""));

        String text = tools.call("sheriff_fix", Map.of("component", "app")).text();

        assertTrue(text.contains("2 error(s) before, 1 after (1 repaired). The fixers ran 2 rounds, each measured by "
                + "a new analysis."), text);
        String single = tools(List.of(), repairLeaving(one)).call("sheriff_fix", Map.of("component", "app")).text();
        assertFalse(single.contains("rounds"), single);
    }

    @Test
    @DisplayName("the errors left for the end are counted per file, under the folder they share when they share one")
    void errorsLeftForTheEndAreCounted() {
        SheriffFinding a = new SheriffFinding("app/src/A.java", "d", "h", "R", "ERROR", Map.of());
        SheriffFinding b = new SheriffFinding("app/src/B.java", "d", "h", "R", "ERROR", Map.of());
        SheriffFinding pom = new SheriffFinding("pom.xml", "d", "h", "R", "ERROR", Map.of());
        assertEquals("", SheriffFixTool.leftForTheEnd(List.of()));
        assertEquals(String.format("%n%nLeft for the end, as they stayed through the edits made for them (3): A.java 2, "
                + "B.java 1, under app/src/"), SheriffFixTool.leftForTheEnd(List.of(a, a, b)));
        assertEquals(String.format("%n%nLeft for the end, as they stayed through the edits made for them (2): "
                + "app/src/A.java 1, pom.xml 1"), SheriffFixTool.leftForTheEnd(List.of(a, pom)));
    }

    @Test
    @DisplayName("an error that stays through the edits made for it is left for the end, gets a last round alone, and "
            + "is then told to the user")
    void aStuckErrorIsLeftForTheEnd() throws IOException {
        mavenComponent("app");
        List<ProcessOutcome> outcomes = new ArrayList<>();
        for (String round : List.of(errors("A.java:stays", "B.java:one"), errors("A.java:stays", "B.java:one"),
                errors("A.java:stays", "B.java:three"), errors("A.java:stays"), errors("A.java:stays"))) {
            outcomes.addAll(List.of(repairLeaving(round)));
        }
        Tools tools = toolsWithTests(VerificationResult.passed(""), outcomes.toArray(ProcessOutcome[]::new));
        Map<String, Object> call = Map.of("component", "app");

        String first = tools.call("sheriff_fix", call).text();
        String second = tools.call("sheriff_fix", call).text();
        String third = tools.call("sheriff_fix", call).text();
        String fourth = tools.call("sheriff_fix", call).text();
        String fifth = tools.call("sheriff_fix", call).text();

        assertTrue(first.contains("stays") && first.contains("one"), first);
        assertFalse(first.contains("last round"), first);
        assertFalse(second.contains("Left for the end"), second);
        assertTrue(second.contains("These 2 errors are exactly the ones the last answer of this tool listed"), second);
        assertFalse(third.contains("- stays"), "the stuck error is not listed among the work: " + third);
        assertTrue(third.contains("Left for the end, as they stayed through the edits made for them (1): A.java 1"),
                third);
        assertTrue(third.contains("Then call the sheriff_fix tool with component 'app' again."), third);
        assertTrue(fourth.contains(String.format("%nOnly errors that stayed through the edits made for them are left: "
                + "this answer is their last round.")), fourth);
        assertTrue(fourth.contains("Still outstanding: 1 error(s) in 1 file(s)."), fourth);
        assertFalse(fourth.contains("Left for the end"), fourth);
        assertTrue(fourth.contains("stays"), fourth);
        assertTrue(fifth.contains("What is left, 1 error(s), stayed through every repair and every edit made for "
                + "them:"), fifth);
        assertTrue(fifth.contains("  - app/A.java: stays  [R]"), fifth);
        assertTrue(fifth.endsWith("Next step: none: the 1 error(s) above stayed through every repair and every edit "
                + "made for them, so the work on them is over. Tell the user each one, in a line, and why it cannot be "
                + "fixed; the rest of the component is done."), fifth);
        assertFalse(fifth.contains(" or "), "an alternative is a choice a model makes differently: " + fifth);
    }

    @Test
    @DisplayName("the real measure goes by the project: a folder outside any repository has nothing to measure")
    void theRealMeasureNeedsAChange() throws IOException {
        mavenComponent("app");
        McpConfig config = new McpConfig(Map.of("SHERIFF_REPO", repository.toString()));
        assertFalse(Tools.changeCoverage(config).applies("app"));
    }

    @Test
    @DisplayName("a clean component with a Java change sends the model to sheriff_fix with verify, which measures "
            + "the tests, not to the tests alone")
    void aCleanChangeIsMeasuredThroughTheRepair() throws IOException, InterruptedException {
        Path app = measurableChange();

        String text = toolsMeasuring(coverageWriting(app, COVERED, mutants("KILLED")),
                ProcessOutcome.completed(0, "", "")).call("sheriff_test", Map.of("component", "app")).text();

        assertTrue(text.endsWith("Next step: call the sheriff_fix tool with component 'app' and verify true. It runs "
                + "the project's tests and then measures how well they test the lines this change wrote: the lines "
                + "they run and the branches of each condition they take (JaCoCo), and the changes to those lines "
                + "they notice (PIT's mutation testing)."), text);
    }

    @Test
    @DisplayName("while an idea is tried out, a clean component is done: no test asked for, run or measured, not "
            + "even for a new method no test calls")
    void aPrototypeIsDoneWhenClean() throws IOException, InterruptedException {
        Path app = measurableChange();
        Files.delete(app.resolve("src/test/java/demo/CalcTest.java"));
        Files.writeString(repository.resolve(".sheriff.properties"), "SHERIFF_PROTOTYPE=1\n");

        String text = toolsMeasuring(coverageWriting(app, COVERED, mutants("KILLED")),
                ProcessOutcome.completed(0, "", "")).call("sheriff_test", Map.of("component", "app")).text();

        assertTrue(text.endsWith("Next step: none, the component is done: it meets the standards. SHERIFF_PROTOTYPE "
                + "is 1, so nothing asks for its tests, runs them, measures them; tell the user so, and that setting it "
                + "back to 0 asks for them before the work is kept."), text);
        assertFalse(text.contains("Calc.java: abs"), text);
        assertFalse(text.contains("verify true"), text);
    }

    @Test
    @DisplayName("while an idea is tried out, tests a model ran anyway with verify are not measured")
    void aPrototypeIsNotMeasured() throws IOException, InterruptedException {
        Path app = measurableChange();
        Files.writeString(repository.resolve(".sheriff.properties"), "SHERIFF_PROTOTYPE=1\n");

        String text = toolsMeasuring(coverageWriting(app, COVERED, mutants("KILLED", "SURVIVED")), repairedToClean())
                .call("sheriff_fix", Map.of("component", "app", "verify", true)).text();

        assertTrue(text.endsWith("Next step: none, the component is done: it meets the standards and its tests "
                + "pass."), text);
        assertFalse(text.contains("mutants"), text);
    }

    @Test
    @DisplayName("tests that pass but let a mutant of the change survive are not done: the answer lists it and "
            + "sends the model back with verify")
    void weakTestsAreNotDone() throws IOException, InterruptedException {
        Path app = measurableChange();

        String text = toolsMeasuring(coverageWriting(app, COVERED, mutants("KILLED", "SURVIVED")), repairedToClean())
                .call("sheriff_fix", Map.of("component", "app", "verify", true)).text();

        assertTrue(text.contains("they do not test enough of what this change wrote"), text);
        assertTrue(text.contains("kill 1 of the 2 mutants PIT made of them (50%, 80% required)"), text);
        assertTrue(text.contains("src/main/java/demo/Calc.java:6 removed negation"), text);
        assertTrue(text.endsWith("Then call the sheriff_fix tool with component 'app' and verify true again."), text);
        assertFalse(text.contains("the component is done"), text);
        assertFalse(text.contains(" or "), "an alternative is a choice a model makes differently: " + text);
    }

    @Test
    @DisplayName("tests that run every changed line and kill enough mutants are done, and the answer says how well")
    void strongTestsAreDone() throws IOException, InterruptedException {
        Path app = measurableChange();

        String text = toolsMeasuring(coverageWriting(app, COVERED, mutants("KILLED", "KILLED")), repairedToClean())
                .call("sheriff_fix", Map.of("component", "app", "verify", true)).text();

        assertTrue(text.contains("The tests run 1 of the 1 lines of code this change wrote (100%, 100% required), "
                + "take 2 of the 2 branches of their conditions (100%, 100% required) and kill 2 of the 2 mutants"),
                text);
        assertTrue(text.endsWith("Next step: none, the component is done: it meets the standards and its tests "
                + "pass."), text);
    }

    @Test
    @DisplayName("a measure that cannot run does not hold the work: the component is done, and the user is told why")
    void anUnmeasuredChangeIsToldNotBlocked() throws IOException, InterruptedException {
        Path app = measurableChange();

        String text = toolsMeasuring(coverageWriting(app, null, null), repairedToClean())
                .call("sheriff_fix", Map.of("component", "app", "verify", true)).text();

        assertTrue(text.contains("could not be measured: JaCoCo wrote no report"), text);
        assertTrue(text.contains("Could not resolve org.jacoco"), text);
        assertTrue(text.contains("the component is done"), text);
    }

    @Test
    @DisplayName("a flag or a number is read as it is written, and anything else as nothing")
    void flagsAndNumbersAreReadAsWritten() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("flag", true);
        arguments.put("whole", 5);
        arguments.put("written", 5.0);
        arguments.put("fraction", 2.5);
        arguments.put("list", List.of("a"));

        assertEquals("true", ToolContext.textArgument(arguments, "flag"));
        assertEquals("5", ToolContext.textArgument(arguments, "whole"));
        assertEquals("5", ToolContext.textArgument(arguments, "written"));
        assertEquals("2.5", ToolContext.textArgument(arguments, "fraction"));
        assertEquals("", ToolContext.textArgument(arguments, "list"));
    }

    @Test
    @DisplayName("a cap that is not a whole number is refused before any task is accepted")
    void aCapThatIsNotAWholeNumberStartsNothing() {
        Tools tools = tools(List.of());

        for (Object cap : List.of("abc", 0, 2.5, "-3")) {
            ToolOutcome outcome = tools.call("sheriff_autofix", Map.of("component", "app", "max_iterations", cap));

            assertTrue(outcome.isError(), outcome.text());
            assertTrue(outcome.text().contains("max_iterations must be a whole number of at least 1"), outcome.text());
            assertTrue(outcome.text().strip().lines().reduce((line, next) -> next).orElseThrow()
                    .startsWith("Next step: call this tool again with max_iterations"), outcome.text());
        }
        assertTrue(tools.call("sheriff_task", Map.of()).text().strip().endsWith("No tasks yet in this session."));
    }

    @Test
    @DisplayName("the folder itself and the one above are not components, and Sheriff is not run for them")
    void theFolderItselfAndTheOneAboveAreNotComponents() {
        Tools tools = tools(List.of());

        for (String name : List.of(".", "..", "./app", "../app")) {
            ToolOutcome outcome = tools.call("sheriff_test", Map.of("component", name));

            assertTrue(outcome.isError(), name);
            assertTrue(outcome.text().contains("'" + name + "' is not a component"), outcome.text());
            assertFalse(outcome.text().contains("could not run"), outcome.text());
        }
    }
}
