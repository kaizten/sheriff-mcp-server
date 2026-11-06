package com.kaizten.sheriff.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * One session of the MCP server as text to read from top to bottom: a
 * summary, then everything the session did in the order it did it.
 *
 * <p>The summary is drawn from the session's JSON report, so the two never
 * disagree. What follows it is every call with its arguments, every command
 * it ran with how it ended, every round of a repair with every error its
 * analysis found, every analysis, and the answer the model was given. Unlike
 * the JSON report, which is for comparing sessions and holds counts and rule
 * codes only, this holds what Sheriff wrote of each error, as it is for going
 * through a session in detail afterwards.
 */
final class SessionTranscript {

    private static final String TITLE = "Sheriff MCP session %s%n";
    private static final String CLIENT_LINE = "Client:   %s %s%n";
    private static final String TOOLS_LINE = "Server:   %s (%s)%n";
    private static final String SHERIFF_LINE = "Sheriff:  %s%n";
    private static final String PROJECT_LINE = "Project:  %s%n";
    private static final String TIMES_LINE = "Started:  %s, updated %s%n";
    private static final String SUMMARY = "%nSummary%n";
    private static final String CALLS_LINE = "  Calls: %d%s, %d failed, %s in calls%n";
    private static final String BY_TOOL = " (%s)";
    private static final String RUNNING_LINE = "  Running now: %s, for %s%n";
    private static final String COMMANDS_LINE = "  Commands run: %d, %d not ended well%n";
    private static final String ROUNDS_LINE = "  Repair rounds with fixers run: %d%n";
    private static final String FIRST_LINE = "  First analysis: %s under %s, %d error(s) in %d file(s)%n";
    private static final String LAST_LINE = "  Now: %s under %s, %d error(s) in %d file(s)%n";
    private static final String RULES_LINE = "    %s: %d%n";
    private static final String CALL_LIST = "%nCalls%n";
    private static final String CALL_ROW = "  %s  %s %s  %s%s%s%n";
    private static final String ANALYSIS_CELL = "  %s error(s)";
    private static final String REPAIR_CELL = "  %d -> %d error(s), %d round(s)";
    private static final String FAILED_CELL = "  FAILED";
    private static final String NO_CALLS = "  none yet%n";
    private static final String HISTORY = "%nWhat was done, in order%n";
    private static final String STARTED_ENTRY = "%n[%s] #%d %s %s started%n";
    private static final String COMMAND_ENTRY = "[%s] #%d $ %s%n          in %s: %s after %s%n";
    private static final String RUNNING_COMMAND_ENTRY = "[%s] #%d $ %s%n          in %s: running%n";
    private static final String OUTPUT_HEAD = "          its output ended with:%n";
    private static final String OUTPUT_LINE = "            %s%n";
    private static final String EXIT_CODE = "exit code %d";
    private static final String ROUND_ENTRY = "[%s] #%d round %d of the repair of %s under %s: %d error(s) in %d file(s), %s%n";
    private static final String FIXERS_RAN = "Sheriff's fixers ran on them";
    private static final String NO_FIXERS = "no fixer ran, so the repair ends here";
    private static final String ANALYSIS_ENTRY = "[%s] #%d analysis of %s under %s: %d error(s) in %d file(s)%n";
    private static final String REPAIR_ENTRY = "[%s] #%d repair of %s under %s: %d error(s) before, %d after, in %d round(s)%n";
    private static final String ERROR_LINE = "          %s: %s [%s]%n";
    private static final String ENDED_ENTRY = "[%s] #%d %s ended after %s%s; its answer:%n";
    private static final String FAILED_SUFFIX = ", as a failure";
    private static final String ANSWER_LINE = "          | %s%n";
    private static final String NOTHING_YET = "%nNothing yet.%n";
    private static final String OUTSIDE_A_CALL = "this process";
    private static final String SEPARATOR = ", ";
    private static final String SPACE = " ";
    private static final String PAIR = "%s=%s";
    private static final String COUNT = "%s %d";
    private static final String MILLISECONDS = "%d ms";
    private static final String SECONDS = "%.1f s";
    private static final String MINUTES = "%d min %d s";
    private static final String MOUNT_PREFIX = "/data/";
    private static final String EMPTY = "";
    private static final String LINE_BREAK = "\\R";
    private static final String STAMP = "HH:mm:ss";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern(STAMP);
    private static final String CLIENT_FIELD = "client";
    private static final String NAME_FIELD = "name";
    private static final String VERSION_FIELD = "version";
    private static final String TOOLS_FIELD = "tools";
    private static final String COMMIT_FIELD = "commit";
    private static final String SHERIFF_FIELD = "sheriff";
    private static final String PROJECT_FIELD = "project";
    private static final String REPOSITORY_FIELD = "repository";
    private static final String ID_FIELD = "id";
    private static final String STARTED_FIELD = "started";
    private static final String UPDATED_FIELD = "updated";
    private static final String TOTALS_FIELD = "totals";
    private static final String CALLS_FIELD = "calls";
    private static final String BY_TOOL_FIELD = "by_tool";
    private static final String FAILURES_FIELD = "failed_calls";
    private static final String CALLS_MS_FIELD = "calls_ms";
    private static final String FIRST_FIELD = "first_errors";
    private static final String LAST_FIELD = "last_errors";
    private static final String RUNNING_FIELD = "running";
    private static final String TOOL_FIELD = "tool";
    private static final String ARGUMENTS_FIELD = "arguments";
    private static final String ELAPSED_FIELD = "elapsed_ms";
    private static final String FAILED_FIELD = "failed";
    private static final String ANALYSES_FIELD = "analyses";
    private static final String BEFORE_FIELD = "before";
    private static final String AFTER_FIELD = "after";
    private static final String ROUNDS_FIELD = "rounds";
    private static final String COMMANDS_FIELD = "commands";
    private static final String REPAIR_ROUNDS_FIELD = "repair_rounds";
    private static final String FIXERS_RAN_FIELD = "fixers_ran";
    private static final String STATUS_FIELD = "status";
    private static final String EXIT_CODE_FIELD = "exit_code";
    private static final String COMPONENT_FIELD = "component";
    private static final String PROFILE_FIELD = "profile";
    private static final String TOTAL_FIELD = "total";
    private static final String FILES_FIELD = "files";
    private static final String BY_RULE_FIELD = "by_rule";
    private static final String COMPLETED = "COMPLETED";
    private static final int OUTPUT_LINES = 40;
    private static final int NONE = 0;
    private static final int ONE = 1;
    private static final int SUCCESS = 0;
    private static final int MILLIS_PER_SECOND = 1000;
    private static final int SECONDS_PER_MINUTE = 60;
    private static final double MILLIS_PER_SECOND_DECIMAL = 1000.0;
    private final List<String> entries = new ArrayList<>();

