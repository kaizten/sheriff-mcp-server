package com.kaizten.sheriff.infrastructure.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kaizten.sheriff.domain.port.RunMetrics;
import com.kaizten.sheriff.domain.valueobject.PassMetrics;
import com.kaizten.sheriff.domain.valueobject.TokenUsage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.TreeMap;

/**
 * The passes of one run, one JSON object a line, in the folder the prompts
 * of that run are logged in: {@code metrics-<when it started>.jsonl}.
 *
 * <p>A line per pass, written as soon as the analysis after it has measured
 * it, so a run cut short leaves what it measured. Writing it never stops a
 * run: a file that cannot be written is said once and the run goes on.
 */
public final class MetricsFile implements RunMetrics {

    private static final String STAMP_PATTERN = "yyyyMMdd-HHmmss";
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern(STAMP_PATTERN);
    private static final String FILE_FORMAT = "metrics-%s.jsonl";
    private static final String WRITTEN_MESSAGE = "  this run's measured passes are written to %s%n";
    private static final String NOT_WRITTEN_MESSAGE = "  this run's measured passes could not be written to %s: %s%n";
    private static final String LINE_END = "\n";
    private static final String LABEL = "label";
    private static final String KIND = "kind";
    private static final String ELAPSED_MS = "elapsed_ms";
    private static final String ERRORS_BEFORE = "errors_before";
    private static final String ERRORS_AFTER = "errors_after";
    private static final String REPAIRED = "repaired";
    private static final String USAGE = "usage";
    private static final String MODELS = "models";
    private static final String INPUT_TOKENS = "input_tokens";
    private static final String OUTPUT_TOKENS = "output_tokens";
    private static final String CACHE_READ_TOKENS = "cache_read_tokens";
    private static final String CACHE_WRITE_TOKENS = "cache_write_tokens";
    private static final String TURNS = "turns";
    private static final String COST_USD = "cost_usd";

    private final Path file;
    private final ObjectMapper json = new ObjectMapper();
    private boolean announced;

    /**
     * A file for one run, named after the moment it starts.
     *
     * @param directory the folder the run's prompts are logged in
     */
    public MetricsFile(Path directory) {
        this(directory.resolve(String.format(FILE_FORMAT, LocalDateTime.now().format(STAMP))), false);
    }

    /**
     * A file whose name is already chosen.
     *
     * @param file where the passes go
     * @param announced whether it has already been said where they go
     */
    MetricsFile(Path file, boolean announced) {
        this.file = file;
        this.announced = announced;
    }

    /**
     * Adds one pass as a line of the file, which is made with its folder the
     * first time.
     *
     * @param pass what the pass did
     */
    @Override
    public void record(PassMetrics pass) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(file, json.writeValueAsString(line(pass)) + LINE_END, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            announce(String.format(WRITTEN_MESSAGE, file));
        } catch (IOException exception) {
            announce(String.format(NOT_WRITTEN_MESSAGE, file, exception.getMessage()));
        }
    }

    /**
     * Where the passes go.
     *
     * @return the file
     */
    public Path file() {
        return file;
    }

    /**
     * One pass as the object its line holds.
     *
     * @param pass what the pass did
     * @return the object, with the errors in rule order
     */
    ObjectNode line(PassMetrics pass) {
        ObjectNode line = json.createObjectNode();
        line.put(LABEL, pass.label());
        line.put(KIND, pass.kind());
        line.put(ELAPSED_MS, pass.elapsed().toMillis());
        line.set(ERRORS_BEFORE, json.valueToTree(new TreeMap<>(pass.errorsBefore())));
        line.set(ERRORS_AFTER, pass.errorsAfter() == null ? json.nullNode()
                : json.valueToTree(new TreeMap<>(pass.errorsAfter())));
        line.set(REPAIRED, json.valueToTree(pass.repaired()));
        line.set(USAGE, pass.usage() == null ? json.nullNode() : usage(pass.usage()));
        return line;
    }

    /**
     * What a pass cost, as an object of its line.
     *
     * @param usage the cost
     * @return the object
     */
    private ObjectNode usage(TokenUsage usage) {
        ObjectNode node = json.createObjectNode();
        node.set(MODELS, json.valueToTree(usage.models()));
        node.put(INPUT_TOKENS, usage.inputTokens());
        node.put(OUTPUT_TOKENS, usage.outputTokens());
        node.put(CACHE_READ_TOKENS, usage.cacheReadTokens());
        node.put(CACHE_WRITE_TOKENS, usage.cacheWriteTokens());
        node.put(TURNS, usage.turns());
        if (usage.costUsd() == null) {
            node.putNull(COST_USD);
        } else {
            node.put(COST_USD, usage.costUsd());
        }
        return node;
    }

    /**
     * Says once where the passes go, or why they could not.
     *
     * @param message what to say
     */
    private void announce(String message) {
        if (!announced) {
            announced = true;
            System.out.print(message);
        }
    }
}
