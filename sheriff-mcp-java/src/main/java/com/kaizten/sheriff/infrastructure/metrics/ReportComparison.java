package com.kaizten.sheriff.infrastructure.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaizten.sheriff.domain.valueobject.PassMetrics;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.ToDoubleFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Runs of the repair loop side by side, from their reports: which
 * configuration, backend and model left least for least, run by run, and
 * rule by rule.
 *
 * <p>Runs are grouped by project (its component and the commit it started
 * from), backend, model and strategy, the grouping and the files and errors
 * a pass is given, written as {@code benchmark.sh} writes a strategy:
 * {@code file:5}, {@code rule:5}, {@code file:0:20}. A group's figures are
 * the mean of its runs, but its costs per repair are its totals divided, so
 * one run that repaired nothing does not count as an infinite cost. A
 * project with no tests leaves its runs' tests empty rather than failed.
 *
 * <p>Reports of the MCP server's sessions are read too, and compared apart:
 * by session, and by client and project, the mean of its sessions. A
 * session's tokens are its client's, which the server does not see.
 *
 * <p>A file that is no report of a schema this reads is passed over and
 * counted, so a folder can be given whole.
 */
public final class ReportComparison {

    private static final String JSON_SUFFIX = ".json";
    private static final String KIND_FIELD = "kind";
    private static final String SCHEMA_FIELD = "schema";
    private static final String CONFIGURATION_FIELD = "configuration";
    private static final String PROJECT_FIELD = "project";
    private static final String TOTALS_FIELD = "totals";
    private static final String INITIAL_FIELD = "initial";
    private static final String FINAL_FIELD = "final";
    private static final String PASSES_FIELD = "passes";
    private static final String COMPONENT_FIELD = "component";
    private static final String COMMIT_FIELD = "commit";
    private static final String BACKEND_FIELD = "backend";
    private static final String MODEL_FIELD = "model";
    private static final String MODELS_FIELD = "models";
    private static final String BATCH_BY_FIELD = "batch_by";
    private static final String MAX_FILES_FIELD = "max_files_per_batch";
    private static final String MAX_ERRORS_FIELD = "max_errors_per_batch";
    private static final String OK_FIELD = "ok";
    private static final String STOPPED_FIELD = "stopped_reason";
    private static final String TESTS_FIELD = "tests_passed";
    private static final String ELAPSED_FIELD = "elapsed_ms";
    private static final String TOTAL_FIELD = "total";
    private static final String BY_RULE_FIELD = "by_rule";
    private static final String LABEL_FIELD = "label";
    private static final String REPAIRED_FIELD = "repaired";
    private static final String INTRODUCED_FIELD = "introduced";
    private static final String GIVEN_FIELD = "given";
    private static final String FILES_FIELD = "files";
    private static final String USAGE_FIELD = "usage";
    private static final String SHERIFF_REPAIRED = "repaired_by_sheriff";
    private static final String MODEL_REPAIRED = "repaired_by_model";
    private static final String MODEL_INTRODUCED = "introduced_by_model";
    private static final String MODEL_PASSES = "model_passes";
    private static final String MODEL_MS = "model_ms";
    private static final String COST = "cost_usd";
    private static final String OUTPUT_TOKENS = "output_tokens";
    private static final String STRATEGY = "%s:%d";
    private static final String STRATEGY_WITH_ERRORS = "%s:%d:%d";
    private static final String AT_COMMIT = "%s@%s";
    private static final int SHORT_COMMIT = 7;
    private static final int START = 0;
    private static final String MODEL_SEPARATOR = "+";
    private static final String BACKENDS_DEFAULT = "(its default)";
    private static final String NOTHING = "";
    private static final String NOT_A_REPORT = "%d file(s) passed over: not a loop report of schema %d.%n";
    private static final String NO_REPORTS = "No loop report found in %s.%n";
    private static final String HEADING = "# Runs of the repair loop compared%n%n%d run(s) in %d configuration(s).%n%n";
    private static final String CONFIGURATIONS_HEADING = "## By configuration (the mean of its runs)\n\n";
    private static final String RUNS_HEADING = "## By run\n\n";
    private static final String RULES_HEADING = "## By rule (the mean of each configuration's runs)\n\n";
    private static final String NOTE = "\nA configuration is a project (its component and commit), a backend, a "
            + "model and a strategy: the grouping, the files a pass is given and, when capped, its errors. What a "
            + "model's pass repaired is what each rule lost in its files, what it introduced what each gained; "
            + "tokens are input, output and prompt cache together, and Claude Code's cost is its list price, also "
            + "on a subscription. Per repair is a configuration's totals divided, not a mean.\n";
    private static final String CELL_SEPARATOR = " | ";
    private static final String ROW_START = "| ";
    private static final String ROW_END = " |\n";
    private static final String RULE_CELL = "---";
    private static final String CSV_SEPARATOR = ",";
    private static final String QUOTE = "\"";
    private static final String DOUBLED_QUOTE = "\"\"";
    private static final String LINE_END = "\n";
    private static final String CONFIGURATIONS_FILE = "configurations.csv";
    private static final String RUNS_FILE = "runs.csv";
    private static final String RULES_FILE = "rules.csv";
    private static final String PASSES_FILE = "passes.csv";
    private static final String ONE_DECIMAL = "%.1f";
    private static final String FOUR_DECIMALS = "%.4f";
    private static final double MILLIS = 1000.0;
    private static final String INPUT_TOKENS = "input_tokens";
    private static final String CACHE_READ_TOKENS = "cache_read_tokens";
    private static final String CACHE_WRITE_TOKENS = "cache_write_tokens";
    private static final List<String> TOKEN_FIELDS = List.of(INPUT_TOKENS, OUTPUT_TOKENS, CACHE_READ_TOKENS,
            CACHE_WRITE_TOKENS);
    private static final String STRATEGY_COLUMN = "strategy";
    private static final String REPORT_COLUMN = "report";
    private static final String ELAPSED_S_COLUMN = "elapsed_s";
    private static final String BEFORE_COLUMN = "errors_before";
    private static final String LEFT_COLUMN = "errors_left";
    private static final String MODEL_S_COLUMN = "model_s";
    private static final String TOKENS_COLUMN = "tokens";
    private static final String TOKENS_PER_REPAIR_COLUMN = "tokens_per_repair";
    private static final String SECONDS_PER_REPAIR_COLUMN = "seconds_per_repair";
    private static final String RUNS_COLUMN = "runs";
    private static final String SUCCEEDED_COLUMN = "succeeded";
    private static final String RULE_NAME_COLUMN = "rule";
    private static final String DETECTED_COLUMN = "detected";
    private static final String RULE_LEFT_COLUMN = "left";
    private static final String PASS_COLUMN = "pass";
    private static final String SESSION_KIND = "session";
    private static final int SESSION_SCHEMA = 1;
    private static final String CLIENT_FIELD = "client";
    private static final String NAME_FIELD = "name";
    private static final String VERSION_FIELD = "version";
    private static final String REPOSITORY_FIELD = "repository";
    private static final String CALLS_FIELD = "calls";
    private static final String BY_TOOL_FIELD = "by_tool";
    private static final String FAILED_CALLS_FIELD = "failed_calls";
    private static final String CALLS_MS_FIELD = "calls_ms";
    private static final String FIRST_ERRORS_FIELD = "first_errors";
    private static final String LAST_ERRORS_FIELD = "last_errors";
    private static final String AFTER_FIELD = "after";
    private static final String TEST_TOOL = "sheriff_test";
    private static final String FIX_TOOL = "sheriff_fix";
    private static final String UNKNOWN_CLIENT = "(unnamed client)";
    private static final String CLIENT_WITH_VERSION = "%s %s";
    private static final int NOTHING_ANALYZED = -1;
    private static final String SESSIONS_FILE = "sessions.csv";
    private static final String SESSIONS_HEADING = "# Sessions of the MCP server compared%n%n%d session(s).%n%n";
    private static final String CLIENTS_HEADING = "## By client and project (the mean of its sessions)\n\n";
    private static final String BY_SESSION_HEADING = "## By session\n\n";
    private static final String SESSION_NOTE = "\nA session is the server from one client's start to its end. Its "
            + "first errors are what its first analysis found, its errors left the last analysis of each component "
            + "it analyzed, added up; the time is the time in its calls. What the client's model spent the server "
            + "does not see.\n";
    private static final String CLIENT_COLUMN = CLIENT_FIELD;
    private static final String SESSIONS_COLUMN = "sessions";
    private static final String TESTS_COLUMN = TEST_TOOL;
    private static final String FIXES_COLUMN = FIX_TOOL;
    private static final String FIRST_COLUMN = FIRST_ERRORS_FIELD;
    private static final String CALLS_S_COLUMN = "calls_s";
    private static final List<String> KEY = List.of(PROJECT_FIELD, BACKEND_FIELD, MODEL_FIELD, STRATEGY_COLUMN);
    private static final List<String> RUN_COLUMNS = Stream.concat(KEY.stream(), Stream.of(REPORT_COLUMN, OK_FIELD,
            STOPPED_FIELD, TESTS_FIELD, ELAPSED_S_COLUMN, BEFORE_COLUMN, LEFT_COLUMN, SHERIFF_REPAIRED,
            MODEL_REPAIRED, MODEL_INTRODUCED, MODEL_PASSES, MODEL_S_COLUMN, TOKENS_COLUMN, OUTPUT_TOKENS, COST,
            TOKENS_PER_REPAIR_COLUMN, SECONDS_PER_REPAIR_COLUMN)).toList();
    private static final List<String> CONFIGURATION_COLUMNS = Stream.concat(KEY.stream(), Stream.of(RUNS_COLUMN,
            SUCCEEDED_COLUMN, TESTS_FIELD, BEFORE_COLUMN, LEFT_COLUMN, SHERIFF_REPAIRED, MODEL_REPAIRED,
            MODEL_INTRODUCED, MODEL_PASSES, MODEL_S_COLUMN, TOKENS_COLUMN, COST, TOKENS_PER_REPAIR_COLUMN,
            SECONDS_PER_REPAIR_COLUMN)).toList();
    private static final List<String> RULE_COLUMNS = Stream.concat(KEY.stream(), Stream.of(RULE_NAME_COLUMN,
            DETECTED_COLUMN, SHERIFF_REPAIRED, MODEL_REPAIRED, MODEL_INTRODUCED, RULE_LEFT_COLUMN)).toList();
    private static final List<String> PASS_COLUMNS = Stream.concat(KEY.stream(), Stream.of(REPORT_COLUMN,
            PASS_COLUMN, KIND_FIELD, ELAPSED_FIELD, FILES_FIELD, GIVEN_FIELD, REPAIRED_FIELD, INTRODUCED_FIELD,
            TOKENS_COLUMN)).toList();
    private static final List<String> SESSION_KEY = List.of(CLIENT_COLUMN, PROJECT_FIELD);
    private static final List<String> SESSION_COLUMNS = Stream.concat(SESSION_KEY.stream(), Stream.of(REPORT_COLUMN,
            CALLS_FIELD, TESTS_COLUMN, FIXES_COLUMN, FAILED_CALLS_FIELD, FIRST_COLUMN, LEFT_COLUMN,
            CALLS_S_COLUMN)).toList();
    private static final List<String> CLIENT_COLUMNS = Stream.concat(SESSION_KEY.stream(), Stream.of(
            SESSIONS_COLUMN, CALLS_FIELD, FIXES_COLUMN, FIRST_COLUMN, LEFT_COLUMN, CALLS_S_COLUMN)).toList();
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> reports = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private final List<JsonNode> sessions = new ArrayList<>();
    private final List<String> sessionNames = new ArrayList<>();
    private int passedOver;