    /**
     * Keeps the start of one call.
     *
     * @param call the call's number in the session, from 1
     * @param at when it started
     * @param tool the tool called
     * @param arguments what it was called with
     */
    void callStarted(int call, Instant at, String tool, Map<String, Object> arguments) {
        entries.add(String.format(STARTED_ENTRY, time(at), call, tool, new TreeMap<>(arguments)));
    }

    /**
     * Keeps one command a call ran, with how it ended and, when it did not
     * end well, the end of what it wrote.
     *
     * @param call the call's number, 0 for none
     * @param at when it ended
     * @param command the command
     * @param directory where it ran, {@code null} for the server's own
     * @param outcome how it ended
     * @param took how long it ran
     */
    void command(int call, Instant at, List<String> command, Path directory, ProcessOutcome outcome, Duration took) {
        entries.add(commandEntry(call, at, command, directory, outcome, took));
    }

    /**
     * Keeps one command a call is starting, in its place in the order, as
     * running until {@link #commandEnded} says how it ended.
     *
     * @param call the call's number, 0 for none
     * @param at when it started
     * @param command the command
     * @param directory where it runs, {@code null} for the server's own
     * @return its place, for {@link #commandEnded}
     */
    int commandStarted(int call, Instant at, List<String> command, Path directory) {
        entries.add(String.format(RUNNING_COMMAND_ENTRY, time(at), call, String.join(SPACE, command),
                directory == null ? OUTSIDE_A_CALL : directory));
        return entries.size() - ONE;
    }

    /**
     * Says, in the place a command was kept when it started, how it ended.
     *
     * @param place what {@link #commandStarted} gave
     * @param call the call's number, 0 for none
     * @param at when it started
     * @param command the command
     * @param directory where it ran, {@code null} for the server's own
     * @param outcome how it ended
     * @param took how long it ran
     */
    void commandEnded(int place, int call, Instant at, List<String> command, Path directory, ProcessOutcome outcome,
            Duration took) {
        entries.set(place, commandEntry(call, at, command, directory, outcome, took));
    }

