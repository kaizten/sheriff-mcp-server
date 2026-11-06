package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the report of one session of the MCP server.
 */
final class SessionReportTest {

    @TempDir
    Path folder;

    private static SheriffFinding error(String file, String rule) {
        return new SheriffFinding(file, "a description that quotes code", "h", rule, SheriffFinding.ERROR, Map.of());
    }

    private static AnalysisResult errors(SheriffFinding... findings) {
        return AnalysisResult.of(List.of(findings));
    }

    private JsonNode read(Path file) throws IOException {
        return new ObjectMapper().readTree(Files.readString(file));
    }

    @Test
    @DisplayName("a session says who connected, every call with its analyses, and what it started from and left")
    void aSessionIsWrittenAfterEveryCall() throws IOException {
        Path file = folder.resolve("reports").resolve("session.json");
        SessionReport report = new SessionReport(file, Map.of("project", Map.of("repository", "/code/shop")));
        report.client("claude-code", "2.4.1");
        report.analyzed("app", "JAVA", null, errors(error("A", "R1"), error("B", "R2"), error("B", "R2")),
                0);
        report.called("sheriff_test", Map.of("component", "app"), Duration.ofSeconds(4), false);
        JsonNode afterOne = read(file);
        assertEquals(1, afterOne.get("totals").get("calls").asInt());
        report.analyzed("app", "JAVA", errors(error("A", "R1"), error("B", "R2"), error("B", "R2")),
                errors(error("B", "R2")), 2);
        report.called("sheriff_fix", Map.of("component", "app"), Duration.ofSeconds(6), false);
        report.called("sheriff_guidelines", Map.of("query", "javadoc"), Duration.ofMillis(5), true);
        JsonNode session = read(file);
        assertEquals(SessionReport.SCHEMA, session.get("schema").asInt());
        assertEquals("session", session.get("kind").asText());
        assertEquals("claude-code", session.get("client").get("name").asText());
        assertEquals("2.4.1", session.get("client").get("version").asText());
        assertEquals("/code/shop", session.get("project").get("repository").asText());
        JsonNode totals = session.get("totals");
        assertEquals(3, totals.get("calls").asInt());
        assertEquals(1, totals.get("by_tool").get("sheriff_fix").asInt());
        assertEquals(1, totals.get("failed_calls").asInt());
        assertEquals(10005, totals.get("calls_ms").asLong());
        assertEquals(3, totals.get("first_errors").get("after").get("total").asInt());
        assertEquals(1, totals.get("last_errors").get("app").get("total").asInt());
        JsonNode fix = session.get("calls").get(1);
        assertEquals("sheriff_fix", fix.get("tool").asText());
        assertEquals(3, fix.get("analyses").get(0).get("before").get("total").asInt());
        assertEquals(2, fix.get("analyses").get(0).get("rounds").asInt());
        assertTrue(session.get("calls").get(2).get("failed").asBoolean());
        assertEquals("javadoc", session.get("calls").get(2).get("arguments").get("query").asText());
        assertEquals(0, session.get("calls").get(2).get("analyses").size(), "a call's analyses stay with it");
        assertFalse(Files.readString(file).contains("quotes code"));
        assertFalse(Files.exists(file.resolveSibling("session.json.partial")));
    }

    @Test
    @DisplayName("a call is written while it runs, each analysis as it comes, so a long repair can be followed")
    void aCallIsWrittenWhileItRuns() throws IOException {
        Path file = folder.resolve("session.json");
        SessionReport report = new SessionReport(file, Map.of());
        report.started("sheriff_fix", Map.of("component", "app"));
        JsonNode started = read(file);
        assertEquals("sheriff_fix", started.get("running").get(0).get("tool").asText());
        assertEquals(0, started.get("calls").size());
        report.analyzed("app", "JAVA", errors(error("A", "R1"), error("B", "R2")), errors(error("B", "R2")), 1);
        assertEquals(1, read(file).get("running").get(0).get("analyses").size());
        report.called("sheriff_fix", Map.of("component", "app"), Duration.ofSeconds(6), false);
        JsonNode ended = read(file);
        assertEquals(0, ended.get("running").size());
        assertEquals(1, ended.get("calls").get(0).get("analyses").size());
    }

