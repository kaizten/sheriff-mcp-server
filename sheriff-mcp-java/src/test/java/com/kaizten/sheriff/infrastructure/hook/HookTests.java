package com.kaizten.sheriff.infrastructure.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.infrastructure.config.ChosenSettings;
import com.kaizten.sheriff.infrastructure.config.Composition;
import com.kaizten.sheriff.infrastructure.config.Configuration;
import com.kaizten.sheriff.infrastructure.coverage.CoverageThresholds;
import com.kaizten.sheriff.infrastructure.coverage.CoverageVerdict;
import com.kaizten.sheriff.infrastructure.coverage.Tally;
import com.kaizten.sheriff.infrastructure.git.GitVersionControl;
import com.kaizten.sheriff.infrastructure.process.FakeProcessRunner;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.SystemProcessRunner;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for both hooks.
 *
 * <p>What matters here is mostly when they must <em>not</em> block: a gate
 * that stops the fix loop's own edits, or that blocks every file in a
 * repository, or that fails closed when Docker is down, is worse than no gate
 * at all.
 */
class HookTests {

    private static final String SHERIFF_OUTPUT = """
            Execution results:
            \tErrors: 2
            [ {"file":"/data/app/A.java","description":"Method 'a' has no JavaDoc","howToSolve":"Add one.",
               "referenceCode":"","type":"ERROR"},
              {"file":"/data/app/B.java","description":"Hardcoded number","howToSolve":"Extract it.",
               "referenceCode":"","type":"ERROR"} ]
            """;

    private static final String ERRORS_IN_EDITED_FILE = """
            Execution results:
            \tErrors: 1
            [ {"file":"/data/app/src/A.java","description":"Method 'a' has no JavaDoc","howToSolve":"Add one.",
               "referenceCode":"","type":"ERROR"} ]
            """;

    @TempDir
    private Path repository;

    private Path agent;
    private String errors = "";

    @BeforeEach
    void layOutARepository() throws IOException {
        agent = repository.resolve("sheriff-mcp-java");
        Files.createDirectories(agent);
        Files.createDirectories(repository.resolve("app/src"));
        Files.writeString(repository.resolve("app/src/A.java"), "class A {}");
        Files.writeString(repository.resolve("README.md"), "not a source file");
    }

    private Composition composition(ProcessOutcome outcome) {
        Configuration configuration = new Configuration(Map.of("TARGET_REPO", repository.toString()), agent);
        return new Composition(configuration, FakeProcessRunner.always(outcome));
    }

    private SheriffStopHook stopHook(Map<String, String> environment, List<ProcessOutcome> outcomes) {
        Configuration configuration = new Configuration(environment, agent);
        return new Composition(configuration, new FakeProcessRunner(outcomes)).stopHook();
    }

    private ComponentGate gate(ProcessOutcome outcome) {
        return composition(outcome).componentGate();
    }