    /**
     * Reads every loop report under some files and folders.
     *
     * @param paths report files, or folders to look in at any depth
     * @return the comparison of what was found
     * @throws IOException when a folder cannot be read
     */
    public static ReportComparison of(List<Path> paths) throws IOException {
        ReportComparison comparison = new ReportComparison();
        for (Path path : paths) {
            List<Path> files;
            if (Files.isDirectory(path)) {
                try (Stream<Path> walked = Files.walk(path)) {
                    files = walked.filter(file -> file.toString().endsWith(JSON_SUFFIX)).sorted().toList();
                }
            } else {
                files = List.of(path);
            }
            for (Path file : files) {
                comparison.read(file);
            }
        }
        return comparison;
    }

    /**
     * Keeps one file when it is a loop report of this schema.
     *
     * @param file the file
     */
    private void read(Path file) {
        JsonNode report;
        try {
            report = json.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException exception) {
            report = null;
        }
        if (report != null && LoopReport.KIND.equals(report.path(KIND_FIELD).asText())
                && report.path(SCHEMA_FIELD).asInt() == LoopReport.SCHEMA) {
            reports.add(report);
            names.add(file.getFileName().toString());
        } else if (report != null && SESSION_KIND.equals(report.path(KIND_FIELD).asText())
                && report.path(SCHEMA_FIELD).asInt() == SESSION_SCHEMA) {
            sessions.add(report);
            sessionNames.add(file.getFileName().toString());
        } else {
            passedOver++;
        }
    }