    /**
     * One command as it ended, with the end of what it wrote when it did not
     * end well.
     *
     * @param call the call's number, 0 for none
     * @param at the time it is shown at
     * @param command the command
     * @param directory where it ran, {@code null} for the server's own
     * @param outcome how it ended
     * @param took how long it ran
     * @return its lines
     */
    private static String commandEntry(int call, Instant at, List<String> command, Path directory,
            ProcessOutcome outcome, Duration took) {
        StringBuilder entry = new StringBuilder(String.format(COMMAND_ENTRY, time(at), call,
                String.join(SPACE, command), directory == null ? OUTSIDE_A_CALL : directory, ending(outcome),
                duration(took.toMillis())));
        if (!outcome.succeeded()) {
            entry.append(String.format(OUTPUT_HEAD));
            tail(outcome).forEach(line -> entry.append(String.format(OUTPUT_LINE, line)));
        }
        return entry.toString();
    }

    /**
     * Keeps one round of a repair: the analysis that opened it, with every
     * error, and whether fixers ran on them.
     *
     * @param call the call's number, 0 for none
     * @param at when the analysis ended
     * @param component the component repaired
     * @param profile the profile analyzed
     * @param round the round, from 1
     * @param analysis what the analysis found
     * @param fixersRan whether fixers ran on it
     */
    void round(int call, Instant at, String component, String profile, int round, AnalysisResult analysis,
            boolean fixersRan) {
        entries.add(String.format(ROUND_ENTRY, time(at), call, round, component, profile, analysis.total(),
                analysis.filesWithErrors().size(), fixersRan ? FIXERS_RAN : NO_FIXERS) + errors(analysis));
    }

    /**
     * Keeps one analysis that repaired nothing, with every error.
     *
     * @param call the call's number, 0 for none
     * @param at when it ended
     * @param component the component analyzed
     * @param profile the profile
     * @param result what it found
     */
    void analysis(int call, Instant at, String component, String profile, AnalysisResult result) {
        entries.add(String.format(ANALYSIS_ENTRY, time(at), call, component, profile, result.total(),
                result.filesWithErrors().size()) + errors(result));
    }

    /**
     * Keeps the end of one repair: its errors before and after. Each round's
     * errors are already kept with the round.
     *
     * @param call the call's number, 0 for none
     * @param at when it ended
     * @param component the component repaired
     * @param profile the profile
     * @param before what it started from
     * @param after what it left
     * @param rounds how many rounds ran fixers
     */
    void repair(int call, Instant at, String component, String profile, AnalysisResult before, AnalysisResult after,
            int rounds) {
        entries.add(String.format(REPAIR_ENTRY, time(at), call, component, profile, before.total(), after.total(),
                rounds));
    }

    /**
     * Keeps the end of one call, with the answer the model was given.
     *
     * @param call the call's number, 0 for none
     * @param at when it ended
     * @param tool the tool called
     * @param took how long it took
     * @param failed whether it answered with a failure
     * @param answer the text of the answer
     */
    void callEnded(int call, Instant at, String tool, Duration took, boolean failed, String answer) {
        StringBuilder entry = new StringBuilder(String.format(ENDED_ENTRY, time(at), call, tool,
                duration(took.toMillis()), failed ? FAILED_SUFFIX : EMPTY));
        for (String line : answer.split(LINE_BREAK)) {
            entry.append(String.format(ANSWER_LINE, line));
        }
        entries.add(entry.toString());
    }

    /**
     * The whole text: the summary, from the session's report as it stands,
     * then everything kept, in order.
     *
     * @param report the session's JSON report
     * @return the text
     */
    String render(JsonNode report) {
        StringBuilder text = new StringBuilder(header(report));
        text.append(summary(report));
        text.append(callList(report));
        text.append(String.format(HISTORY));
        if (entries.isEmpty()) {
            text.append(String.format(NOTHING_YET));
        }
        entries.forEach(text::append);
        return text.toString();
    }

