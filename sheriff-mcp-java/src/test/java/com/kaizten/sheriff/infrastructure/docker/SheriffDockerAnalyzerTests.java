package com.kaizten.sheriff.infrastructure.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.domain.valueobject.TrackedFile;
import com.kaizten.sheriff.infrastructure.process.FakeProcessRunner;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the Docker analyzer, against the output format real Sheriff
 * actually produces — a human-readable header followed by a bare JSON array,
 * with absolute container paths.
 */
class SheriffDockerAnalyzerTests {

    private static final String REAL_OUTPUT = """
            Execution results:
            \tErrors: 2
            \tWarnings: 1
            3 issues found:
            [ {"file":"/data/app/src/main/java/A.java","code":-33,"description":"Method 'a' has no JavaDoc",\
            "referenceCode":"","type":"ERROR","howToSolve":"Add a JavaDoc comment.","timestamp":"now"},
              {"file":"/data/app/src/main/java/B.java","code":-34,"description":"Hardcoded number '0'",\
            "referenceCode":"","type":"ERROR","howToSolve":"Extract a constant.","timestamp":"now"},
              {"file":"/data/app/src/main/java/B.java","code":-35,"description":"Line too long",\
            "referenceCode":"LineLength","type":"WARNING","howToSolve":"Split it.","timestamp":"now"} ]
            """;

    @TempDir
    private Path repository;

    private SheriffDockerAnalyzer analyzer(ProcessRunner runner) {
        return new SheriffDockerAnalyzer(
                runner, "kaizten/sheriff:latest", "JAVA", "app", repository, Duration.ofSeconds(300));
    }

