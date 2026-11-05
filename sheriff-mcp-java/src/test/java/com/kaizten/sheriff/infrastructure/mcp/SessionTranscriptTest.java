package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for the text of a session: its summary and everything it did, in
 * order.
 */
final class SessionTranscriptTest {

    private static final Instant AT = Instant.parse("2026-11-05T10:45:20Z");

    private static AnalysisResult errors(SheriffFinding... findings) {
        return AnalysisResult.of(List.of(findings));
    }

    private static SheriffFinding error(String file, String description, String rule) {
        return new SheriffFinding(file, description, "h", rule, SheriffFinding.ERROR, Map.of());
    }

    private static JsonNode report(String json) throws Exception {
        return new ObjectMapper().readTree(json);
    }

    @Test
    @DisplayName("an empty report and nothing kept still render a header, a summary and say nothing was done")
    void nothingKeptRendersTheSummaryAlone() throws Exception {
        String text = new SessionTranscript().render(report("{}"));

        assertTrue(text.startsWith("Sheriff MCP session "), text);
        assertTrue(text.contains("Calls: 0 (), 0 failed, 0 ms in calls"), text);
        assertTrue(text.contains("Commands run: 0, 0 not ended well"), text);
        assertTrue(text.contains("Calls\n  none yet"), text);
        assertTrue(text.endsWith("What was done, in order\n\nNothing yet.\n"), text);
        assertFalse(text.contains("First analysis"), "no analysis, no first one");
    }

    @Test
    @DisplayName("a call's start, its analysis with every error by file, and its end with each line of the answer")
    void aCallIsKeptFromStartToAnswer() throws Exception {
        SessionTranscript transcript = new SessionTranscript();
        transcript.callStarted(1, AT, "sheriff_test", Map.of("profile", "JAVA", "component", "app"));
        transcript.analysis(1, AT, "app", "JAVA", errors(error("/data/app/B.java", "Line 3: tab", "Tab"),
                error("/data/app/A.java", "Line 1:\n  unsorted", "SortedImport")));
        transcript.callEnded(1, AT, "sheriff_test", Duration.ofMillis(1500), true, "first\nNext step: none");

        String text = transcript.render(report("{}"));

        assertTrue(text.contains("#1 sheriff_test {component=app, profile=JAVA} started"), text);
        assertTrue(text.contains("#1 analysis of app under JAVA: 2 error(s) in 2 file(s)"), text);
        int a = text.indexOf("app/A.java: Line 1: unsorted [SortedImport]");
        int b = text.indexOf("app/B.java: Line 3: tab [Tab]");
        assertTrue(a > 0 && b > a, "file by file, a description on one line: " + text);
        assertTrue(text.contains("#1 sheriff_test ended after 1.5 s, as a failure; its answer:\n"
                + "          | first\n          | Next step: none\n"), text);
    }

    @Test
    @DisplayName("an empty answer is kept as one empty line, and a call outside any is numbered 0")
    void anEmptyAnswerAndNoCall() throws Exception {
        SessionTranscript transcript = new SessionTranscript();
        transcript.callEnded(0, AT, "sheriff_task", Duration.ZERO, false, "");
        transcript.analysis(0, AT, "app", "JAVA", errors());

        String text = transcript.render(report("{}"));

        assertTrue(text.contains("#0 sheriff_task ended after 0 ms; its answer:\n          | \n"), text);
        assertTrue(text.contains("#0 analysis of app under JAVA: 0 error(s) in 0 file(s)\n"), text);
    }

    @Test
    @DisplayName("a command with no directory, one that did not run, and the end of a long output, cut to its last lines")
    void commandsSayHowTheyEnded() throws Exception {
        SessionTranscript transcript = new SessionTranscript();
        String output = String.join("\n", java.util.stream.IntStream.rangeClosed(1, 50).mapToObj(n -> "line " + n)
                .toList());
        transcript.command(2, AT, List.of("mvn", "test"), Path.of("/code"), ProcessOutcome.completed(1, output, ""),
                Duration.ofSeconds(75));
        transcript.command(2, AT, List.of("git", "status"), null, ProcessOutcome.unavailable("'git' is not installed"),
                Duration.ZERO);
        transcript.command(2, AT, List.of("docker", "run"), null, ProcessOutcome.completed(0, "[]", ""),
                Duration.ofSeconds(2));

        String text = transcript.render(report("{}"));

        assertTrue(text.contains("#2 $ mvn test\n          in /code: exit code 1 after 1 min 15 s"), text);
        assertFalse(text.contains("line 10\n"), "only the last 40 lines");
        assertTrue(text.contains("            line 11\n") && text.contains("            line 50\n"), text);
        assertTrue(text.contains("in this process: 'git' is not installed after 0 ms"), text);
        assertEquals(2, text.split("its output ended with", -1).length - 1,
                "the two that did not end well show their output, the one that did shows none");
    }

