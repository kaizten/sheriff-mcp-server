package com.kaizten.sheriff.infrastructure.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kaizten.sheriff.domain.port.RunMetrics;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.PassMetrics;
import com.kaizten.sheriff.domain.valueobject.RunSummary;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.domain.valueobject.TokenUsage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * One run of the repair loop as a single JSON document, written when it
 * ends, so runs can be compared: across configurations, backends, models and
 * projects.
 *
 * <p>It holds what the run was asked to do with (its configuration, the
 * tools' version and Sheriff's image, the project and its commit), what it
 * started from and what it left, rule by rule, every pass with what it was
 * given and what it changed, how the run stopped, and the totals a
 * comparison reads first. Counts, rule codes and paths only: a finding's
 * description is never kept, as Sheriff pastes code and build output into
 * it.
 *
 * <p>The outcome's fields sit at the top, with the names the loop's
 * {@code --json-report} has always written, so whatever read that report
 * reads this one.
 *
 * <p>A run that stopped right after a pass, before any analysis measured it
 * (the fixer failed, or changed a file outside its batch), is analyzed once
 * more here, so what it left is what is in the tree. Writing never stops a
 * run: a report that cannot be written is said, and the run goes on.
 */
public final class LoopReport implements RunMetrics {

    private static final String SCHEMA_FIELD = "schema";
    private static final String KIND_FIELD = "kind";
    private static final String ID_FIELD = "id";
    private static final String STARTED_FIELD = "started";
    private static final String FINISHED_FIELD = "finished";
    private static final String ELAPSED_FIELD = "elapsed_ms";
    private static final String OK_FIELD = "ok";
    private static final String STOPPED_FIELD = "stopped_reason";
    private static final String ITERATIONS_FIELD = "iterations_used";
    private static final String MAX_ITERATIONS_FIELD = "max_iterations";
    private static final String REPAIR_FIELD = "repair_pass_used";
    private static final String PARKED_FIELD = "parked_files";
    private static final String BRANCH_FIELD = "working_branch";
    private static final String TESTS_FIELD = "tests_passed";
    private static final String INITIAL_FIELD = "initial";
    private static final String FINAL_FIELD = "final";
    private static final String TOTAL_FIELD = "total";
    private static final String FILES_FIELD = "files";
    private static final String BY_RULE_FIELD = "by_rule";
    private static final String AFTER_LAST_PASS_FIELD = "after_last_pass";
    private static final String PASSES_FIELD = "passes";
    private static final String GIVEN_FIELD = "given";
    private static final String INTRODUCED_FIELD = "introduced";
    private static final String TOTALS_FIELD = "totals";
    private static final String MODEL_PASSES_FIELD = "model_passes";
    private static final String SHERIFF_REPAIRED_FIELD = "repaired_by_sheriff";
    private static final String MODEL_REPAIRED_FIELD = "repaired_by_model";
    private static final String MODEL_INTRODUCED_FIELD = "introduced_by_model";
    private static final String MODEL_MS_FIELD = "model_ms";
    private static final String SHERIFF_MS_FIELD = "sheriff_fixers_ms";
    private static final String MODELS_FIELD = "models";
    private static final String INPUT_FIELD = "input_tokens";
    private static final String OUTPUT_FIELD = "output_tokens";
    private static final String CACHE_READ_FIELD = "cache_read_tokens";
    private static final String CACHE_WRITE_FIELD = "cache_write_tokens";
    private static final String TURNS_FIELD = "turns";
    private static final String COST_FIELD = "cost_usd";
    private static final String WRITTEN = "This run's report: %s%n";
    private static final String NOT_WRITTEN = "This run's report could not be written to %s: %s%n";
    private static final String PARTIAL_SUFFIX = ".partial";
    private static final int ID_LENGTH = 8;
    private static final int ID_START = 0;
    private static final int NONE = 0;
    private static final int ONE = 1;
    /**
     * The version of this document's shape. A field added keeps it; one
     * renamed, removed or given another meaning raises it.
     */
    public static final int SCHEMA = 1;
    /**
     * What a loop's report says it is, beside the reports of other kinds.
     */
    public static final String KIND = "loop";
    private final Path file;
    private final Map<String, Object> context;
    private final Supplier<AnalysisResult> lastLook;
    private final Instant started;
    private final String id = UUID.randomUUID().toString().substring(ID_START, ID_LENGTH);
    private final ObjectMapper json = new ObjectMapper();
    private final List<PassMetrics> passes = new ArrayList<>();
    private final Map<String, Set<String>> givenFiles = new HashMap<>();
    private final Map<String, Integer> givenErrors = new HashMap<>();
    private AnalysisResult first;
    private AnalysisResult last;

