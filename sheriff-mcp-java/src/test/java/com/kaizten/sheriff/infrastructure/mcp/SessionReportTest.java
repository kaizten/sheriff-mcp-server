package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
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