    /**
     * How many runs were read.
     *
     * @return that number
     */
    public int runs() {
        return reports.size();
    }

    /**
     * How many sessions of the MCP server were read.
     *
     * @return that number
     */
    public int sessions() {
        return sessions.size();
    }

    /**
     * The comparison as a Markdown page: by configuration, by run, by rule.
     *
     * @param where what was read, for the page to say when nothing was
     * @return the page
     */
    public String markdown(String where) {
        StringBuilder page = new StringBuilder();
        if (passedOver > START) {
            page.append(String.format(NOT_A_REPORT, passedOver, LoopReport.SCHEMA));
        }
        if (reports.isEmpty() && sessions.isEmpty()) {
            return page.append(String.format(NO_REPORTS, where)).toString();
        }
        if (!reports.isEmpty()) {
            List<List<String>> configurations = configurations();
            page.append(String.format(HEADING, reports.size(), configurations.size()));
            page.append(CONFIGURATIONS_HEADING).append(table(CONFIGURATION_COLUMNS, configurations))
                    .append(LINE_END);
            page.append(RUNS_HEADING).append(table(RUN_COLUMNS, runRows())).append(LINE_END);
            page.append(RULES_HEADING).append(table(RULE_COLUMNS, rules())).append(NOTE);
        }
        if (!sessions.isEmpty()) {
            page.append(String.format(SESSIONS_HEADING, sessions.size()));
            page.append(CLIENTS_HEADING).append(table(CLIENT_COLUMNS, clients())).append(LINE_END);
            page.append(BY_SESSION_HEADING).append(table(SESSION_COLUMNS, sessionRows())).append(SESSION_NOTE);
        }
        return page.toString();
    }