    @Test
    void buildsTheInvocationSheriffActuallyExpects() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "", ""));
        analyzer(runner).analyze();
        List<String> command = runner.command();
        assertTrue(command.get(4).startsWith("sheriff-agent-"), "every run is a named container");
        assertEquals(
                List.of("docker", "run", "--rm", "--name", command.get(4), "--mount", "type=bind,source=" + repository + ",target=/data",
                        "kaizten/sheriff:latest", "test", "--test", "JAVA", "--uri", "file:/data",
                        "--component", "app"),
                command);
    }

    @Test
    void failingFastAddsSheriffsOwnFlagAndNothingElse() {
        assertEquals(List.of("test", "--test", "JAVA", "--uri", "file:/data", "--component", "app", "--fail-fast"),
                analyzer(FakeProcessRunner.always(ProcessOutcome.completed(0, "", ""))).failingFast().arguments());
    }

    @Test
    @DisplayName("a profile Sheriff does not know is reported in its own words, not as a parse failure")
    void aRejectedProfileIsReportedInSheriffsOwnWords() {
        AnalysisResult result = analyzer(FakeProcessRunner.always(ProcessOutcome.completed(
                0, "Usage: Sheriff [options] [command]", "ERROR. Test 'JAVAX' not valid\nUsage: Sheriff"))).analyze();
        assertTrue(result.error());
        assertEquals("ERROR. Test 'JAVAX' not valid", result.message());
    }

    @Test
    @DisplayName("an empty result Sheriff did not record is 'nothing analyzed', never 'no errors'")
    void anUnrecordedEmptyRunIsAFailure() throws IOException {
        Files.writeString(repository.resolve("sheriff_errors.json"), "{\"file:/data\": {\"other\": {\"JAVA\": []}}}");
        AnalysisResult result = analyzer(FakeProcessRunner.withoutSheriffReport(
                ProcessOutcome.completed(0, "", ""))).analyze();
        assertTrue(result.error());
        assertTrue(result.message().contains("nothing was analyzed"));
    }

    @Test
    @DisplayName("TypeScript traces every checker it runs; a clean run is only those lines, and it is clean")
    void theTypescriptTracesAreNotOutput() throws IOException {
        Files.writeString(repository.resolve("sheriff_errors.json"), "{\"file:/data\": {\"app\": {\"JAVA\": []}}}");
        String traces = "COMMAND: node entry-point.js file-comment /data/app/a.ts\n"
                + "COMMAND: node entry-point.js imports /data/app/a.ts\n";
        AnalysisResult clean = analyzer(FakeProcessRunner.always(ProcessOutcome.completed(0, traces, ""))).analyze();
        assertFalse(clean.error(), clean.message());
        assertEquals(0, clean.total());
        AnalysisResult found =
                analyzer(FakeProcessRunner.always(ProcessOutcome.completed(0, traces + REAL_OUTPUT, ""))).analyze();
        assertEquals(2, found.total());
    }

    @Test
    void anEmptyRunSheriffRecordedIsClean() throws IOException {
        Files.writeString(repository.resolve("sheriff_errors.json"), "{\"file:/data\": {\"app\": {\"JAVA\": []}}}");
        AnalysisResult result = analyzer(FakeProcessRunner.always(ProcessOutcome.completed(0, "", ""))).analyze();
        assertFalse(result.error());
        assertEquals(0, result.total());
    }

    @Test
    @DisplayName("empty stdout means no issues -- Sheriff says nothing when it is happy")
    void emptyOutputIsACleanResult() {
        AnalysisResult result = analyzer(FakeProcessRunner.always(ProcessOutcome.completed(0, "", ""))).analyze();
        assertFalse(result.error());
        assertEquals(0, result.total());
    }

    @Test
    void parsesTheHeaderAndTheJsonArrayThatFollowsIt() {
        AnalysisResult result =
                analyzer(FakeProcessRunner.always(ProcessOutcome.completed(0, REAL_OUTPUT, ""))).analyze();
        assertEquals(3, result.findings().size());
        assertEquals(2, result.total());
        assertEquals(1, result.warnings().size());
        SheriffFinding first = result.errors().get(0);
        assertEquals("Method 'a' has no JavaDoc", first.description());
        assertEquals("Add a JavaDoc comment.", first.howToSolve());
    }

    @Test
    @DisplayName("container paths are made repository-relative, or the scope check compares nonsense")
    void normalizesTheContainerMountPrefix() {
        AnalysisResult result =
                analyzer(FakeProcessRunner.always(ProcessOutcome.completed(0, REAL_OUTPUT, ""))).analyze();
        assertEquals("app/src/main/java/A.java", result.errors().get(0).file());
        assertEquals(List.of("a/b.java"), List.of(SheriffDockerAnalyzer.normalize("/data/a/b.java")));
        assertEquals("already/relative.java", SheriffDockerAnalyzer.normalize("already/relative.java"));
    }

    @Test
    void keepsEveryFieldOfTheOriginalEntry() {
        AnalysisResult result =
                analyzer(FakeProcessRunner.always(ProcessOutcome.completed(0, REAL_OUTPUT, ""))).analyze();
        assertTrue(result.errors().get(0).raw().containsKey("timestamp"));
    }

    @Test
    @DisplayName("a non-zero exit is an invocation failure, never 'no errors'")
    void reportsAnInvocationFailure() {
        AnalysisResult result = analyzer(FakeProcessRunner.always(
                ProcessOutcome.completed(1, "", "Invalid context: 'file:/data'."))).analyze();
        assertTrue(result.error());
        assertTrue(result.message().contains("Invalid context"));
    }

    @Test
    void reportsAnExitCodeWhenSheriffSaysNothing() {
        AnalysisResult result = analyzer(FakeProcessRunner.always(ProcessOutcome.completed(2, "", ""))).analyze();
        assertTrue(result.error());
        assertTrue(result.message().contains("2"));
    }

    @Test
    void reportsDockerBeingUnavailable() {
        AnalysisResult result = analyzer(FakeProcessRunner.always(
                ProcessOutcome.unavailable("'docker' is not installed or not on the PATH."))).analyze();
        assertTrue(result.error());
        assertTrue(result.message().contains("docker"));
    }

    @Test
    void reportsATimeout() {
        AnalysisResult result = analyzer(FakeProcessRunner.always(
                ProcessOutcome.timedOut("did not respond within 300s."))).analyze();
        assertTrue(result.error());
        assertTrue(result.message().contains("300"));
    }

    @Test
    void unparseableOutputIsAnErrorNotAnEmptyResult() {
        AnalysisResult result = analyzer(FakeProcessRunner.withoutSheriffReport(
                ProcessOutcome.completed(0, "something went sideways", ""))).analyze();
        assertTrue(result.error());
    }

    @Test
    void brokenJsonAfterTheBracketIsAnErrorToo() {
        AnalysisResult result =
                analyzer(FakeProcessRunner.always(ProcessOutcome.completed(0, "results:\n[ {broken", ""))).analyze();
        assertTrue(result.error());
    }

    @Test
    @DisplayName("Sheriff's leftovers are removed, or the next run refuses to start on a dirty tree")
    void deletesTheStateFilesSheriffDropsInTheRepository() throws IOException {
        for (String name : SheriffDockerAnalyzer.STATE_FILES) {
            Files.writeString(repository.resolve(name), "{}");
        }
        analyzer(FakeProcessRunner.always(ProcessOutcome.completed(0, "", ""))).analyze();
        for (String name : SheriffDockerAnalyzer.STATE_FILES) {
            assertFalse(Files.exists(repository.resolve(name)), name + " was left behind");
        }
    }

    /**
     * Reproduced against the image of 21 September: sheriff_tracked_files.json
     * holds paths and hashes only, with no profile and no rule version, and
     * Sheriff skips every file whose hash it already has. So state left by a
     * JAVA_HEXAGONAL run (0 errors) made the next JAVA run report 0 on code
     * with 3, and the JAVA entry it recorded was there, just empty, so nothing
     * downstream can tell. Sheriff must never start with state on the mount.
     */
    @Test
    @DisplayName("Sheriff starts with no state on the mount: old hashes would make it skip every unchanged file")
    void noStateIsLeftForSheriffToReuse() throws IOException {
        Files.writeString(repository.resolve("sheriff_tracked_files.json"),
                "{\"/data/app/src/A.java\": {\"hash\": \"aaa\", \"timestamp\": \"now\"}}");
        Files.writeString(repository.resolve("sheriff_errors.json"),
                "{\"file:/data\": {\"app\": {\"JAVA_HEXAGONAL\": []}}}");
        List<String> presentAtStart = new ArrayList<>();
        ProcessRunner sheriff = (command, directory, environment, timeout) -> {
            for (String name : SheriffDockerAnalyzer.STATE_FILES) {
                if (Files.exists(repository.resolve(name))) {
                    presentAtStart.add(name);
                }
            }
            return ProcessOutcome.completed(0, REAL_OUTPUT, "");
        };
        analyzer(sheriff).analyze();
        assertEquals(List.of(), presentAtStart);
    }

    @Test
    void cleansUpEvenWhenTheRunFailed() throws IOException {
        Files.writeString(repository.resolve(SheriffDockerAnalyzer.STATE_FILES.get(0)), "{}");
        analyzer(FakeProcessRunner.always(ProcessOutcome.timedOut("too slow"))).analyze();
        assertFalse(Files.exists(repository.resolve(SheriffDockerAnalyzer.STATE_FILES.get(0))));
    }

    /**
     * A Sheriff that leaves the given state files behind and prints nothing.
     */
    private ProcessRunner leaving(String errors, String tracked) {
        return (command, directory, environment, timeout) -> {
            try {
                Files.writeString(repository.resolve("sheriff_errors.json"), errors);
                Files.writeString(repository.resolve("sheriff_tracked_files.json"), tracked);
                Files.writeString(repository.resolve("sheriff_summary.json"), "{ }");
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            return ProcessOutcome.completed(0, "", "");
        };
    }

    private static final String ONE_ERROR = """
            {"file:/data": {"app": {"JAVA": [ {"file": "/data/app/src/A.java", "code": 1,
              "description": "Braces are not used", "referenceCode": "JavaBraces", "type": "ERROR",
              "howToSolve": "Add braces.", "timestamp": "now"} ]}}}
            """;

    private static final String TRACKED = """
            {"/data/app/src/A.java": {"hash": "aaa", "timestamp": "now"},
             "/data/app/pom.xml": {"hash": "ppp", "timestamp": "now"}}
            """;

    @Test
    @DisplayName("findings come from sheriff_errors.json, which records the run, not from stdout")
    void readsTheFindingsFromSheriffsOwnFile() {
        AnalysisResult result = analyzer(leaving(ONE_ERROR, TRACKED)).analyze();
        assertFalse(result.error(), result.message());
        assertEquals(1, result.total());
        assertEquals("app/src/A.java", result.errors().get(0).file());
        assertEquals("JavaBraces", result.errors().get(0).referenceCode());
    }

    @Test
    @DisplayName("the file names Java requires are never counted, whoever asks")
    void dropsKnownFalsePositives() {
        AnalysisResult result = analyzer(leaving("""
                {"file:/data": {"app": {"JAVA": [
                  {"file": "/data/app/src/A.java", "description": "unsorted", "referenceCode": "SortedImport",
                   "type": "ERROR", "howToSolve": "sort"},
                  {"file": "/data/app/src/package-info.java", "description": "not PascalCase",
                   "referenceCode": "FilenamePascalCase", "type": "ERROR", "howToSolve": "rename"},
                  {"file": "/data/app/src/A.java", "description": "Braces are not used", "referenceCode": "JavaBraces",
                   "type": "ERROR", "howToSolve": "Add braces."} ]}}}
                """, TRACKED)).analyze();
        assertFalse(result.error(), result.message());
        assertEquals(List.of("SortedImport", "JavaBraces"),
                result.errors().stream().map(finding -> finding.referenceCode()).toList());
    }

    @Test
    @DisplayName("sheriff_tracked_files.json becomes the files analyzed, with their hashes")
    void readsTheTrackedFiles() {
        AnalysisResult result = analyzer(leaving(ONE_ERROR, TRACKED)).analyze();
        assertEquals(
                List.of(new TrackedFile("app/src/A.java", "aaa"), new TrackedFile("app/pom.xml", "ppp")),
                result.trackedFiles());
    }

    @Test
    @DisplayName("two analyses name the files that changed between them")
    void comparesTwoAnalysesByHash() {
        AnalysisResult before = analyzer(leaving(ONE_ERROR, TRACKED)).analyze();
        AnalysisResult after = analyzer(leaving(ONE_ERROR, """
                {"/data/app/src/A.java": {"hash": "bbb"}, "/data/app/src/New.java": {"hash": "nnn"}}
                """)).analyze();
        assertEquals(List.of("app/src/A.java", "app/src/New.java", "app/pom.xml"), after.changedSince(before));
        assertEquals(List.of(), after.changedSince(AnalysisResult.of(List.of())));
    }

    @Test
    @DisplayName("the findings and tracked files are exported before the clean-up; the summary is not")
    void exportsTheFilesWorthKeeping(@TempDir Path export) throws IOException {
        analyzer(leaving(ONE_ERROR, TRACKED)).exportingTo(export.resolve("run")).analyze();
        assertTrue(Files.readString(export.resolve("run/sheriff_errors.json")).contains("JavaBraces"));
        assertTrue(Files.readString(export.resolve("run/sheriff_tracked_files.json")).contains("aaa"));
        assertFalse(Files.exists(export.resolve("run/sheriff_summary.json")));
        for (String name : SheriffDockerAnalyzer.STATE_FILES) {
            assertFalse(Files.exists(repository.resolve(name)), name + " was left in the mount");
        }
    }

    @Test
    @DisplayName("several profiles are one run each, in order, merged into one result and cleaned up after")
    void runsOncePerProfileAndMerges() {
        List<String> profiles = new ArrayList<>();
        ProcessRunner sheriff = (command, directory, environment, timeout) -> {
            String profile = command.get(command.indexOf("--test") + 1);
            profiles.add(profile);
            String own = "JAVA".equals(profile) ? "Braces are not used" : "Class outside the layers";
            try {
                Files.writeString(repository.resolve("sheriff_errors.json"), """
                        {"file:/data": {"app": {"%s": [
                          {"file": "/data/app/src/A.java", "description": "No JavaDoc", "referenceCode": "Doc", "type": "ERROR"},
                          {"file": "/data/app/src/A.java", "description": "%s", "referenceCode": "%s", "type": "ERROR"} ]}}}
                        """.formatted(profile, own, profile));
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            return ProcessOutcome.completed(0, "", "");
        };
        AnalysisResult result = new SheriffDockerAnalyzer(sheriff, "kaizten/sheriff:latest", "JAVA,JAVA_HEXAGONAL",
                "app", repository, Duration.ofSeconds(300)).analyze();
        assertEquals(List.of("JAVA", "JAVA_HEXAGONAL"), profiles);
        assertFalse(result.error(), result.message());
        assertEquals(3, result.total());
        assertFalse(Files.exists(repository.resolve("sheriff_errors.json")));
    }

    @Test
    @DisplayName("with several profiles, the first that fails is the result: half an analysis is not a pass")
    void aFailingProfileFailsTheWhole() {
        ProcessRunner sheriff = (command, directory, environment, timeout) ->
                command.contains("JAVA_HEXAGONAL") ? ProcessOutcome.unavailable("no docker") : ProcessOutcome.completed(0, "[]", "");
        AnalysisResult result = new SheriffDockerAnalyzer(sheriff, "kaizten/sheriff:latest", "JAVA,JAVA_HEXAGONAL",
                "app", repository, Duration.ofSeconds(300)).analyze();
        assertTrue(result.error());
    }
}