    @Test
    @DisplayName("a command is named while it runs, and its place in the text is the one it started in")
    void aCommandIsNamedWhileItRuns() throws Exception {
        Path file = folder.resolve("session-2-shop.json");
        SessionReport report = new SessionReport(file, Map.of());
        report.started("sheriff_fix", Map.of("component", "app"));
        report.starting(List.of("docker", "run", "fix"), folder);
        assertEquals("docker run fix", read(file).get("running").get(0).get("command_running").asText());
        String text = Files.readString(folder.resolve("session-2-shop.txt"));
        assertTrue(text.contains("$ docker run fix\n          in " + folder + ": running\n"), text);

        report.round("app", "JAVA", 1, errors(error("A", "R1")), true);
        report.ran(List.of("docker", "run", "fix"), folder, ProcessOutcome.completed(0, "", ""), Duration.ofSeconds(3));
        report.starting(List.of("git", "status"), null);
        report.ran(List.of("git", "status"), null, ProcessOutcome.completed(0, "", ""), Duration.ZERO);

        assertFalse(read(file).get("running").get(0).has("command_running"), "it ended");
        String ended = Files.readString(folder.resolve("session-2-shop.txt"));
        assertFalse(ended.contains(": running\n"), ended);
        int command = ended.indexOf("$ docker run fix\n          in " + folder + ": exit code 0 after 3.0 s");
        int round = ended.indexOf("round 1 of the repair");
        assertTrue(command > 0 && round > command, "the command where it started, before the round: " + ended);
        assertEquals(2, read(file).get("running").get(0).get("commands").size());
    }

    @Test
    @DisplayName("a repair's rounds and a call's commands are kept as they happen, and no command outside a call")
    void roundsAndCommandsAreKeptAsTheyHappen() throws Exception {
        Path file = folder.resolve("session.json");
        SessionReport report = new SessionReport(file, Map.of());
        report.started("sheriff_fix", Map.of("component", "app"));
        report.ran(List.of("docker", "run", "test"), folder, ProcessOutcome.completed(0, "[]", ""),
                Duration.ofSeconds(2));
        report.round("app", "JAVA", 1, errors(error("A", "R1"), error("B", "R2")), true);
        JsonNode running = read(file).get("running").get(0);
        assertEquals("docker run test", running.get("commands").get(0).get("command").asText());
        assertEquals("COMPLETED", running.get("commands").get(0).get("status").asText());
        assertEquals(2000, running.get("commands").get(0).get("elapsed_ms").asLong());
        assertEquals(2, running.get("repair_rounds").get(0).get("total").asInt());
        assertTrue(running.get("repair_rounds").get(0).get("fixers_ran").asBoolean());
        Thread outside = new Thread(() -> report.ran(List.of("docker", "pull"), null,
                ProcessOutcome.completed(0, "", ""), Duration.ZERO));
        outside.start();
        outside.join();
        report.round("app", "JAVA", 2, errors(error("B", "R2")), false);
        report.analyzed("app", "JAVA", errors(error("A", "R1"), error("B", "R2")), errors(error("B", "R2")), 1);
        report.called("sheriff_fix", Map.of("component", "app"), Duration.ofSeconds(9), false, "Next step: none");
        JsonNode call = read(file).get("calls").get(0);
        assertEquals(1, call.get("commands").size(), "the pull ran outside any call");
        assertEquals(List.of(1, 2), List.of(call.get("repair_rounds").get(0).get("round").asInt(),
                call.get("repair_rounds").get(1).get("round").asInt()));
        assertFalse(call.get("repair_rounds").get(1).get("fixers_ran").asBoolean());
        assertTrue(call.get("started").isTextual());
    }