    /**
     * A report of one run, which starts now.
     *
     * @param file where it is written when the run ends
     * @param context what the run was asked to do with, section by section:
     *     each value a map, written as it is
     * @param lastLook an analysis of the component as it is, made only when
     *     the run stopped before any analysis measured its last pass
     */
    public LoopReport(Path file, Map<String, Object> context, Supplier<AnalysisResult> lastLook) {
        this.file = file;
        this.context = Map.copyOf(context);
        this.lastLook = lastLook;
        this.started = Instant.now();
    }

    /**
     * Where the report is written.
     *
     * @return that file
     */
    public Path file() {
        return file;
    }

    /**
     * Keeps one pass.
     *
     * @param pass what the pass did
     */
    @Override
    public void record(PassMetrics pass) {
        passes.add(pass);
    }

    /**
     * Keeps the first and the last analysis Sheriff could answer.
     *
     * @param analysis what Sheriff found, or why it could not say
     */
    @Override
    public void analyzed(AnalysisResult analysis) {
        if (analysis.error()) {
            return;
        }
        if (first == null) {
            first = analysis;
        }
        last = analysis;
    }

    /**
     * Keeps what a pass of the model's was given.
     *
     * @param label the pass
     * @param files the files it may change
     * @param errors how many errors it is asked to fix
     */
    @Override
    public void given(String label, Set<String> files, int errors) {
        givenFiles.put(label, new TreeSet<>(files));
        givenErrors.put(label, errors);
    }

    /**
     * Writes the report, analyzing once more first when the last pass was
     * never measured.
     *
     * @param summary what the run did and why it stopped
     */
    @Override
    public void finished(RunSummary summary) {
        boolean measured = passes.isEmpty() || passes.get(passes.size() - ONE).errorsAfter() != null;
        AnalysisResult left = last;
        if (!measured) {
            AnalysisResult look = lastLook.get();
            if (!look.error()) {
                left = look;
                measured = true;
            }
        }
        write(document(summary, left, measured, Instant.now()));
    }

    /**
     * The whole report.
     *
     * @param summary how the run ended
     * @param left the last analysis, of what the run left
     * @param measured whether that analysis came after the last pass
     * @param finished when the run ended
     * @return the document
     */
    ObjectNode document(RunSummary summary, AnalysisResult left, boolean measured, Instant finished) {
        ObjectNode root = json.createObjectNode();
        root.put(SCHEMA_FIELD, SCHEMA);
        root.put(KIND_FIELD, KIND);
        root.put(ID_FIELD, id);
        root.put(STARTED_FIELD, started.toString());
        root.put(FINISHED_FIELD, finished.toString());
        root.put(ELAPSED_FIELD, Duration.between(started, finished).toMillis());
        root.put(OK_FIELD, summary.ok());
        root.put(STOPPED_FIELD, summary.stoppedReason().wireName());
        root.put(ITERATIONS_FIELD, summary.iterationsUsed());
        root.set(MAX_ITERATIONS_FIELD, json.valueToTree(summary.maxIterations()));
        root.put(REPAIR_FIELD, summary.repairPassUsed());
        root.set(PARKED_FIELD, json.valueToTree(summary.parkedFiles()));
        root.set(BRANCH_FIELD, json.valueToTree(summary.workingBranch()));
        root.set(TESTS_FIELD, json.valueToTree(summary.testsPassed()));
        new TreeMap<>(context).forEach((section, values) -> root.set(section, json.valueToTree(values)));
        root.set(INITIAL_FIELD, counts(first));
        ObjectNode leftCounts = counts(left);
        if (left != null) {
            leftCounts.put(AFTER_LAST_PASS_FIELD, measured);
        }
        root.set(FINAL_FIELD, leftCounts);
        root.set(TOTALS_FIELD, totals());
        ArrayNode passLines = root.putArray(PASSES_FIELD);
        for (PassMetrics pass : passes) {
            passLines.add(passLine(pass));
        }
        return root;
    }

    /**
     * The errors of one analysis: how many, in how many files, rule by rule.
     *
     * @param analysis the analysis, or {@code null} when there was none
     * @return those counts, or a null node
     */
    private ObjectNode counts(AnalysisResult analysis) {
        if (analysis == null) {
            return null;
        }
        Map<String, Integer> byRule = new TreeMap<>();
        for (SheriffFinding finding : analysis.errors()) {
            byRule.merge(finding.referenceCode(), ONE, Integer::sum);
        }
        ObjectNode counts = json.createObjectNode();
        counts.put(TOTAL_FIELD, analysis.total());
        counts.put(FILES_FIELD, analysis.filesWithErrors().size());
        counts.set(BY_RULE_FIELD, json.valueToTree(byRule));
        return counts;
    }