    @Test
    @DisplayName("a repair: its rounds with every error and whether fixers ran, then its counts before and after")
    void aRepairIsKeptRoundByRound() throws Exception {
        SessionTranscript transcript = new SessionTranscript();
        transcript.round(3, AT, "app", "JAVA", 1, errors(error("/data/app/A.java", "unsorted", "SortedImport")), true);
        transcript.round(3, AT, "app", "JAVA", 2, errors(), false);
        transcript.repair(3, AT, "app", "JAVA", errors(error("/data/app/A.java", "unsorted", "SortedImport")),
                errors(), 1);

        String text = transcript.render(report("{}"));

        assertTrue(text.contains("#3 round 1 of the repair of app under JAVA: 1 error(s) in 1 file(s), Sheriff's "
                + "fixers ran on them\n          app/A.java: unsorted [SortedImport]\n"), text);
        assertTrue(text.contains("#3 round 2 of the repair of app under JAVA: 0 error(s) in 0 file(s), no fixer "
                + "ran, so the repair ends here\n"), text);
        assertTrue(text.contains("#3 repair of app under JAVA: 1 error(s) before, 0 after, in 1 round(s)"), text);
    }

    @Test
    @DisplayName("the summary is drawn from the report: client, calls, what runs, commands, rounds, errors by rule")
    void theSummaryIsDrawnFromTheReport() throws Exception {
        JsonNode session = report("""
                {"id":"ab12cd34","started":"2026-11-05T10:44:45Z","updated":"2026-11-05T10:50:12Z",
                 "client":{"name":"codex-mcp-client","version":"0.166.0"},
                 "tools":{"version":"0.1.12","commit":"cc1ca95"},
                 "sheriff":{"image":"kaizten/sheriff:latest"},
                 "project":{"repository":"/code"},
                 "totals":{"calls":1,"by_tool":{"sheriff_fix":1},"failed_calls":0,"calls_ms":292057,
                   "first_errors":{"component":"app","profile":"JAVA",
                     "after":{"total":1345,"files":26,"by_rule":{"FourSpaceIndentation":660}}},
                   "last_errors":{"app":{"profile":"JAVA","total":214,"files":23,"by_rule":{"SortedImport":15}}}},
                 "calls":[{"tool":"sheriff_fix","arguments":{"component":"app"},"started":"2026-11-05T10:45:20Z",
                   "elapsed_ms":292057,"failed":false,
                   "analyses":[{"before":{"total":1345},"after":{"total":214},"rounds":4}],
                   "repair_rounds":[{"fixers_ran":true},{"fixers_ran":true},{"fixers_ran":false}],
                   "commands":[{"status":"COMPLETED","exit_code":0},{"status":"TIMED_OUT","exit_code":0}]}],
                 "running":[{"tool":"sheriff_test","elapsed_ms":61000,"commands":[{"status":"COMPLETED",
                   "exit_code":2}]}]}
                """);

        String text = new SessionTranscript().render(session);

        assertTrue(text.contains("Sheriff MCP session ab12cd34\nClient:   codex-mcp-client 0.166.0\n"
                + "Server:   0.1.12 (cc1ca95)\nSheriff:  image=kaizten/sheriff:latest\nProject:  /code\n"), text);
        assertTrue(text.contains("Calls: 1 (sheriff_fix 1), 0 failed, 4 min 52 s in calls"), text);
        assertTrue(text.contains("Running now: sheriff_test, for 1 min 1 s"), text);
        assertTrue(text.contains("Commands run: 3, 2 not ended well"), text);
        assertTrue(text.contains("Repair rounds with fixers run: 2"), text);
        assertTrue(text.contains("First analysis: app under JAVA, 1345 error(s) in 26 file(s)\n"
                + "    FourSpaceIndentation: 660\n"), text);
        assertTrue(text.contains("Now: app under JAVA, 214 error(s) in 23 file(s)\n    SortedImport: 15\n"), text);
        assertTrue(text.contains("sheriff_fix {\"component\":\"app\"}  4 min 52 s  1345 -> 214 error(s), 4 round(s)"),
                text);
    }
}