    private int capturingStandardError(java.util.function.Supplier<Integer> action) {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            return action.get();
        } finally {
            System.setErr(original);
            errors = captured.toString(StandardCharsets.UTF_8);
        }
    }

    private String sessionEditOf(String session, String relativePath) {
        return """
                {"session_id":"%s","tool_name":"Edit","tool_input":{"file_path":"%s"}}
                """.formatted(session, jsonPath(repository.resolve(relativePath)));
    }

    private String editOf(String relativePath) {
        return """
                {"tool_name":"Edit","tool_input":{"file_path":"%s"}}
                """.formatted(jsonPath(repository.resolve(relativePath)));
    }

    /**
     * A path as it appears inside a JSON string: on Windows its backslashes
     * are escaped, as Claude Code escapes them in the payload it sends.
     */
    private static String jsonPath(Path path) {
        return path.toString().replace("\\", "\\\\");
    }

    @Nested
    @DisplayName("which component a file belongs to")
    class Components {

        private ComponentGate gate() {
            return HookTests.this.gate(ProcessOutcome.completed(0, "", ""));
        }

        @Test
        void aSourceFileNamesItsOwnComponent() {
            assertEquals("app", gate().componentFor(repository.resolve("app/src/A.java").toString()));
        }

        @Test
        @DisplayName("every component is guarded, not just the configured one")
        void anotherComponentIsGuardedToo() {
            assertEquals("other", gate().componentFor(repository.resolve("other/src/B.java").toString()));
        }

        @Test
        @DisplayName("Python is a language Sheriff checks, so its sources are guarded too")
        void pythonSourcesAreGuarded() {
            assertEquals("tool", gate().componentFor(repository.resolve("tool/src/suma.py").toString()));
        }

        @Test
        void documentationIsNotGuarded() {
            assertEquals("", gate().componentFor(repository.resolve("README.md").toString()));
            assertEquals("", gate().componentFor(repository.resolve("app/notes.md").toString()));
        }

        @Test
        void filesOutsideTheRepositoryAreNotGuarded() {
            assertEquals("", gate().componentFor("/elsewhere/Thing.java"));
            assertEquals("", gate().componentFor(""));
        }

        @Test
        @DisplayName("the agent's own tree is the tool, not something Sheriff analyzes")
        void theAgentsOwnTreeIsNotAComponent() {
            assertEquals("", gate().componentFor(agent.resolve("src/Thing.java").toString()));
        }

        @Test
        void aLooseFileAtTheRootBelongsToNoComponent() {
            assertEquals("", gate().componentFor(repository.resolve("Thing.java").toString()));
        }

        @Test
        @DisplayName("a component given no profile, as one Sheriff was never applied to, is not guarded")
        void aComponentWithNoProfileIsNotGuarded() {
            Configuration configuration = new Configuration(Map.of("TARGET_REPO", repository.toString()), agent);
            Composition applied = new Composition(configuration,
                    FakeProcessRunner.always(ProcessOutcome.completed(0, "", "")),
                    component -> "app".equals(component) ? "JAVA" : "");
            ComponentGate gate = applied.componentGate();

            assertEquals("app", gate.componentFor(repository.resolve("app/src/A.java").toString()));
            assertEquals("", gate.componentFor(repository.resolve("other/src/B.java").toString()));
        }

        @Test
        @DisplayName("a file is named as Sheriff names it, relative to the mount with '/', to compare with its findings")
        void namesAFileTheWaySheriffDoes() {
            assertEquals("app/src/A.java", gate().sheriffName(repository.resolve("app/src/A.java").toString()));
            assertEquals("app/src/A.java",
                    gate().sheriffName(repository.resolve("app/src/../src/A.java").toString()));
            assertEquals("", gate().sheriffName("/elsewhere/Thing.java"));
        }

        @Test
        @DisplayName("a verdict knows which files have its errors, and one cached without them knows none")
        void aVerdictKnowsItsFiles() {
            GateVerdict verdict = new GateVerdict(2, "", Set.of("app/src/A.java"));
            assertTrue(verdict.hasErrorsIn("app/src/A.java"));
            assertFalse(verdict.hasErrorsIn("app/src/B.java"));
            assertFalse(verdict.hasErrorsIn(""));
            assertFalse(new GateVerdict(2, "").hasErrorsIn("app/src/A.java"));
            assertFalse(new GateVerdict(2, null, null).hasErrorsIn("app/src/A.java"));
        }
    }

    @Nested
    @DisplayName("the fingerprint that makes a verdict expire on content")
    class Fingerprints {

        @Test
        void changesWhenAFileChanges() throws IOException, InterruptedException {
            ComponentGate gate = gate(ProcessOutcome.completed(0, "", ""));
            String before = gate.fingerprintOf("app");
            Thread.sleep(10);
            Files.writeString(repository.resolve("app/src/A.java"), "class A { void a() {} }");
            assertFalse(before.equals(gate.fingerprintOf("app")), "an edit must change the fingerprint");
        }

        @Test
        @DisplayName("a verdict that stopped at the first error is not served for a full one, and a full one is")
        void aFailFastVerdictIsNeverServedForAFullOne(@TempDir Path cache) {
            Configuration failFast = new Configuration(
                    Map.of("TARGET_REPO", repository.toString(), "SHERIFF_FAIL_FAST", "1"), agent);
            VerdictCache verdicts = new VerdictCache(cache, Duration.ofMinutes(15));
            FakeProcessRunner quickRuns = FakeProcessRunner.always(ProcessOutcome.completed(0, SHERIFF_OUTPUT, ""));
            Composition quickComposition = new Composition(failFast, quickRuns);
            ComponentGate quick = new ComponentGate(failFast, quickComposition, verdicts);
            assertEquals(2, quick.verdictFor("app").errors());
            FakeProcessRunner fullRuns = FakeProcessRunner.always(ProcessOutcome.completed(0, "", ""));
            Configuration full = new Configuration(Map.of("TARGET_REPO", repository.toString()), agent);
            ComponentGate thorough = new ComponentGate(full, new Composition(full, fullRuns), verdicts);
            assertEquals(0, thorough.verdictFor("app").errors(), "the fail-fast verdict was served for a full one");
            assertEquals(1, fullRuns.commands().size());
            int quickBefore = quickRuns.commands().size();
            assertEquals(0, quick.verdictFor("app").errors(), "the full verdict says more, and serves");
            assertEquals(quickBefore, quickRuns.commands().size());
        }

        @Test
        @DisplayName("what a build writes beside its build file does not make a verdict look stale")
        void ignoresTheBuildOutput() throws IOException {
            Files.writeString(repository.resolve("app/pom.xml"), "<project/>");
            Files.createDirectories(repository.resolve("app/target/generated-sources"));
            ComponentGate gate = gate(ProcessOutcome.completed(0, "", ""));
            String before = gate.fingerprintOf("app");
            Files.writeString(repository.resolve("app/target/generated-sources/G.java"), "class G {}");
            assertEquals(before, gate.fingerprintOf("app"));
            Files.delete(repository.resolve("app/pom.xml"));
            assertFalse(before.equals(gate.fingerprintOf("app")), "without a pom.xml, target/ may be sources");
        }

        @Test
        @DisplayName("a count reached by stopping at the first error is said to be a floor")
        void countsAtLeastWhenFailingFast() {
            Configuration failFast = new Configuration(
                    Map.of("TARGET_REPO", repository.toString(), "SHERIFF_FAIL_FAST", "1"), agent);
            ComponentGate quick = new Composition(failFast,
                    FakeProcessRunner.always(ProcessOutcome.completed(0, "", ""))).componentGate();
            assertEquals("at least 1", quick.countOf(new GateVerdict(1, "")));
            assertEquals("1", gate(ProcessOutcome.completed(0, "", "")).countOf(new GateVerdict(1, "")));
            assertTrue(quick.floorNote().contains("sheriff_test lists every one"));
            assertEquals("", gate(ProcessOutcome.completed(0, "", "")).floorNote());
        }

        @Test
        void ignoresFilesSheriffDoesNotAnalyze() throws IOException {
            ComponentGate gate = gate(ProcessOutcome.completed(0, "", ""));
            String before = gate.fingerprintOf("app");
            Files.writeString(repository.resolve("app/notes.md"), "hello");
            assertEquals(before, gate.fingerprintOf("app"));
        }

        @Test
        @DisplayName("with the hooks on in every project, walking a front end's dependencies cost every edit")
        void doesNotLookInsideDependenciesOrGit() throws IOException {
            Path dependency = Files.createDirectories(repository.resolve("app/node_modules/lib")).resolve("x.ts");
            Path object = Files.createDirectories(repository.resolve("app/.git/hooks")).resolve("check.js");
            ComponentGate gate = gate(ProcessOutcome.completed(0, "", ""));
            String before = gate.fingerprintOf("app");
            Files.writeString(dependency, "export const x = 1;");
            Files.writeString(object, "exit(0);");
            assertEquals(before, gate.fingerprintOf("app"));
        }

        @Test
        @DisplayName("'cannot tell' must stay distinguishable from 'no files'")
        void aMissingComponentHasNoFingerprint() {
            assertEquals("", gate(ProcessOutcome.completed(0, "", "")).fingerprintOf("nope"));
        }
    }

    @Nested
    @DisplayName("the PreToolUse gate")
    class Gate {

        @Test
        void blocksAnEditWhenTheComponentHasErrors() {
            SheriffGateHook hook = new SheriffGateHook(gate(ProcessOutcome.completed(0, SHERIFF_OUTPUT, "")));
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> hook.decide(editOf("app/src/A.java"))));
            assertTrue(errors.contains("Blocked: app already has 2 Sheriff error"), errors);
            assertTrue(errors.contains("They are fixed first, in every file of the component, before the\nnew code"),
                    errors);
            assertTrue(errors.contains("Method 'a' has no JavaDoc"), "the sample shows what to fix");
        }

        @Test
        @DisplayName("errors left for the end hold no new code up: an edit goes through when they are all there is")
        void deferredErrorsDoNotBlock() {
            ComponentGate gate = gate(ProcessOutcome.completed(0, SHERIFF_OUTPUT, ""))
                    .withStuckErrors(new StuckErrors(repository.resolve("stuck")));
            gate.stalledOn("app", gate.verdictFor("app"));
            SheriffGateHook hook = new SheriffGateHook(gate);
            assertEquals(HookExit.ALLOW, capturingStandardError(() -> hook.decide(editOf("app/src/C.java"))));
            assertFalse(errors.contains("Blocked"), errors);
        }

        @Test
        @DisplayName("the hook already analyzed, so its step is the one an analysis gives: the repair, by name")
        void theGateEndsWithTheRepairStepAndACommandWithoutTheTools() {
            SheriffGateHook hook = new SheriffGateHook(gate(ProcessOutcome.completed(0, SHERIFF_OUTPUT, "")));
            capturingStandardError(() -> hook.decide(editOf("app/src/A.java")));
            assertTrue(errors.contains("Next step: call the sheriff_fix tool with component 'app'."), errors);
            assertTrue(errors.contains("\"Next step: none, the component is done\""), "what done looks like: " + errors);
            assertTrue(errors.contains("Without the sheriff tools, run `cd ") && errors.contains("--call sheriff_fix component="),
                    "a session without the MCP's tools had nothing it could do: " + errors);
        }

        @Test
        @DisplayName("a weaker model reads a long message as a list of ways out: it is a few lines and one instruction")
        void theMessageIsShort() {
            SheriffGateHook hook = new SheriffGateHook(gate(ProcessOutcome.completed(0, SHERIFF_OUTPUT, "")));
            capturingStandardError(() -> hook.decide(editOf("app/src/A.java")));
            assertTrue(errors.strip().lines().count() <= 10, errors);
            assertFalse(errors.contains("--agent") || errors.contains("sheriff_autofix"), errors);
        }

        @Test
        @DisplayName("blocking every edit left no way to clear the errors by hand, since a fix is an edit")
        void blocksOncePerSessionAndComponent() {
            SheriffGateHook hook = new SheriffGateHook(gate(ProcessOutcome.completed(0, SHERIFF_OUTPUT, "")),
                    new GateMemory(repository.resolve("memory")), new TurnStart(repository.resolve("turns")));
            String edit = sessionEditOf("one", "app/src/A.java");
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> hook.decide(edit)));
            assertTrue(errors.contains("Edits to the files that have them go through"), errors);
            assertEquals(HookExit.ALLOW, hook.decide(edit));
            assertEquals(HookExit.ALLOW, hook.decide(sessionEditOf("one", "app/src/B.java")));
        }

        @Test
        @DisplayName("an edit to a file with errors is the fix: a session following the repair steps was refused its first one")
        void letsAnEditToAFileWithErrorsThrough() {
            SheriffGateHook hook = new SheriffGateHook(gate(ProcessOutcome.completed(0, ERRORS_IN_EDITED_FILE, "")),
                    new GateMemory(repository.resolve("memory")), new TurnStart(repository.resolve("turns")));
            assertEquals(HookExit.ALLOW, hook.decide(sessionEditOf("one", "app/src/A.java")));
            assertEquals(HookExit.BLOCK,
                    capturingStandardError(() -> hook.decide(sessionEditOf("one", "app/src/New.java"))),
                    "letting a fix through must not use up the warning");
            assertTrue(errors.contains("Blocked: app already has 1 Sheriff error(s), from before this change, "
                    + "and app/src/New.java\nhas none of them."), errors);
        }

        @Test
        @DisplayName("the files with errors are cached with the count, so a cached verdict lets the fix through too")
        void aCachedVerdictKnowsTheFiles() {
            new SheriffGateHook(gate(ProcessOutcome.completed(0, ERRORS_IN_EDITED_FILE, ""))).decide(editOf("app/src/A.java"));
            SheriffGateHook cached = new SheriffGateHook(gate(ProcessOutcome.completed(0, SHERIFF_OUTPUT, "")));
            assertEquals(HookExit.ALLOW, cached.decide(editOf("app/src/A.java")));
        }

        @Test
        @DisplayName("clearing the errors is part of the request, never a question for the user")
        void clearsTheErrorsWithoutAsking() {
            SheriffGateHook hook = new SheriffGateHook(gate(ProcessOutcome.completed(0, SHERIFF_OUTPUT, "")));
            capturingStandardError(() -> hook.decide(editOf("app/src/A.java")));
            assertTrue(errors.contains("Do not ask the"), errors);
        }

        @Test
        void warnsANewSessionAgain() {
            SheriffGateHook hook = new SheriffGateHook(gate(ProcessOutcome.completed(0, SHERIFF_OUTPUT, "")),
                    new GateMemory(repository.resolve("memory")), new TurnStart(repository.resolve("turns")));
            capturingStandardError(() -> hook.decide(sessionEditOf("one", "app/src/A.java")));
            assertEquals(HookExit.BLOCK,
                    capturingStandardError(() -> hook.decide(sessionEditOf("two", "app/src/A.java"))));
        }

        @Test
        void keepsBlockingWhenTheSessionIsUnknown() {
            SheriffGateHook hook = new SheriffGateHook(gate(ProcessOutcome.completed(0, SHERIFF_OUTPUT, "")),
                    new GateMemory(repository.resolve("memory")), new TurnStart(repository.resolve("turns")));
            capturingStandardError(() -> hook.decide(editOf("app/src/A.java")));
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> hook.decide(editOf("app/src/A.java"))));
        }

        @Test
        void allowsAnEditWhenTheComponentIsClean() {
            SheriffGateHook hook = new SheriffGateHook(gate(ProcessOutcome.completed(0, "", "")));
            assertEquals(HookExit.ALLOW, hook.decide(editOf("app/src/A.java")));
        }

        @Test
        void ignoresToolsThatDoNotEdit() {
            SheriffGateHook hook = new SheriffGateHook(gate(ProcessOutcome.completed(0, SHERIFF_OUTPUT, "")));
            assertEquals(HookExit.ALLOW, hook.decide("""
                    {"tool_name":"Read","tool_input":{"file_path":"%s"}}
                    """.formatted(jsonPath(repository.resolve("app/src/A.java")))));
        }

        @Test
        void ignoresFilesOutsideAnyComponent() {
            SheriffGateHook hook = new SheriffGateHook(gate(ProcessOutcome.completed(0, SHERIFF_OUTPUT, "")));
            assertEquals(HookExit.ALLOW, hook.decide(editOf("README.md")));
        }

        @Test
        void ignoresInputItCannotParse() {
            SheriffGateHook hook = new SheriffGateHook(gate(ProcessOutcome.completed(0, SHERIFF_OUTPUT, "")));
            assertEquals(HookExit.ALLOW, hook.decide("not json at all"));
            assertEquals(HookExit.ALLOW, hook.decide(""));
        }

        @Test
        @DisplayName("Docker being down must not mean nobody can edit anything")
        void failsOpenWhenSheriffCannotRun() {
            SheriffGateHook hook = new SheriffGateHook(gate(ProcessOutcome.unavailable("no docker")));
            assertEquals(HookExit.ALLOW, capturingStandardError(() -> hook.decide(editOf("app/src/A.java"))));
            assertTrue(errors.contains("could not run Sheriff"));
        }

        @Test
        @DisplayName("NotebookEdit names its argument differently, and used to slip through unchecked")
        void readsTheNotebookArgumentToo() throws IOException {
            com.fasterxml.jackson.databind.JsonNode call = new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree("{\"tool_input\":{\"notebook_path\":\"/a.ipynb\"}}");
            assertEquals("/a.ipynb", SheriffGateHook.editedPath(call));
        }
    }

    @Nested
    @DisplayName("the end-of-turn hook")
    class Stop {

        private SheriffStopHook hook(ProcessOutcome outcome) {
            return composition(outcome).stopHook();
        }

        @Test
        @DisplayName("without a session to count blocks by, it still blocks only once")
        void neverBlocksTwiceWithoutASession() {
            assertEquals(HookExit.ALLOW, hook(ProcessOutcome.completed(0, SHERIFF_OUTPUT, ""))
                    .decide("{\"stop_hook_active\":true}"));
        }

        private SheriffStopHook hookSeeing(String status) throws IOException {
            return stopHook(Map.of("TARGET_REPO", repository.toString()), List.of(
                    ProcessOutcome.completed(0, repository.toRealPath() + "\n", ""),
                    ProcessOutcome.completed(0, status, "")));
        }

        @Test
        void nothingToCheckWhenNoSourceFileWasTouched() throws IOException {
            SheriffStopHook stop = hookSeeing(" M README.md\0");
            assertEquals(HookExit.ALLOW, stop.decide("{\"stop_hook_active\":false}"));
        }

        @Test
        @DisplayName("a rename reports the name that exists now")
        void readsWhatGitSaysChanged() throws IOException {
            SheriffStopHook stop = hookSeeing(" M app/src/A.java\0R  app/src/N.java\0old/N.java\0?? other/B.java\0");
            assertEquals(Set.of("app", "other"), stop.touchedComponents());
        }

        @Test
        @DisplayName("a repository that is one module: git is asked from the project, not from the mount")
        void aSingleModuleRepositoryIsCheckedToo() throws IOException {
            Path app = repository.resolve("app");
            SheriffStopHook stop = stopHook(
                    Map.of("TARGET_REPO", repository.toString(), "CLAUDE_PROJECT_DIR", app.toString()),
                    List.of(ProcessOutcome.completed(0, app.toRealPath() + "\n", ""),
                            ProcessOutcome.completed(0, " M src/A.java\0", "")));
            assertEquals(Set.of("app"), stop.touchedComponents());
        }

        private com.kaizten.sheriff.infrastructure.git.GitVersionControl gitSeeingAppTouched() throws IOException {
            FakeProcessRunner gitRunner = new FakeProcessRunner(List.of(
                    ProcessOutcome.completed(0, repository.toRealPath() + "\n", ""),
                    ProcessOutcome.completed(0, " M app/src/A.java\0", "")));
            return new com.kaizten.sheriff.infrastructure.git.GitVersionControl(
                    gitRunner, repository, repository, "");
        }

        private static final String FRESH_STOP = "{\"session_id\":\"s\",\"stop_hook_active\":false}";
        private static final String STOP_AGAIN = "{\"session_id\":\"s\",\"stop_hook_active\":true}";

        private SheriffStopHook capped(StopBlocks blocks) throws IOException {
            return new SheriffStopHook(gate(ProcessOutcome.completed(0, SHERIFF_OUTPUT, "")), gitSeeingAppTouched(),
                    null, blocks, new TurnStart(repository.resolve("turns")), 2);
        }

        private void touch(String content) throws IOException, InterruptedException {
            Thread.sleep(10);
            Files.writeString(repository.resolve("app/src/A.java"), content);
        }

        @Test
        @DisplayName("a demo ended with 183 errors and a question, because a second stop always went through")
        void keepsBlockingWhileTheModelKeepsWorkingUpToTheCap() throws IOException, InterruptedException {
            StopBlocks blocks = new StopBlocks(repository.resolve("stop-blocks"));
            SheriffStopHook first = capped(blocks);
            SheriffStopHook second = capped(blocks);
            SheriffStopHook third = capped(blocks);
            SheriffStopHook nextTurn = capped(blocks);
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> first.decide(FRESH_STOP)));
            touch("class A { int b; }");
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> second.decide(STOP_AGAIN)));
            touch("class A { int c; }");
            assertEquals(HookExit.ALLOW, capturingStandardError(() -> third.decide(STOP_AGAIN)));
            assertTrue(errors.contains("2 times in a row"), errors);
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> nextTurn.decide(FRESH_STOP)));
        }

        private SheriffStopHook patient(StopBlocks blocks) throws IOException {
            return new SheriffStopHook(gate(ProcessOutcome.completed(0, SHERIFF_OUTPUT, "")), gitSeeingAppTouched(),
                    null, blocks, new TurnStart(repository.resolve("turns")), 5);
        }

        @Test
        @DisplayName("Sonnet ended a turn by stopping once with nothing changed: that is sent back once more, the second goes")
        void letsTheTurnEndOnlyWhenNothingChangedTwiceInARow() throws IOException {
            StopBlocks blocks = new StopBlocks(repository.resolve("stop-blocks"));
            SheriffStopHook first = patient(blocks);
            SheriffStopHook second = patient(blocks);
            SheriffStopHook third = patient(blocks);
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> first.decide(FRESH_STOP)));
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> second.decide(STOP_AGAIN)));
            assertTrue(errors.contains("Nothing changed since the last time") && errors.contains("Fix them now"), errors);
            assertEquals(HookExit.ALLOW, capturingStandardError(() -> third.decide(STOP_AGAIN)));
            assertTrue(errors.contains("Nothing changed in 2 stop(s) in a row"), errors);
            assertFalse(errors.contains("Not done:"), errors);
        }

        @Test
        @DisplayName("how many stops with nothing changed are sent back is a setting: 0 lets the first through, as a "
                + "false positive of the checker needs, and more hold the model longer")
        void theStallsSentBackAreASetting() throws IOException {
            StopBlocks blocks = new StopBlocks(repository.resolve("stop-blocks"));
            SheriffStopHook impatientFirst = patient(blocks).withMaxStalls(0);
            SheriffStopHook impatientSecond = patient(blocks).withMaxStalls(0);
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> impatientFirst.decide(FRESH_STOP)));
            assertEquals(HookExit.ALLOW, capturingStandardError(() -> impatientSecond.decide(STOP_AGAIN)));
            assertTrue(errors.contains("Nothing changed in 1 stop(s) in a row"), errors);
        }

        @Test
        @DisplayName("with more stops with nothing changed allowed, the model is held that much longer")
        void moreStallsHoldTheModelLonger() throws IOException {
            StopBlocks patientBlocks = new StopBlocks(repository.resolve("patient-blocks"));
            List<SheriffStopHook> stops = new ArrayList<>();
            for (int stop = 0; stop < 4; stop++) {
                stops.add(patient(patientBlocks).withMaxStalls(2));
            }
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> stops.get(0).decide(FRESH_STOP)));
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> stops.get(1).decide(STOP_AGAIN)));
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> stops.get(2).decide(STOP_AGAIN)));
            assertEquals(2, patientBlocks.stalls("s"));
            assertEquals(HookExit.ALLOW, capturingStandardError(() -> stops.get(3).decide(STOP_AGAIN)));
        }

        @Test
        void blocksUpToFiveTimesInARowUnlessConfigured() {
            assertEquals(5, new Configuration(Map.of(), agent).stopMaxBlocks());
            assertEquals(1, new Configuration(Map.of("SHERIFF_STOP_MAX_BLOCKS", "1"), agent).stopMaxBlocks());
        }

        private SheriffStopHook withTests(com.kaizten.sheriff.domain.valueobject.VerificationResult result)
                throws IOException {
            return new SheriffStopHook(gate(ProcessOutcome.completed(0, "", "")), gitSeeingAppTouched(),
                    component -> result);
        }

        @Test
        @DisplayName("what the turn changed is cleared whole, errors already there included, without asking")
        void errorsAlreadyThereAreClearedToo() throws IOException {
            SheriffStopHook stop = new SheriffStopHook(gate(ProcessOutcome.completed(0, SHERIFF_OUTPUT, "")),
                    gitSeeingAppTouched());
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> stop.decide("{\"stop_hook_active\":false}")));
            assertTrue(errors.contains("app: 2 error(s)"), errors);
            assertTrue(errors.contains("those that were already there\nincluded"), errors);
            assertTrue(errors.contains("Do not ask the user whether to"), errors);
        }

        @Test
        @DisplayName("whose turn it was is decided here, not left to the model: the message has no way out to take")
        void theMessageIsShortAndHasNoWayOut() throws IOException {
            SheriffStopHook stop = new SheriffStopHook(gate(ProcessOutcome.completed(0, SHERIFF_OUTPUT, "")),
                    gitSeeingAppTouched());
            capturingStandardError(() -> stop.decide("{\"stop_hook_active\":false}"));
            assertFalse(errors.contains("wrote no code"), errors);
            assertFalse(errors.contains("--agent") || errors.contains("sheriff_autofix"), errors);
            assertTrue(errors.strip().lines().count() <= 12, errors);
        }

        private SheriffStopHook recording(TurnStart turns) throws IOException {
            return new SheriffStopHook(gate(ProcessOutcome.completed(0, SHERIFF_OUTPUT, "")), gitSeeingAppTouched(),
                    null, new StopBlocks(repository.resolve("stop-blocks")), turns, 5);
        }

        @Test
        @DisplayName("a turn that did not change a component git reports leaves it alone: a question is not a fix")
        void aComponentTheTurnDidNotChangeIsNotChecked() throws IOException {
            TurnStart turns = new TurnStart(repository.resolve("turns"));
            assertEquals(HookExit.ALLOW, recording(turns).startTurn("{\"session_id\":\"s\"}"));
            assertEquals(HookExit.ALLOW, capturingStandardError(() -> {
                try {
                    return recording(turns).decide(FRESH_STOP);
                } catch (IOException exception) {
                    throw new IllegalStateException(exception);
                }
            }));
            assertFalse(errors.contains("Not done:"), errors);
        }

        @Test
        @DisplayName("a component the turn changed is checked whole, though it was already changed before")
        void aComponentTheTurnChangedIsChecked() throws IOException, InterruptedException {
            TurnStart turns = new TurnStart(repository.resolve("turns"));
            recording(turns).startTurn("{\"session_id\":\"s\"}");
            touch("class A { int b; }");
            SheriffStopHook stop = recording(turns);
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> stop.decide(FRESH_STOP)));
            assertTrue(errors.contains("app: 2 error(s)"), errors);
        }

        @Test
        @DisplayName("a model whose only edit the gate refused asked the user and ended the turn, as it had changed nothing")
        void aComponentTheGateRefusedInTheTurnIsChecked() throws IOException {
            TurnStart turns = new TurnStart(repository.resolve("turns"));
            recording(turns).startTurn("{\"session_id\":\"s\"}");
            SheriffGateHook gateHook = new SheriffGateHook(gate(ProcessOutcome.completed(0, SHERIFF_OUTPUT, "")),
                    new GateMemory(repository.resolve("memory")), turns);
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> gateHook.decide(sessionEditOf("s", "app/src/New.java"))));
            SheriffStopHook stop = recording(turns);
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> stop.decide(FRESH_STOP)));
            assertTrue(errors.contains("Not done: an edit this turn tried was stopped"), errors);
            assertTrue(errors.contains("app: 2 error(s)"), errors);
            assertTrue(errors.contains("Next step: call the sheriff_fix tool with component 'app'."), errors);
        }

        @Test
        @DisplayName("what the gate refused in one turn is not the next turn's: a question after it is not a fix")
        void aRefusalIsForgottenWhenTheNextTurnStarts() throws IOException {
            TurnStart turns = new TurnStart(repository.resolve("turns"));
            recording(turns).startTurn("{\"session_id\":\"s\"}");
            turns.refused("s", "app");
            recording(turns).startTurn("{\"session_id\":\"s\"}");
            SheriffStopHook stop = recording(turns);
            assertEquals(HookExit.ALLOW, capturingStandardError(() -> stop.decide(FRESH_STOP)));
            assertFalse(errors.contains("Not done:"), errors);
        }

        @Test
        @DisplayName("Codex keeps MCP tools behind a search: told only 'the standards tools', it never found them")
        void theMessageNamesTheToolsToUse() throws IOException {
            SheriffStopHook stop = new SheriffStopHook(gate(ProcessOutcome.completed(0, SHERIFF_OUTPUT, "")),
                    gitSeeingAppTouched());
            capturingStandardError(() -> stop.decide("{\"stop_hook_active\":false}"));
            assertTrue(errors.contains("Next step: call the sheriff_fix tool with component 'app'."), errors);
            assertTrue(errors.contains("--call sheriff_fix component="), errors);
        }

        @Test
        @DisplayName("with tests on, a turn that leaves them failing cannot end")
        void aTurnThatBreaksTheTestsCannotEnd() throws IOException {
            SheriffStopHook stop = withTests(
                    com.kaizten.sheriff.domain.valueobject.VerificationResult.failed("BUILD FAILURE"));
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> stop.decide("{\"stop_hook_active\":false}")));
            assertTrue(errors.contains("tests fail"));
            assertTrue(errors.contains("BUILD FAILURE"));
        }

        @Test
        @DisplayName("long failing output is shown by the line that says why, not only by its end")
        void longFailingOutputShowsItsCause() throws IOException {
            String output = "java.lang.UnsupportedClassVersionError: class file version 65.0\n"
                    + "    at java.lang.ClassLoader.defineClass (ClassLoader.java:1017)\n".repeat(60)
                    + "[ERROR] urls[0] = file:/home/ana/.m2/plexus-utils-4.0.1.jar\n".repeat(60) + "[ERROR] [Help 1]";
            SheriffStopHook stop = withTests(
                    com.kaizten.sheriff.domain.valueobject.VerificationResult.failed(output));
            capturingStandardError(() -> stop.decide("{\"stop_hook_active\":false}"));
            assertTrue(errors.contains("java.lang.UnsupportedClassVersionError: class file version 65.0\n[...]"), errors);
            assertFalse(errors.contains("ClassLoader.defineClass"), errors);
        }

        @Test
        @DisplayName("failing tests come with the command that runs them, and the check to make after")
        void failingTestsNameTheirCommand() throws IOException {
            SheriffStopHook stop = withTests(
                    com.kaizten.sheriff.domain.valueobject.VerificationResult.failed("BUILD FAILURE"))
                    .withTestCommands(component -> "cd '/work/" + component + "' && mvn test");
            capturingStandardError(() -> stop.decide("{\"stop_hook_active\":false}"));
            assertTrue(errors.contains("Next step: run cd '/work/app' && mvn test and fix what fails"), errors);
            assertTrue(errors.contains("call the sheriff_test tool with component 'app' once more"), errors);
        }

        @Test
        void withTestsOnAGreenTurnEnds() throws IOException {
            SheriffStopHook stop = withTests(com.kaizten.sheriff.domain.valueobject.VerificationResult.passed("ok"));
            assertEquals(HookExit.ALLOW, stop.decide("{\"stop_hook_active\":false}"));
        }

        @Test
        @DisplayName("with tests on and green, tests that do not test what the turn wrote enough cannot end it")
        void weakTestsCannotEndATurn() throws IOException {
            CoverageVerdict weak = new CoverageVerdict(true, "", new Tally(2, 1), new Tally(4, 3), new Tally(2, 1),
                    Map.of("src/main/java/demo/Calc.java", List.of(10)),
                    List.of("src/main/java/demo/Calc.java:13 takes 3 of its 4 branches"),
                    List.of("src/main/java/demo/Calc.java:13 changed conditional boundary"),
                    CoverageThresholds.defaults());
            SheriffStopHook stop = withTests(com.kaizten.sheriff.domain.valueobject.VerificationResult.passed("ok"))
                    .withChangeCoverage(component -> weak);
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> stop.decide("{\"stop_hook_active\":false}")));
            assertTrue(errors.contains("they do not test enough of what this turn wrote"), errors);
            assertTrue(errors.contains("src/main/java/demo/Calc.java: 10"), errors);
            assertTrue(errors.contains("Calc.java:13 changed conditional boundary"), errors);
            assertTrue(errors.contains("Calc.java:13 takes 3 of its 4 branches"), errors);
            assertTrue(errors.contains("call the sheriff_fix tool with component 'app' and verify true"), errors);
            assertTrue(errors.contains("--call sheriff_fix component='app' verify=true"), errors);
            assertTrue(gate(ProcessOutcome.completed(0, "", "")).coverageStep("api")
                    .startsWith("Next step: add tests under the component's src/test"));
        }

        @Test
        @DisplayName("a measure that cannot run lets the turn end, and says why; with tests off nothing is measured")
        void anUnmeasuredTurnEnds() throws IOException {
            SheriffStopHook stop = withTests(com.kaizten.sheriff.domain.valueobject.VerificationResult.passed("ok"))
                    .withChangeCoverage(component -> CoverageVerdict.unmeasured("no network"));
            assertEquals(HookExit.ALLOW, capturingStandardError(() -> stop.decide("{\"stop_hook_active\":false}")));
            assertTrue(errors.contains("could not be measured (no network)"), errors);
            List<String> measured = new ArrayList<>();
            SheriffStopHook untested = new SheriffStopHook(gate(ProcessOutcome.completed(0, "", "")),
                    gitSeeingAppTouched()).withChangeCoverage(component -> {
                        measured.add(component);
                        return CoverageVerdict.nothingToMeasure();
                    });
            assertEquals(HookExit.ALLOW, untested.decide("{\"stop_hook_active\":false}"));
            assertEquals(List.of(), measured, "the measure runs the tests, which are off");
        }

        @Test
        @DisplayName("when the stops with nothing changed run out, the errors left are left for the end: the next turn "
                + "is not sent back for them, and says so")
        void errorsLeftOverAreLeftForTheEnd() throws IOException {
            StuckErrors stuck = new StuckErrors(repository.resolve("stuck"));
            StopBlocks blocks = new StopBlocks(repository.resolve("stop-blocks"));
            List<SheriffStopHook> stops = new java.util.ArrayList<>();
            for (int stop = 0; stop < 3; stop++) {
                ComponentGate gate = gate(ProcessOutcome.completed(0, SHERIFF_OUTPUT, "")).withStuckErrors(stuck);
                stops.add(new SheriffStopHook(gate, gitSeeingAppTouched(), null, blocks,
                        new TurnStart(repository.resolve("turns")), 5));
            }
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> stops.get(0).decide(FRESH_STOP)));
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> stops.get(1).decide(STOP_AGAIN)));
            assertEquals(HookExit.ALLOW, capturingStandardError(() -> stops.get(2).decide(STOP_AGAIN)));
            assertEquals(2, stuck.deferred(repository.resolve("app")).size());
            SheriffStopHook nextTurn = new SheriffStopHook(gate(ProcessOutcome.completed(0, SHERIFF_OUTPUT, ""))
                    .withStuckErrors(stuck), gitSeeingAppTouched(), null, blocks,
                    new TurnStart(repository.resolve("turns")), 5);
            assertEquals(HookExit.ALLOW, capturingStandardError(() -> nextTurn.decide(FRESH_STOP)));
            assertTrue(errors.contains("app: its 2 error(s) are left for the end"), errors);
        }

        @Test
        @DisplayName("a Stop hook that cannot run Sheriff lets the turn end, and holds nothing as left for the end")
        void anUnavailableCheckHoldsNothing() throws IOException {
            SheriffStopHook stop = new SheriffStopHook(gate(ProcessOutcome.unavailable("docker is not on the PATH"))
                    .withStuckErrors(new StuckErrors(repository.resolve("stuck"))), gitSeeingAppTouched());
            assertEquals(HookExit.ALLOW, capturingStandardError(() -> stop.decide(FRESH_STOP)));
            assertFalse(errors.contains("left for the end"), errors);
        }

        @Test
        @DisplayName("with two components whose tests are weak, the step names the first")
        void theStepNamesTheFirstWeakComponent() throws IOException {
            Files.createDirectories(repository.resolve("other/src"));
            Files.writeString(repository.resolve("other/src/B.java"), "class B {}");
            FakeProcessRunner gitRunner = new FakeProcessRunner(List.of(
                    ProcessOutcome.completed(0, repository.toRealPath() + "\n", ""),
                    ProcessOutcome.completed(0, " M app/src/A.java\0 M other/src/B.java\0", "")));
            CoverageVerdict weak = new CoverageVerdict(true, "", new Tally(2, 1), Tally.none(), Tally.none(), Map.of(),
                    List.of(), List.of(), CoverageThresholds.defaults());
            SheriffStopHook stop = new SheriffStopHook(gate(ProcessOutcome.completed(0, "", "")),
                    new com.kaizten.sheriff.infrastructure.git.GitVersionControl(gitRunner, repository, repository, ""),
                    component -> com.kaizten.sheriff.domain.valueobject.VerificationResult.passed("ok"))
                    .withChangeCoverage(component -> weak);
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> stop.decide("{\"stop_hook_active\":false}")));
            assertTrue(errors.contains("app: The tests run") && errors.contains("other: The tests run"), errors);
            assertTrue(errors.contains("call the sheriff_fix tool with component 'app' and verify true"), errors);
        }

        @Test
        @DisplayName("with tests on, nothing to measure says nothing, and tests that test the turn well let it end")
        void aGoodOrAbsentMeasureLetsTheTurnEnd() throws IOException {
            CoverageVerdict good = new CoverageVerdict(true, "", new Tally(2, 2), new Tally(2, 2), new Tally(2, 2),
                    Map.of(), List.of(), List.of(), CoverageThresholds.defaults());
            SheriffStopHook nothing = withTests(com.kaizten.sheriff.domain.valueobject.VerificationResult.passed("ok"))
                    .withChangeCoverage(component -> CoverageVerdict.nothingToMeasure());
            assertEquals(HookExit.ALLOW, capturingStandardError(() -> nothing.decide("{\"stop_hook_active\":false}")));
            assertFalse(errors.contains("could not be measured"), errors);
            SheriffStopHook measured = withTests(com.kaizten.sheriff.domain.valueobject.VerificationResult.passed("ok"))
                    .withChangeCoverage(component -> good);
            assertEquals(HookExit.ALLOW, capturingStandardError(() -> measured.decide("{\"stop_hook_active\":false}")));
        }

        /**
         * The commands a stop runs, in a repository of its own: two stops on
         * one repository share the gate's record of verdicts, and when both
         * wrote their file within one tick of the file system's clock the
         * second reused the first's verdict and ran no Sheriff.
         */
        private List<List<String>> commandsOfAStopWith(String folder, Map<String, String> settings)
                throws IOException {
            Path project = repository.resolve(folder);
            Files.createDirectories(project.resolve("app/src/main/java"));
            Files.writeString(project.resolve("app/src/main/java/A.java"), "class A { public int a() { return 1; } }");
            Map<String, String> environment = new java.util.HashMap<>(settings);
            environment.put("TARGET_REPO", project.toString());
            FakeProcessRunner processes = new FakeProcessRunner(List.of(
                    ProcessOutcome.completed(0, project.toRealPath() + "\n", ""),
                    ProcessOutcome.completed(0, " M app/src/main/java/A.java\0", ""),
                    ProcessOutcome.completed(0, "", "")));
            new Composition(new Configuration(environment, agent), processes).stopHook()
                    .decide("{\"stop_hook_active\":false}");
            return processes.commands();
        }

        @Test
        @DisplayName("while an idea is tried out, a clean turn ends without git being asked for new methods")
        void aPrototypeAsksNothingOfTheChange() throws IOException {
            List<List<String>> checked = commandsOfAStopWith("checked", Map.of());
            List<List<String>> prototype = commandsOfAStopWith("prototype", Map.of("SHERIFF_PROTOTYPE", "1"));
            assertEquals(List.of("git", "status", "--porcelain", "-z", "--untracked-files=all"),
                    checked.get(checked.size() - 1), "the change is asked for its new methods: " + checked);
            assertEquals("docker", prototype.get(prototype.size() - 1).get(0), "Sheriff is the last: " + prototype);
        }

        @Test
        @DisplayName("a session outside any repository let every turn end without a word")
        void saysSoWhenGitCannotAnswer() {
            SheriffStopHook stop = stopHook(Map.of("TARGET_REPO", repository.toString()),
                    List.of(ProcessOutcome.completed(128, "", "fatal: not a git repository")));
            assertEquals(HookExit.ALLOW, capturingStandardError(() -> stop.decide("{\"stop_hook_active\":false}")));
            assertTrue(errors.contains("could not ask git"), errors);
        }

        @Test
        @DisplayName("no repository means nothing to check, never a blocked turn")
        void failsOpenOutsideARepository() {
            SheriffStopHook stop = stopHook(Map.of("TARGET_REPO", repository.toString()),
                    List.of(ProcessOutcome.completed(128, "", "fatal: not a git repository")));
            assertEquals(Set.of(), stop.touchedComponents());
        }

        @Test
        void ignoresInputItCannotParse() {
            assertEquals(HookExit.ALLOW, hook(ProcessOutcome.completed(0, "", "")).decide("not json"));
        }
    }

    @Nested
    @DisplayName("the project's settings at the end of a turn")
    class ProjectSettings {

        private static final String STOP = "{\"session_id\":\"s\",\"stop_hook_active\":false}";

        private void git(String... arguments) throws IOException, InterruptedException {
            List<String> command = new ArrayList<>(List.of("git", "-c", "user.email=t@example.com",
                    "-c", "user.name=t"));
            command.addAll(List.of(arguments));
            Process process = new ProcessBuilder(command).directory(repository.toFile()).redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            process.waitFor();
        }

        private void commitEverything() throws IOException, InterruptedException {
            git("init", "-q");
            git("add", "-A");
            git("commit", "-q", "-m", "seed");
        }

        private SheriffStopHook stop(ChosenSettings chosen) {
            return new SheriffStopHook(gate(ProcessOutcome.completed(0, "", "")),
                    new GitVersionControl(new SystemProcessRunner(), repository, repository, ""), null,
                    new StopBlocks(repository.resolve("stop-blocks")), new TurnStart(repository.resolve("turns")), 5)
                    .withChosenSettings(chosen).withSettingsBaseline(new SettingsBaseline(repository.resolve("base")));
        }

        private void turnStarts() {
            assertEquals(HookExit.ALLOW, stop().startTurn("{\"session_id\":\"s\"}"));
        }

        private void write(String settings) throws IOException {
            Files.writeString(repository.resolve(".sheriff.properties"), settings);
        }

        private SheriffStopHook stop() {
            return stop(new ChosenSettings(repository.resolve("chosen")));
        }

        @Test
        @DisplayName("a model that turns prototype mode on in the project's file is sent back, told which line")
        void aLooserProjectFileSendsTheTurnBack() throws IOException, InterruptedException {
            commitEverything();
            Files.writeString(repository.resolve(".sheriff.properties"), "SHERIFF_PROTOTYPE=1\n");
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> stop().decide(STOP)));
            assertTrue(errors.contains(".sheriff.properties: SHERIFF_PROTOTYPE unset -> 1"), errors);
            assertTrue(errors.contains("Next step: put each line back to its value before the arrow;"), errors);
        }

        @Test
        @DisplayName("a threshold a pom.xml lowers is sent back too, and one it raises is not")
        void aLowerThresholdInAPomSendsTheTurnBack() throws IOException, InterruptedException {
            String pom = "<project><properties><sheriff.coverage>%s</sheriff.coverage></properties></project>";
            Files.writeString(repository.resolve("app/pom.xml"), String.format(pom, "90"));
            commitEverything();
            Files.writeString(repository.resolve("app/pom.xml"), String.format(pom, "40"));
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> stop().decide(STOP)));
            assertTrue(errors.contains("pom.xml: sheriff.coverage 90 -> 40"), errors);
            Files.writeString(repository.resolve("app/pom.xml"), String.format(pom, "100"));
            assertEquals(HookExit.ALLOW, capturingStandardError(() -> stop().decide(STOP)));
            assertFalse(errors.contains("Not done"), errors);
        }

        @Test
        @DisplayName("a person who edits the file by hand and commits it is not sent anything")
        void aCommittedChangeIsTheUsers() throws IOException, InterruptedException {
            Files.writeString(repository.resolve(".sheriff.properties"), "SHERIFF_PROTOTYPE=1\ncoverage=50\n");
            commitEverything();
            assertEquals(HookExit.ALLOW, capturingStandardError(() -> stop().decide(STOP)));
            assertFalse(errors.contains("Not done"), errors);
        }

        @Test
        @DisplayName("a line sheriff_config wrote is the user's; one written by hand beside it is not")
        void aChangeMadeTheUsersWayEndsTheTurn() throws IOException, InterruptedException {
            commitEverything();
            Path file = repository.resolve(".sheriff.properties");
            Files.writeString(file, "SHERIFF_STOP_MAX_BLOCKS=2\n");
            ChosenSettings chosen = new ChosenSettings(repository.resolve("chosen"));
            chosen.record(file, "SHERIFF_STOP_MAX_BLOCKS", "2");
            assertEquals(HookExit.ALLOW, capturingStandardError(() -> stop(chosen).decide(STOP)));
            Files.writeString(file, "SHERIFF_STOP_MAX_BLOCKS=2\nSHERIFF_COVERAGE=10\n");
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> stop(chosen).decide(STOP)));
            assertTrue(errors.contains("SHERIFF_COVERAGE unset -> 10"), errors);
            assertFalse(errors.contains("SHERIFF_STOP_MAX_BLOCKS"), errors);
        }

        @Test
        @DisplayName("a settings file that checks as much or more ends the turn as before")
        void aStricterFileIsNotSentBack() throws IOException, InterruptedException {
            commitEverything();
            Files.writeString(repository.resolve(".sheriff.properties"), "profile=JAVA_HEXAGONAL\nmutation=90\n");
            assertEquals(HookExit.ALLOW, capturingStandardError(() -> stop().decide(STOP)));
            assertFalse(errors.contains("Not done"), errors);
        }

        @Test
        @DisplayName("a loosening the model writes and commits inside the turn is caught: the turn's start is "
                + "compared with, not the last commit")
        void aLooseningCommittedInTheTurnIsCaught() throws IOException, InterruptedException {
            write("coverage=100\n");
            commitEverything();
            turnStarts();
            write("coverage=10\n");
            git("commit", "-q", "-am", "lower");
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> stop().decide(STOP)));
            assertTrue(errors.contains(".sheriff.properties: coverage 100 -> 10"), errors);
        }

        @Test
        @DisplayName("a person who commits a looser setting between turns, or leaves one uncommitted, is not sent "
                + "anything; what the turn itself loosens is")
        void whatThePersonDidBetweenTurnsIsTheirs() throws IOException, InterruptedException {
            write("coverage=100\nbranches=100\nmutation=90\n");
            commitEverything();
            write("coverage=50\nbranches=100\nmutation=90\n");
            git("commit", "-q", "-am", "the person's");
            write("coverage=50\nbranches=60\nmutation=90\n");
            turnStarts();
            assertEquals(HookExit.ALLOW, capturingStandardError(() -> stop().decide(STOP)));
            assertFalse(errors.contains("Not done"), errors);
            write("coverage=50\nbranches=60\nmutation=10\n");
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> stop().decide(STOP)));
            assertTrue(errors.contains("mutation 90 -> 10"), errors);
            assertFalse(errors.contains("coverage") || errors.contains("branches"), errors);
        }

        @Test
        @DisplayName("a loosening a turn ended with, the stops run out, is not the next turn's starting point: it is "
                + "sent back until put back, and only then let go")
        void aLooseningThatOutlastsTheStopsIsHeld() throws IOException, InterruptedException {
            write("coverage=100\n");
            commitEverything();
            turnStarts();
            write("coverage=10\n");
            git("commit", "-q", "-am", "lower");
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> stop().decide(STOP)));
            turnStarts();
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> stop().decide(STOP)));
            assertTrue(errors.contains("coverage 100 -> 10"), errors);
            write("coverage=100\n");
            assertEquals(HookExit.ALLOW, capturingStandardError(() -> stop().decide(STOP)));
            git("commit", "-q", "-am", "back");
            write("coverage=10\n");
            git("commit", "-q", "-am", "the person's, between turns");
            turnStarts();
            assertEquals(HookExit.ALLOW, capturingStandardError(() -> stop().decide(STOP)));
            assertFalse(errors.contains("Not done"), errors);
        }

        @Test
        @DisplayName("a model that will not put the lines back is told to tell the user before it stops, and the turn "
                + "after is sent back over them again")
        void aStalledLooseningIsToldAndKept() throws IOException, InterruptedException {
            String again = "{\"session_id\":\"s\",\"stop_hook_active\":true}";
            write("coverage=100\n");
            commitEverything();
            turnStarts();
            write("coverage=10\n");
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> stop().decide(STOP)));
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> stop().decide(again)));
            assertTrue(errors.contains("still check less. Put them back now. If you stop again without changing them, "
                    + "tell\nthe user each one first"), errors);
            assertEquals(HookExit.ALLOW, capturingStandardError(() -> stop().decide(again)));
            turnStarts();
            assertEquals(HookExit.BLOCK, capturingStandardError(() -> stop().decide(STOP)));
            assertTrue(errors.contains("coverage 100 -> 10"), errors);
        }
    }
}