    /**
     * Who connected, to what, and when.
     *
     * @param report the session's report
     * @return those lines
     */
    private static String header(JsonNode report) {
        JsonNode client = report.path(CLIENT_FIELD);
        JsonNode tools = report.path(TOOLS_FIELD);
        List<String> sheriff = new ArrayList<>();
        report.path(SHERIFF_FIELD).properties().forEach(field -> sheriff.add(String.format(PAIR, field.getKey(),
                field.getValue().asText())));
        return String.format(TITLE, report.path(ID_FIELD).asText())
                + String.format(CLIENT_LINE, client.path(NAME_FIELD).asText(), client.path(VERSION_FIELD).asText())
                + String.format(TOOLS_LINE, tools.path(VERSION_FIELD).asText(), tools.path(COMMIT_FIELD).asText())
                + String.format(SHERIFF_LINE, String.join(SEPARATOR, sheriff))
                + String.format(PROJECT_LINE, report.path(PROJECT_FIELD).path(REPOSITORY_FIELD).asText())
                + String.format(TIMES_LINE, report.path(STARTED_FIELD).asText(), report.path(UPDATED_FIELD).asText());
    }

    /**
     * The counts of the session: calls, what is running, commands, rounds,
     * and the errors it started from and has now, rule by rule.
     *
     * @param report the session's report
     * @return those lines
     */
    private static String summary(JsonNode report) {
        JsonNode totals = report.path(TOTALS_FIELD);
        List<String> byTool = new ArrayList<>();
        totals.path(BY_TOOL_FIELD).properties().forEach(field -> byTool.add(String.format(COUNT, field.getKey(),
                field.getValue().asInt())));
        StringBuilder text = new StringBuilder(String.format(SUMMARY));
        String tools = byTool.isEmpty() ? EMPTY : String.format(BY_TOOL, String.join(SEPARATOR, byTool));
        text.append(String.format(CALLS_LINE, totals.path(CALLS_FIELD).asInt(), tools,
                totals.path(FAILURES_FIELD).asInt(), duration(totals.path(CALLS_MS_FIELD).asLong())));
        report.path(RUNNING_FIELD).forEach(call -> text.append(String.format(RUNNING_LINE,
                call.path(TOOL_FIELD).asText(), duration(call.path(ELAPSED_FIELD).asLong()))));
        List<JsonNode> calls = new ArrayList<>();
        report.path(CALLS_FIELD).forEach(calls::add);
        report.path(RUNNING_FIELD).forEach(calls::add);
        List<JsonNode> commands = new ArrayList<>();
        calls.forEach(call -> call.path(COMMANDS_FIELD).forEach(commands::add));
        long badly = commands.stream().filter(command -> !COMPLETED.equals(command.path(STATUS_FIELD).asText())
                || command.path(EXIT_CODE_FIELD).asInt() != SUCCESS).count();
        text.append(String.format(COMMANDS_LINE, commands.size(), badly));
        long rounds = calls.stream().mapToLong(call -> countFixing(call.path(REPAIR_ROUNDS_FIELD))).sum();
        text.append(String.format(ROUNDS_LINE, rounds));
        JsonNode first = totals.path(FIRST_FIELD);
        if (first.isObject()) {
            JsonNode counts = first.path(AFTER_FIELD);
            text.append(String.format(FIRST_LINE, first.path(COMPONENT_FIELD).asText(),
                    first.path(PROFILE_FIELD).asText(), counts.path(TOTAL_FIELD).asInt(),
                    counts.path(FILES_FIELD).asInt()));
            text.append(rules(counts.path(BY_RULE_FIELD)));
        }
        totals.path(LAST_FIELD).properties().forEach(field -> text.append(String.format(LAST_LINE, field.getKey(),
                field.getValue().path(PROFILE_FIELD).asText(), field.getValue().path(TOTAL_FIELD).asInt(),
                field.getValue().path(FILES_FIELD).asInt())).append(rules(field.getValue().path(BY_RULE_FIELD))));
        return text.toString();
    }

    /**
     * How many rounds of a call ran fixers.
     *
     * @param rounds the call's rounds
     * @return that count
     */
    private static long countFixing(JsonNode rounds) {
        long fixing = NONE;
        for (JsonNode round : rounds) {
            fixing += round.path(FIXERS_RAN_FIELD).asBoolean() ? ONE : NONE;
        }
        return fixing;
    }