    /**
     * One pass as the metrics file writes it, with what it was given and
     * what it added.
     *
     * @param pass the pass
     * @return its object
     */
    private ObjectNode passLine(PassMetrics pass) {
        ObjectNode line = MetricsFile.line(json, pass);
        line.set(FILES_FIELD, json.valueToTree(givenFiles.get(pass.label())));
        line.set(GIVEN_FIELD, json.valueToTree(givenErrors.get(pass.label())));
        line.set(INTRODUCED_FIELD, json.valueToTree(introduced(pass)));
        return line;
    }

    /**
     * What a pass left more of, rule by rule, measured.
     *
     * @param pass the pass
     * @return each rule whose count rose, with how much, in name order;
     *     empty when nothing was measured after it
     */
    static Map<String, Integer> introduced(PassMetrics pass) {
        Map<String, Integer> introduced = new TreeMap<>();
        if (pass.errorsAfter() != null) {
            pass.errorsAfter().forEach((rule, after) -> {
                int rose = after - pass.errorsBefore().getOrDefault(rule, NONE);
                if (rose > NONE) {
                    introduced.put(rule, rose);
                }
            });
        }
        return introduced;
    }

    /**
     * The run's totals, as a comparison reads them first.
     *
     * @return those totals
     */
    private ObjectNode totals() {
        int modelPasses = NONE;
        int sheriffRepaired = NONE;
        int modelRepaired = NONE;
        int modelIntroduced = NONE;
        long modelMillis = NONE;
        long sheriffMillis = NONE;
        long input = NONE;
        long output = NONE;
        long cacheRead = NONE;
        long cacheWrite = NONE;
        long turns = NONE;
        Double cost = null;
        Set<String> models = new TreeSet<>();
        for (PassMetrics pass : passes) {
            int repaired = pass.repaired().values().stream().mapToInt(Integer::intValue).sum();
            if (PassMetrics.MODEL.equals(pass.kind())) {
                modelPasses++;
                modelRepaired += repaired;
                modelIntroduced += introduced(pass).values().stream().mapToInt(Integer::intValue).sum();
                modelMillis += pass.elapsed().toMillis();
            } else {
                sheriffRepaired += repaired;
                sheriffMillis += pass.elapsed().toMillis();
            }
            TokenUsage usage = pass.usage();
            if (usage != null) {
                models.addAll(usage.models());
                input += usage.inputTokens();
                output += usage.outputTokens();
                cacheRead += usage.cacheReadTokens();
                cacheWrite += usage.cacheWriteTokens();
                turns += usage.turns();
                if (usage.costUsd() != null) {
                    cost = (cost == null ? NONE : cost) + usage.costUsd();
                }
            }
        }
        ObjectNode totals = json.createObjectNode();
        totals.put(PASSES_FIELD, passes.size());
        totals.put(MODEL_PASSES_FIELD, modelPasses);
        totals.put(SHERIFF_REPAIRED_FIELD, sheriffRepaired);
        totals.put(MODEL_REPAIRED_FIELD, modelRepaired);
        totals.put(MODEL_INTRODUCED_FIELD, modelIntroduced);
        totals.put(MODEL_MS_FIELD, modelMillis);
        totals.put(SHERIFF_MS_FIELD, sheriffMillis);
        totals.set(MODELS_FIELD, json.valueToTree(models));
        totals.put(INPUT_FIELD, input);
        totals.put(OUTPUT_FIELD, output);
        totals.put(CACHE_READ_FIELD, cacheRead);
        totals.put(CACHE_WRITE_FIELD, cacheWrite);
        totals.put(TURNS_FIELD, turns);
        totals.set(COST_FIELD, json.valueToTree(cost));
        return totals;
    }

    /**
     * Writes the document whole: to a file beside it, then moved into place,
     * so a reader never finds half of one.
     *
     * @param document the report
     */
    private void write(ObjectNode document) {
        Path partial = file.resolveSibling(file.getFileName() + PARTIAL_SUFFIX);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(partial, json.writerWithDefaultPrettyPrinter().writeValueAsString(document),
                    StandardCharsets.UTF_8);
            try {
                Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
            }
            System.out.printf(WRITTEN, file);
        } catch (IOException exception) {
            System.out.printf(NOT_WRITTEN, file, exception.getMessage());
        }
    }
}