    @Test
    @DisplayName("beside the report, a text: a summary, then every call, command, round, error and answer in order")
    void aTextReadsTheSessionInOrder() throws IOException {
        Path file = folder.resolve("session-1-shop.json");
        SessionReport report = new SessionReport(file, Map.of("project", Map.of("repository", "/code/shop")));
        report.client("codex-mcp-client", "0.166.0");
        report.started("sheriff_test", Map.of("component", "app"));
        report.analyzed("app", "JAVA", null, errors(error("/data/app/A.java", "R1")), 0);
        report.called("sheriff_test", Map.of("component", "app"), Duration.ofMillis(1500), false,
                "app has 1 error(s) under JAVA.\nNext step: call the sheriff_fix tool");
        report.started("sheriff_fix", Map.of("component", "app"));
        report.ran(List.of("docker", "run", "fix"), folder, ProcessOutcome.completed(3, "", "it broke"),
                Duration.ofSeconds(3));
        report.round("app", "JAVA", 1, errors(error("/data/app/A.java", "R1")), true);
        report.round("app", "JAVA", 2, errors(), false);
        report.analyzed("app", "JAVA", errors(error("/data/app/A.java", "R1")), errors(), 1);
        report.called("sheriff_fix", Map.of("component", "app"), Duration.ofSeconds(70), false,
                "Next step: none, the component is done");
        assertEquals(java.util.Optional.of(folder.resolve("session-1-shop.txt")), report.textFile());
        String text = Files.readString(folder.resolve("session-1-shop.txt"));
        assertTrue(text.contains("Client:   codex-mcp-client 0.166.0"), text);
        assertTrue(text.contains("Calls: 2 (sheriff_fix 1, sheriff_test 1), 0 failed, 1 min 11 s in calls"), text);
        assertTrue(text.contains("Commands run: 1, 1 not ended well"), text);
        assertTrue(text.contains("Repair rounds with fixers run: 1"), text);
        assertTrue(text.contains("First analysis: app under JAVA, 1 error(s) in 1 file(s)"), text);
        assertTrue(text.contains("Now: app under JAVA, 0 error(s) in 0 file(s)"), text);
        assertTrue(text.contains("1 -> 0 error(s), 1 round(s)"), text);
        assertTrue(text.contains("app/A.java: a description that quotes code [R1]"), "its errors, by their text");
        assertTrue(text.contains("            it broke"), "the end of a command that did not end well");
        List<String> order = List.of("Summary", "What was done, in order", "#1 sheriff_test {component=app} started",
                "#1 analysis of app under JAVA: 1 error(s)", "| Next step: call the sheriff_fix tool",
                "#2 $ docker run fix", "exit code 3 after 3.0 s", "#2 round 1 of the repair of app under JAVA",
                "Sheriff's fixers ran on them", "#2 round 2", "no fixer ran", "#2 repair of app under JAVA: 1 error(s) "
                + "before, 0 after, in 1 round(s)", "#2 sheriff_fix ended after 1 min 10 s",
                "| Next step: none, the component is done");
        int at = 0;
        for (String part : order) {
            int found = text.indexOf(part, at);
            assertTrue(found >= at, part + " after the one before it in:\n" + text);
            at = found;
        }
        assertFalse(Files.exists(folder.resolve("session-1-shop.txt.partial")));
    }

    @Test
    @DisplayName("a call answered at once while a repair runs takes none of the repair's analyses")
    void aCallOnAnotherThreadKeepsItsOwnAnalyses() throws Exception {
        Path file = folder.resolve("session.json");
        SessionReport report = new SessionReport(file, Map.of());
        report.started("sheriff_fix", Map.of());
        report.analyzed("app", "JAVA", null, errors(error("A", "R1")), 0);
        Thread other = new Thread(() -> {
            report.started("sheriff_task", Map.of());
            report.called("sheriff_task", Map.of(), Duration.ZERO, false);
        });
        other.start();
        other.join();
        report.called("sheriff_fix", Map.of(), Duration.ZERO, false);
        JsonNode session = read(file);
        assertEquals(0, session.get("calls").get(0).get("analyses").size(), "sheriff_task's");
        assertEquals(1, session.get("calls").get(1).get("analyses").size(), "sheriff_fix's");
    }

    @Test
    @DisplayName("a report turned on mid-session holds every call since it started, and one turned off stops")
    void aReportFollowsTheSetting() throws IOException {
        Path file = folder.resolve("session.json");
        java.util.concurrent.atomic.AtomicBoolean on = new java.util.concurrent.atomic.AtomicBoolean();
        SessionReport report = new SessionReport("session.json",
                () -> on.get() ? java.util.Optional.of(folder) : java.util.Optional.empty(), Map.of());
        report.called("sheriff_test", Map.of(), Duration.ZERO, false);
        assertFalse(Files.exists(file));
        on.set(true);
        report.called("sheriff_fix", Map.of(), Duration.ZERO, false);
        assertEquals(2, read(file).get("totals").get("calls").asInt());
        on.set(false);
        report.called("sheriff_fix", Map.of(), Duration.ZERO, false);
        assertEquals(2, read(file).get("totals").get("calls").asInt());
    }

    @Test
    @DisplayName("a session that analyzed nothing, from a client that gave no name, still says so")
    void anEmptySessionIsWritten() throws IOException {
        Path file = folder.resolve("session.json");
        new SessionReport(file, Map.of()).called("sheriff_task", Map.of(), Duration.ZERO, false);
        JsonNode session = read(file);
        assertTrue(session.get("client").get("name").isNull());
        assertTrue(session.get("totals").get("first_errors").isNull());
        assertEquals(0, session.get("totals").get("last_errors").size());
    }

    @Test
    @DisplayName("a report that cannot be written never fails a call")
    void anUnwritableReportIsOnlySaid() throws IOException {
        Path blocker = Files.writeString(folder.resolve("a-file"), "not a folder");
        SessionReport report = new SessionReport(blocker.resolve("session.json"), Map.of());
        report.called("sheriff_test", Map.of(), Duration.ZERO, false);
        report.called("sheriff_test", Map.of(), Duration.ZERO, false);
        assertEquals(2, report.document().get("totals").get("calls").asInt());
    }
}