    /**
     * Errors rule by rule, one line each.
     *
     * @param byRule the counts by rule
     * @return those lines
     */
    private static String rules(JsonNode byRule) {
        StringBuilder text = new StringBuilder();
        byRule.properties().forEach(field -> text.append(String.format(RULES_LINE, field.getKey(),
                field.getValue().asInt())));
        return text.toString();
    }

    /**
     * Every call that ended, one line each: when, what, how long and what
     * its analyses found.
     *
     * @param report the session's report
     * @return those lines
     */
    private static String callList(JsonNode report) {
        StringBuilder text = new StringBuilder(String.format(CALL_LIST));
        if (report.path(CALLS_FIELD).isEmpty()) {
            text.append(String.format(NO_CALLS));
        }
        for (JsonNode call : report.path(CALLS_FIELD)) {
            StringBuilder found = new StringBuilder();
            for (JsonNode analysis : call.path(ANALYSES_FIELD)) {
                JsonNode before = analysis.path(BEFORE_FIELD);
                found.append(before.isObject()
                        ? String.format(REPAIR_CELL, before.path(TOTAL_FIELD).asInt(),
                                analysis.path(AFTER_FIELD).path(TOTAL_FIELD).asInt(), analysis.path(ROUNDS_FIELD).asInt())
                        : String.format(ANALYSIS_CELL, analysis.path(AFTER_FIELD).path(TOTAL_FIELD).asInt()));
            }
            String started = call.path(STARTED_FIELD).isTextual() ? time(Instant.parse(call.path(STARTED_FIELD).asText()))
                    : EMPTY;
            text.append(String.format(CALL_ROW, started, call.path(TOOL_FIELD).asText(),
                    call.path(ARGUMENTS_FIELD), duration(call.path(ELAPSED_FIELD).asLong()), found,
                    call.path(FAILED_FIELD).asBoolean() ? FAILED_CELL : EMPTY));
        }
        return text.toString();
    }

    /**
     * Every error of an analysis, one line each, file by file.
     *
     * @param analysis the analysis
     * @return those lines
     */
    private static String errors(AnalysisResult analysis) {
        StringBuilder text = new StringBuilder();
        analysis.errors().stream().sorted(Comparator.comparing(SheriffFinding::file))
                .forEach(error -> text.append(String.format(ERROR_LINE, shown(error.file()),
                        SheriffFinding.brief(error.description()), error.referenceCode())));
        return text.toString();
    }

    /**
     * A path Sheriff wrote, without the folder it mounts everything under.
     *
     * @param file the path
     * @return it, from the component's parent
     */
    private static String shown(String file) {
        return file.startsWith(MOUNT_PREFIX) ? file.substring(MOUNT_PREFIX.length()) : file;
    }

    /**
     * How a command ended, in words.
     *
     * @param outcome how it ended
     * @return its exit code, or why it did not end on its own
     */
    private static String ending(ProcessOutcome outcome) {
        return outcome.ran() ? String.format(EXIT_CODE, outcome.exitCode()) : outcome.failure();
    }

    /**
     * The end of what a command wrote, standard output then error.
     *
     * @param outcome how it ended
     * @return its last lines
     */
    private static List<String> tail(ProcessOutcome outcome) {
        List<String> lines = new ArrayList<>();
        for (String stream : List.of(outcome.standardOutput(), outcome.standardError(), outcome.failure())) {
            for (String line : stream.split(LINE_BREAK)) {
                if (!line.isBlank()) {
                    lines.add(line);
                }
            }
        }
        return lines.subList(Math.max(NONE, lines.size() - OUTPUT_LINES), lines.size());
    }

    /**
     * A moment as a time of day here.
     *
     * @param at the moment
     * @return it, to the second
     */
    private static String time(Instant at) {
        return LocalTime.ofInstant(at.truncatedTo(ChronoUnit.SECONDS), ZoneId.systemDefault()).format(TIME);
    }

    /**
     * A length of time as a person reads it.
     *
     * @param millis the time, in milliseconds
     * @return it, in milliseconds, seconds or minutes
     */
    private static String duration(long millis) {
        if (millis < MILLIS_PER_SECOND) {
            return String.format(MILLISECONDS, millis);
        }
        long seconds = millis / MILLIS_PER_SECOND;
        if (seconds < SECONDS_PER_MINUTE) {
            return String.format(Locale.ROOT, SECONDS, millis / MILLIS_PER_SECOND_DECIMAL);
        }
        return String.format(MINUTES, seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE);
    }
}