    /**
     * Writes the four tables as CSV files: configurations, runs, rules and
     * passes.
     *
     * @param folder where they go, made when missing
     * @throws IOException when they cannot be written
     */
    public void writeCsv(Path folder) throws IOException {
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(CONFIGURATIONS_FILE), csv(CONFIGURATION_COLUMNS, configurations()),
                StandardCharsets.UTF_8);
        Files.writeString(folder.resolve(RUNS_FILE), csv(RUN_COLUMNS, runRows()), StandardCharsets.UTF_8);
        Files.writeString(folder.resolve(RULES_FILE), csv(RULE_COLUMNS, rules()), StandardCharsets.UTF_8);
        Files.writeString(folder.resolve(PASSES_FILE), csv(PASS_COLUMNS, passRows()), StandardCharsets.UTF_8);
        if (!sessions.isEmpty()) {
            Files.writeString(folder.resolve(SESSIONS_FILE), csv(SESSION_COLUMNS, sessionRows()),
                    StandardCharsets.UTF_8);
        }
    }

    /**
     * Every session as a row: its client, project, calls, and the errors it
     * started from and left.
     *
     * @return the rows, in the order the sessions were read
     */
    private List<List<String>> sessionRows() {
        List<List<String>> rows = new ArrayList<>();
        for (int index = START; index < sessions.size(); index++) {
            JsonNode session = sessions.get(index);
            JsonNode totals = session.path(TOTALS_FIELD);
            List<String> row = new ArrayList<>(sessionKey(session));
            row.add(sessionNames.get(index));
            row.add(totals.path(CALLS_FIELD).asText());
            row.add(Integer.toString(totals.path(BY_TOOL_FIELD).path(TEST_TOOL).asInt()));
            row.add(Integer.toString(totals.path(BY_TOOL_FIELD).path(FIX_TOOL).asInt()));
            row.add(totals.path(FAILED_CALLS_FIELD).asText());
            row.add(count(totals.path(FIRST_ERRORS_FIELD).path(AFTER_FIELD)));
            row.add(sessionLeft(session) < START ? NOTHING : whole(sessionLeft(session)));
            row.add(decimal(totals.path(CALLS_MS_FIELD).asDouble() / MILLIS));
            rows.add(row);
        }
        return rows;
    }

    /**
     * Every client and project as a row, its figures the mean of its
     * sessions.
     *
     * @return the rows, in key order
     */
    private List<List<String>> clients() {
        Map<List<String>, List<JsonNode>> groups = new TreeMap<>(Comparator.comparing(key -> String.join(
                CSV_SEPARATOR, key)));
        for (JsonNode session : sessions) {
            groups.computeIfAbsent(sessionKey(session), key -> new ArrayList<>()).add(session);
        }
        List<List<String>> rows = new ArrayList<>();
        groups.forEach((key, group) -> {
            List<String> row = new ArrayList<>(key);
            row.add(Integer.toString(group.size()));
            row.add(decimal(mean(group, session -> session.path(TOTALS_FIELD).path(CALLS_FIELD).asDouble())));
            row.add(decimal(mean(group, session -> session.path(TOTALS_FIELD).path(BY_TOOL_FIELD).path(FIX_TOOL)
                    .asDouble())));
            row.add(decimal(mean(group, session -> session.path(TOTALS_FIELD).path(FIRST_ERRORS_FIELD)
                    .path(AFTER_FIELD).path(TOTAL_FIELD).asDouble())));
            row.add(decimal(mean(group, session -> Math.max(START, sessionLeft(session)))));
            row.add(decimal(mean(group, session -> session.path(TOTALS_FIELD).path(CALLS_MS_FIELD).asDouble())
                    / MILLIS));
            rows.add(row);
        });
        return rows;
    }

    /**
     * What a session is grouped by: its client, with its version, and its
     * project's folder.
     *
     * @param session the session's report
     * @return client and project
     */
    static List<String> sessionKey(JsonNode session) {
        JsonNode client = session.path(CLIENT_FIELD);
        String name = client.path(NAME_FIELD).asText(NOTHING);
        String version = client.path(VERSION_FIELD).asText(NOTHING);
        String who = name.isEmpty() ? UNKNOWN_CLIENT : version.isEmpty() ? name
                : String.format(CLIENT_WITH_VERSION, name, version);
        Path repository = Path.of(session.path(PROJECT_FIELD).path(REPOSITORY_FIELD).asText(NOTHING));
        String project = repository.getFileName() == null ? repository.toString()
                : repository.getFileName().toString();
        return List.of(who, project);
    }

    /**
     * The errors a session left: the last analysis of each component it
     * analyzed, added up.
     *
     * @param session the session's report
     * @return that sum, or -1 when it analyzed nothing
     */
    private static double sessionLeft(JsonNode session) {
        JsonNode last = session.path(TOTALS_FIELD).path(LAST_ERRORS_FIELD);
        if (last.isEmpty()) {
            return NOTHING_ANALYZED;
        }
        double left = START;
        for (JsonNode component : last) {
            left += component.path(TOTAL_FIELD).asDouble();
        }
        return left;
    }

    /**
     * The four fields a run is grouped by.
     *
     * @param report the run's report
     * @return project, backend, model and strategy
     */
    static List<String> key(JsonNode report) {
        JsonNode project = report.path(PROJECT_FIELD);
        String commit = project.path(COMMIT_FIELD).asText(NOTHING);
        String component = project.path(COMPONENT_FIELD).asText(NOTHING);
        String where = commit.length() >= SHORT_COMMIT
                ? String.format(AT_COMMIT, component, commit.substring(START, SHORT_COMMIT)) : component;
        JsonNode knobs = report.path(CONFIGURATION_FIELD);
        String model = knobs.path(MODEL_FIELD).asText(NOTHING);
        if (model.isEmpty() || knobs.path(MODEL_FIELD).isNull()) {
            TreeSet<String> seen = new TreeSet<>();
            report.path(TOTALS_FIELD).path(MODELS_FIELD).forEach(name -> seen.add(name.asText()));
            model = seen.isEmpty() ? BACKENDS_DEFAULT : String.join(MODEL_SEPARATOR, seen);
        }
        int errors = knobs.path(MAX_ERRORS_FIELD).asInt();
        String grouping = knobs.path(BATCH_BY_FIELD).asText(NOTHING);
        int files = knobs.path(MAX_FILES_FIELD).asInt();
        String strategy = errors > START ? String.format(STRATEGY_WITH_ERRORS, grouping, files, errors)
                : String.format(STRATEGY, grouping, files);
        return List.of(where, knobs.path(BACKEND_FIELD).asText(NOTHING), model, strategy);
    }

    /**
     * Every run as a row.
     *
     * @return the rows, in the order the runs were read
     */
    private List<List<String>> runRows() {
        List<List<String>> rows = new ArrayList<>();
        for (int index = START; index < reports.size(); index++) {
            JsonNode report = reports.get(index);
            JsonNode totals = report.path(TOTALS_FIELD);
            double repaired = totals.path(MODEL_REPAIRED).asDouble();
            double tokens = tokens(totals);
            double seconds = totals.path(MODEL_MS).asDouble() / MILLIS;
            List<String> row = new ArrayList<>(key(report));
            row.add(names.get(index));
            row.add(report.path(OK_FIELD).asText());
            row.add(report.path(STOPPED_FIELD).asText());
            row.add(report.path(TESTS_FIELD).isBoolean() ? report.path(TESTS_FIELD).asText() : NOTHING);
            row.add(decimal(report.path(ELAPSED_FIELD).asDouble() / MILLIS));
            row.add(count(report.path(INITIAL_FIELD)));
            row.add(count(report.path(FINAL_FIELD)));
            row.add(totals.path(SHERIFF_REPAIRED).asText());
            row.add(totals.path(MODEL_REPAIRED).asText());
            row.add(totals.path(MODEL_INTRODUCED).asText());
            row.add(totals.path(MODEL_PASSES).asText());
            row.add(decimal(seconds));
            row.add(whole(tokens));
            row.add(totals.path(OUTPUT_TOKENS).asText());
            row.add(cost(totals.path(COST)));
            row.add(repaired > START ? whole(tokens / repaired) : NOTHING);
            row.add(repaired > START ? decimal(seconds / repaired) : NOTHING);
            rows.add(row);
        }
        return rows;
    }

    /**
     * Every configuration as a row, its figures the mean of its runs.
     *
     * @return the rows, in key order
     */
    private List<List<String>> configurations() {
        List<List<String>> rows = new ArrayList<>();
        grouped().forEach((key, runs) -> {
            double repaired = sum(runs, report -> report.path(TOTALS_FIELD).path(MODEL_REPAIRED).asDouble());
            double tokens = sum(runs, report -> tokens(report.path(TOTALS_FIELD)));
            double seconds = sum(runs, report -> report.path(TOTALS_FIELD).path(MODEL_MS).asDouble()) / MILLIS;
            boolean costed = runs.stream().anyMatch(report -> report.path(TOTALS_FIELD).path(COST).isNumber());
            List<String> row = new ArrayList<>(key);
            row.add(Integer.toString(runs.size()));
            row.add(Long.toString(runs.stream().filter(report -> report.path(OK_FIELD).asBoolean()).count()));
            boolean tested = runs.stream().anyMatch(report -> report.path(TESTS_FIELD).isBoolean());
            row.add(tested ? Long.toString(runs.stream().filter(report -> report.path(TESTS_FIELD).asBoolean())
                    .count()) : NOTHING);
            row.add(decimal(mean(runs, report -> report.path(INITIAL_FIELD).path(TOTAL_FIELD).asDouble())));
            row.add(decimal(mean(runs, report -> report.path(FINAL_FIELD).path(TOTAL_FIELD).asDouble())));
            row.add(decimal(mean(runs, report -> report.path(TOTALS_FIELD).path(SHERIFF_REPAIRED).asDouble())));
            row.add(decimal(repaired / runs.size()));
            row.add(decimal(mean(runs, report -> report.path(TOTALS_FIELD).path(MODEL_INTRODUCED).asDouble())));
            row.add(decimal(mean(runs, report -> report.path(TOTALS_FIELD).path(MODEL_PASSES).asDouble())));
            row.add(decimal(seconds / runs.size()));
            row.add(whole(tokens / runs.size()));
            row.add(costed ? String.format(Locale.ROOT, FOUR_DECIMALS,
                    mean(runs, report -> report.path(TOTALS_FIELD).path(COST).asDouble())) : NOTHING);
            row.add(repaired > START ? whole(tokens / repaired) : NOTHING);
            row.add(repaired > START ? decimal(seconds / repaired) : NOTHING);
            rows.add(row);
        });
        return rows;
    }

    /**
     * Every rule of every configuration, its figures per run: found at the
     * start, repaired by Sheriff and by the model, introduced, and left.
     *
     * @return the rows, by configuration and then by how many were found
     */
    private List<List<String>> rules() {
        List<List<String>> rows = new ArrayList<>();
        grouped().forEach((key, runs) -> {
            Map<String, double[]> byRule = new TreeMap<>();
            for (JsonNode report : runs) {
                add(byRule, report.path(INITIAL_FIELD).path(BY_RULE_FIELD), RuleColumn.DETECTED);
                add(byRule, report.path(FINAL_FIELD).path(BY_RULE_FIELD), RuleColumn.LEFT);
                for (JsonNode pass : report.path(PASSES_FIELD)) {
                    boolean model = PassMetrics.MODEL.equals(pass.path(KIND_FIELD).asText());
                    add(byRule, pass.path(REPAIRED_FIELD),
                            model ? RuleColumn.MODEL_REPAIRED : RuleColumn.SHERIFF_REPAIRED);
                    if (model) {
                        add(byRule, pass.path(INTRODUCED_FIELD), RuleColumn.INTRODUCED);
                    }
                }
            }
            byRule.entrySet().stream()
                    .sorted(Comparator.comparingDouble(
                            (Map.Entry<String, double[]> rule) -> -rule.getValue()[RuleColumn.DETECTED.ordinal()]))
                    .forEach(rule -> {
                        List<String> row = new ArrayList<>(key);
                        row.add(rule.getKey());
                        for (RuleColumn column : RuleColumn.values()) {
                            row.add(decimal(rule.getValue()[column.ordinal()] / runs.size()));
                        }
                        rows.add(row);
                    });
        });
        return rows;
    }

    /**
     * Every pass of every run as a row.
     *
     * @return the rows
     */
    private List<List<String>> passRows() {
        List<List<String>> rows = new ArrayList<>();
        for (int index = START; index < reports.size(); index++) {
            JsonNode report = reports.get(index);
            for (JsonNode pass : report.path(PASSES_FIELD)) {
                List<String> row = new ArrayList<>(key(report));
                row.add(names.get(index));
                row.add(pass.path(LABEL_FIELD).asText());
                row.add(pass.path(KIND_FIELD).asText());
                row.add(pass.path(ELAPSED_FIELD).asText());
                row.add(pass.path(FILES_FIELD).isArray() ? Integer.toString(pass.path(FILES_FIELD).size())
                        : NOTHING);
                row.add(pass.path(GIVEN_FIELD).isNumber() ? pass.path(GIVEN_FIELD).asText() : NOTHING);
                row.add(whole(sumOf(pass.path(REPAIRED_FIELD))));
                row.add(whole(sumOf(pass.path(INTRODUCED_FIELD))));
                row.add(whole(tokens(pass.path(USAGE_FIELD))));
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * The runs of each configuration.
     *
     * @return each configuration's key with its runs, in key order
     */
    private Map<List<String>, List<JsonNode>> grouped() {
        Map<List<String>, List<JsonNode>> groups = new TreeMap<>(Comparator.comparing(key -> String.join(
                CSV_SEPARATOR, key)));
        for (JsonNode report : reports) {
            groups.computeIfAbsent(key(report), key -> new ArrayList<>()).add(report);
        }
        return new LinkedHashMap<>(groups);
    }

    /**
     * Adds a rule-by-rule count to one column of the rules' figures.
     *
     * @param byRule the figures so far
     * @param counts each rule with its count
     * @param column which figure the counts are
     */
    private static void add(Map<String, double[]> byRule, JsonNode counts, RuleColumn column) {
        counts.fields().forEachRemaining(rule -> byRule.computeIfAbsent(rule.getKey(),
                name -> new double[RuleColumn.values().length])[column.ordinal()] += rule.getValue().asDouble());
    }

    /**
     * The tokens of a usage or a run's totals: input, output and both kinds
     * of prompt cache.
     *
     * @param usage the object holding them
     * @return their sum
     */
    private static double tokens(JsonNode usage) {
        return TOKEN_FIELDS.stream().mapToDouble(field -> usage.path(field).asDouble()).sum();
    }

    /**
     * The sum of a rule-by-rule count.
     *
     * @param counts each rule with its count
     * @return their sum
     */
    private static double sumOf(JsonNode counts) {
        double sum = START;
        for (JsonNode count : counts) {
            sum += count.asDouble();
        }
        return sum;
    }

    /**
     * The total of an analysis's counts, or nothing when there was none.
     *
     * @param counts the counts
     * @return the total, or empty
     */
    private static String count(JsonNode counts) {
        return counts.isObject() ? counts.path(TOTAL_FIELD).asText() : NOTHING;
    }

    /**
     * A sum over some runs.
     *
     * @param runs the runs
     * @param value what to add of each
     * @return the sum
     */
    private static double sum(List<JsonNode> runs, ToDoubleFunction<JsonNode> value) {
        return runs.stream().mapToDouble(value).sum();
    }

    /**
     * A mean over some runs.
     *
     * @param runs the runs, at least one
     * @param value what to average of each
     * @return the mean
     */
    private static double mean(List<JsonNode> runs, ToDoubleFunction<JsonNode> value) {
        return sum(runs, value) / runs.size();
    }

    /**
     * A number with one decimal.
     *
     * @param value the number
     * @return it written
     */
    private static String decimal(double value) {
        return String.format(Locale.ROOT, ONE_DECIMAL, value);
    }

    /**
     * A number rounded to a whole one.
     *
     * @param value the number
     * @return it written
     */
    private static String whole(double value) {
        return Long.toString(Math.round(value));
    }

    /**
     * A cost, or nothing when the backend gave none.
     *
     * @param cost the cost node
     * @return it written
     */
    private static String cost(JsonNode cost) {
        return cost.isNumber() ? String.format(Locale.ROOT, FOUR_DECIMALS, cost.asDouble()) : NOTHING;
    }

    /**
     * Rows as a Markdown table.
     *
     * @param columns the header
     * @param rows the rows
     * @return the table
     */
    private static String table(List<String> columns, List<List<String>> rows) {
        StringBuilder table = new StringBuilder(ROW_START).append(String.join(CELL_SEPARATOR, columns))
                .append(ROW_END).append(ROW_START)
                .append(columns.stream().map(column -> RULE_CELL).collect(Collectors.joining(CELL_SEPARATOR)))
                .append(ROW_END);
        for (List<String> row : rows) {
            table.append(ROW_START).append(String.join(CELL_SEPARATOR, row)).append(ROW_END);
        }
        return table.toString();
    }

    /**
     * Rows as CSV, every cell quoted that needs it.
     *
     * @param columns the header
     * @param rows the rows
     * @return the CSV text
     */
    private static String csv(List<String> columns, List<List<String>> rows) {
        StringBuilder text = new StringBuilder(String.join(CSV_SEPARATOR, columns)).append(LINE_END);
        for (List<String> row : rows) {
            text.append(row.stream().map(ReportComparison::csvCell).collect(Collectors.joining(CSV_SEPARATOR)))
                    .append(LINE_END);
        }
        return text.toString();
    }

    /**
     * One CSV cell, quoted when it holds a comma or a quote.
     *
     * @param cell the cell
     * @return it written
     */
    private static String csvCell(String cell) {
        return cell.contains(CSV_SEPARATOR) || cell.contains(QUOTE)
                ? QUOTE + cell.replace(QUOTE, DOUBLED_QUOTE) + QUOTE : cell;
    }
}
