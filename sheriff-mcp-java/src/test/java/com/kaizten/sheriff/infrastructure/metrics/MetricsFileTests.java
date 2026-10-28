package com.kaizten.sheriff.infrastructure.metrics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaizten.sheriff.domain.valueobject.PassMetrics;
import com.kaizten.sheriff.domain.valueobject.TokenUsage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the file a run's measured passes go to: one JSON object a line,
 * what a benchmark reads back.
 */
class MetricsFileTests {

    private static final PassMetrics MODEL_PASS = new PassMetrics("iteration-1", PassMetrics.MODEL,
            Map.of("R2", 1, "R1", 3), Map.of("R1", 1), Duration.ofMillis(1500),
            new TokenUsage(List.of("claude-sonnet-5-5"), 10, 20, 30, 40, 2, 0.25));
    private static final PassMetrics SHERIFF_PASS = new PassMetrics("sheriff-fixers", PassMetrics.DETERMINISTIC,
            Map.of("SortedImport", 2), null, Duration.ofMillis(800), null);

    @TempDir
    private Path logs;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    @DisplayName("each pass is a line, in the order measured, with the run's start in the file's name")
    void writesOneLinePerPass() throws IOException {
        MetricsFile file = new MetricsFile(logs.resolve("nested"));
        file.record(SHERIFF_PASS);
        file.record(MODEL_PASS);
        assertTrue(file.file().getFileName().toString().matches("metrics-\\d{8}-\\d{6}\\.jsonl"));
        List<String> lines = Files.readAllLines(file.file());
        assertEquals(2, lines.size());
        assertEquals("sheriff-fixers", json.readTree(lines.get(0)).path("label").asText());
        assertEquals("iteration-1", json.readTree(lines.get(1)).path("label").asText());
    }

    @Test
    @DisplayName("a model's pass: its kind, time, errors before and after in rule order, what it repaired, its cost")
    void writesAModelPassWhole() throws IOException {
        MetricsFile file = new MetricsFile(logs.resolve("m.jsonl"), true);
        file.record(MODEL_PASS);
        String line = Files.readAllLines(file.file()).get(0);
        assertTrue(line.contains("\"errors_before\":{\"R1\":3,\"R2\":1}"), line);
        JsonNode pass = json.readTree(line);
        assertEquals("model", pass.path("kind").asText());
        assertEquals(1500, pass.path("elapsed_ms").asLong());
        assertEquals(1, pass.path("errors_after").path("R1").asInt());
        assertEquals(2, pass.path("repaired").path("R1").asInt());
        assertEquals(1, pass.path("repaired").path("R2").asInt());
        JsonNode usage = pass.path("usage");
        assertEquals("claude-sonnet-5-5", usage.path("models").get(0).asText());
        assertEquals(10, usage.path("input_tokens").asLong());
        assertEquals(20, usage.path("output_tokens").asLong());
        assertEquals(30, usage.path("cache_read_tokens").asLong());
        assertEquals(40, usage.path("cache_write_tokens").asLong());
        assertEquals(2, usage.path("turns").asInt());
        assertEquals(0.25, usage.path("cost_usd").asDouble());
    }

    @Test
    @DisplayName("what was not measured or not reported is null, never a zero that reads as measured")
    void writesWhatIsUnknownAsNull() throws IOException {
        MetricsFile file = new MetricsFile(logs.resolve("m.jsonl"), true);
        file.record(SHERIFF_PASS);
        file.record(new PassMetrics("iteration-1", PassMetrics.MODEL, Map.of(), Map.of(), Duration.ZERO,
                new TokenUsage(List.of(), 1, 1, 0, 0, 1, null)));
        List<String> lines = Files.readAllLines(file.file());
        assertTrue(json.readTree(lines.get(0)).path("errors_after").isNull());
        assertTrue(json.readTree(lines.get(0)).path("usage").isNull());
        assertTrue(json.readTree(lines.get(1)).path("usage").path("cost_usd").isNull());
    }

    @Test
    @DisplayName("a line holds the pass's seven fields, and nothing else")
    void aLineHoldsThePassAndNothingElse() {
        JsonNode line = new MetricsFile(logs.resolve("m.jsonl"), true).line(SHERIFF_PASS);
        List<String> fields = new ArrayList<>();
        line.fieldNames().forEachRemaining(fields::add);
        assertEquals(List.of("label", "kind", "elapsed_ms", "errors_before", "errors_after", "repaired", "usage"),
                fields);
        assertEquals(0, line.path("repaired").size());
    }

    @Test
    @DisplayName("a file that cannot be written does not stop the run")
    void aFileThatCannotBeWrittenIsPassedOver() throws IOException {
        Path blocked = Files.writeString(logs.resolve("blocked"), "a file, not a folder");
        MetricsFile file = new MetricsFile(blocked.resolve("m.jsonl"), false);
        file.record(MODEL_PASS);
        file.record(MODEL_PASS);
        assertEquals("a file, not a folder", Files.readString(blocked));
    }
}
