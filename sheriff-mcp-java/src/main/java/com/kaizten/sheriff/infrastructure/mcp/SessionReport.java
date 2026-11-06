package com.kaizten.sheriff.infrastructure.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.infrastructure.process.ProcessObserver;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
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
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * One session of the MCP server as a single JSON document, rewritten whole
 * after every call, so sessions can be compared: one assistant against
 * another on the same project, and one project against another.
 *
 * <p>It holds the client that connected (the name and version its
 * {@code initialize} gave), this jar's version and Sheriff's image, the
 * project, and every call: its tool and arguments, how long it took, whether
 * it failed, and each analysis it made, rule by rule, before and after for a
 * repair. Counts, rule codes and the arguments only: a finding's description
 * is never kept, as Sheriff pastes code and build output into it. What the
 * client's model spent is not visible to the server, and is not guessed.
 *
 * <p>Written as the session goes rather than at the end, as a client may
 * stop the server without letting it end (Codex does): when a call starts,
 * after each analysis it makes and when it ends, so a repair that takes
 * minutes is seen round by round. A call not yet answered is under
 * {@code running}. Writing never fails a call: a report that cannot be
 * written is said once on standard error.
 *
 * <p>Whether it is written is asked at every write, not once at the start:
 * a session kept open while {@code SHERIFF_REPORT} was turned on wrote
 * nothing, and the user asked for the report of the work it had just done.
 * Turned on mid-session, the report holds every call since the session
 * started; turned off, it stops being written.
 *
 * <p>Each call also holds every round of a repair as it happens, with the
 * analysis that opened it rule by rule, and every command it ran, with how
 * it ended; a command still running is named under {@code command_running}. Beside the report, a text of the same name reads the session
 * from top to bottom ({@link SessionTranscript}): a summary, then all of it
 * in order, every error by its text included.
 */
public final class SessionReport implements ProcessObserver {

    private static final String SCHEMA_FIELD = "schema";
    private static final String KIND_FIELD = "kind";
    private static final String ID_FIELD = "id";
    private static final String STARTED_FIELD = "started";
    private static final String UPDATED_FIELD = "updated";
    private static final String CLIENT_FIELD = "client";
    private static final String NAME_FIELD = "name";
    private static final String VERSION_FIELD = "version";
    private static final String CALLS_FIELD = "calls";
    private static final String TOOL_FIELD = "tool";
    private static final String ARGUMENTS_FIELD = "arguments";
    private static final String ELAPSED_FIELD = "elapsed_ms";
    private static final String FAILED_FIELD = "failed";
    private static final String ANALYSES_FIELD = "analyses";
    private static final String RUNNING_FIELD = "running";
    private static final String COMMAND_RUNNING_FIELD = "command_running";
    private static final String COMPONENT_FIELD = "component";
    private static final String PROFILE_FIELD = "profile";
    private static final String BEFORE_FIELD = "before";
    private static final String AFTER_FIELD = "after";
    private static final String ROUNDS_FIELD = "rounds";
    private static final String TOTAL_FIELD = "total";
    private static final String FILES_FIELD = "files";
    private static final String BY_RULE_FIELD = "by_rule";
    private static final String TOTALS_FIELD = "totals";
    private static final String BY_TOOL_FIELD = "by_tool";
    private static final String FAILURES_FIELD = "failed_calls";
    private static final String CALLS_MS_FIELD = "calls_ms";
    private static final String FIRST_FIELD = "first_errors";
    private static final String LAST_FIELD = "last_errors";
    private static final String COMMANDS_FIELD = "commands";
    private static final String COMMAND_FIELD = "command";
    private static final String DIRECTORY_FIELD = "directory";
    private static final String STATUS_FIELD = "status";
    private static final String EXIT_CODE_FIELD = "exit_code";
    private static final String REPAIR_ROUNDS_FIELD = "repair_rounds";
    private static final String ROUND_FIELD = "round";
    private static final String FIXERS_RAN_FIELD = "fixers_ran";
    private static final String COMMAND_SEPARATOR = " ";
    private static final String JSON_SUFFIX = ".json";
    private static final String TEXT_SUFFIX = ".txt";
    private static final String NO_ANSWER = "";
    private static final String NOT_WRITTEN = "The session's report could not be written to %s: %s%n";
    private static final String PARTIAL_SUFFIX = ".partial";
    private static final int ID_LENGTH = 8;
    private static final int ID_START = 0;
    private static final int ONE = 1;
    private static final int NONE = 0;
    /**
     * The version of this document's shape, as {@code LoopReport}'s is kept.
     */
    public static final int SCHEMA = 1;
    /**
     * What a session's report says it is, beside a loop's.
     */
    public static final String KIND = "session";
    private final String name;
    private final Supplier<Optional<Path>> folder;
    private final Map<String, Object> context;
    private final Instant started = Instant.now();
    private final String id = UUID.randomUUID().toString().substring(ID_START, ID_LENGTH);
    private final ObjectMapper json = new ObjectMapper();
    private final ArrayNode calls = json.createArrayNode();
    private final List<ObjectNode> analyses = new ArrayList<>();
    private final Map<Thread, ObjectNode> running = new HashMap<>();
    private final Map<Thread, Instant> runningSince = new HashMap<>();
    private final Map<String, Integer> byTool = new TreeMap<>();
    private final Map<String, ObjectNode> lastByComponent = new TreeMap<>();
    private ObjectNode first;
    private String clientName;
    private String clientVersion;
    private int failures;
    private long callsMillis;
    private final Map<Thread, Integer> numbers = new HashMap<>();
    private final Map<Thread, Integer> commandPlaces = new HashMap<>();
    private final SessionTranscript transcript = new SessionTranscript();
    private int opened;
    private boolean warned;

    /**
     * A report of the session that starts now, always written to one file.
     *
     * @param file where it is written
     * @param context the sections that do not change during the session,
     *     each a map: the tools, Sheriff's image, the project
     */
    public SessionReport(Path file, Map<String, Object> context) {
        this(file.getFileName().toString(), () -> Optional.of(file.toAbsolutePath().getParent()), context);
    }

    /**
     * A report of the session that starts now, written while its folder is
     * given.
     *
     * @param name the file's name in that folder
     * @param folder where reports go, asked at every write: empty while
     *     reports are off
     * @param context the sections that do not change during the session,
     *     each a map: the tools, Sheriff's image, the project
     */
    public SessionReport(String name, Supplier<Optional<Path>> folder, Map<String, Object> context) {
        this.name = name;
        this.folder = folder;
        this.context = Map.copyOf(context);
    }

    /**
     * Where the report is written now.
     *
     * @return that file, or empty while reports are off
     */
    public Optional<Path> file() {
        return folder.get().map(reports -> reports.resolve(name));
    }

    /**
     * Where the text of the session is written now, beside the report.
     *
     * @return that file, or empty while reports are off
     */
    public Optional<Path> textFile() {
        String text = name.endsWith(JSON_SUFFIX)
                ? name.substring(ID_START, name.length() - JSON_SUFFIX.length()) + TEXT_SUFFIX : name + TEXT_SUFFIX;
        return folder.get().map(reports -> reports.resolve(text));
    }

    /**
     * Keeps the client that connected, as its {@code initialize} named it.
     *
     * @param name its name, such as {@code claude-code}
     * @param version its version, empty when it gave none
     */
    public synchronized void client(String name, String version) {
        clientName = name;
        clientVersion = version;
        write();
    }

    /**
     * Keeps one analysis the call in progress made: a check, or a repair
     * with the analysis before it.
     *
     * @param component the component analyzed
     * @param profile the profile it was analyzed under
     * @param before what a repair started from, {@code null} for a check
     * @param after what the analysis found, or the repair left
     * @param rounds how many rounds of fixers a repair ran, 0 for a check
     */
    public synchronized void analyzed(String component, String profile, AnalysisResult before, AnalysisResult after,
            int rounds) {
        ObjectNode analysis = json.createObjectNode();
        analysis.put(COMPONENT_FIELD, component);
        analysis.put(PROFILE_FIELD, profile);
        analysis.set(BEFORE_FIELD, before == null ? json.nullNode() : counts(before));
        ObjectNode left = counts(after);
        analysis.set(AFTER_FIELD, left);
        analysis.put(ROUNDS_FIELD, rounds);
        if (first == null) {
            first = analysis.deepCopy();
            first.set(AFTER_FIELD, before == null ? left : counts(before));
            first.remove(BEFORE_FIELD);
            first.remove(ROUNDS_FIELD);
        }
        ObjectNode last = json.createObjectNode();
        last.put(PROFILE_FIELD, profile);
        last.setAll(left);
        lastByComponent.put(component, last);
        ObjectNode call = running.get(Thread.currentThread());
        if (call == null) {
            analyses.add(analysis);
        } else {
            ((ArrayNode) call.get(ANALYSES_FIELD)).add(analysis);
        }
        if (before == null) {
            transcript.analysis(callNumber(), Instant.now(), component, profile, after);
        } else {
            transcript.repair(callNumber(), Instant.now(), component, profile, before, after, rounds);
        }
        write();
    }

    /**
     * Keeps one round of a repair as it happens: the analysis that opened
     * it, profile by profile, and whether fixers ran on what it found.
     *
     * @param component the component repaired
     * @param profile the profile analyzed
     * @param round the round, from 1
     * @param analysis what the analysis found
     * @param fixersRan whether Sheriff's fixers ran after it
     */
    public synchronized void round(String component, String profile, int round, AnalysisResult analysis,
            boolean fixersRan) {
        ObjectNode entry = json.createObjectNode();
        entry.put(COMPONENT_FIELD, component);
        entry.put(PROFILE_FIELD, profile);
        entry.put(ROUND_FIELD, round);
        entry.setAll(counts(analysis));
        entry.put(FIXERS_RAN_FIELD, fixersRan);
        ObjectNode call = running.get(Thread.currentThread());
        if (call != null) {
            ((ArrayNode) call.get(REPAIR_ROUNDS_FIELD)).add(entry);
        }
        transcript.round(callNumber(), Instant.now(), component, profile, round, analysis, fixersRan);
        write();
    }

    /**
     * Keeps one command a call is starting, named as running until it ends,
     * so a long one is seen while it runs. A command run outside any call is
     * not kept.
     *
     * @param command the command
     * @param directory where it runs, {@code null} for the server's own
     */
    @Override
    public synchronized void starting(List<String> command, Path directory) {
        ObjectNode call = running.get(Thread.currentThread());
        if (call == null) {
            return;
        }
        call.put(COMMAND_RUNNING_FIELD, String.join(COMMAND_SEPARATOR, command));
        commandPlaces.put(Thread.currentThread(), transcript.commandStarted(callNumber(), Instant.now(), command,
                directory));
        write();
    }

    /**
     * Keeps one command a call ran, once it ended, in the place it was kept
     * when it started. A command run outside any call, such as the image's
     * pull in the background, is not kept.
     *
     * @param command the command
     * @param directory where it ran, {@code null} for the server's own
     * @param outcome how it ended
     * @param took how long it ran
     */
    @Override
    public synchronized void ran(List<String> command, Path directory, ProcessOutcome outcome, Duration took) {
        ObjectNode call = running.get(Thread.currentThread());
        Integer place = commandPlaces.remove(Thread.currentThread());
        if (call == null) {
            return;
        }
        call.remove(COMMAND_RUNNING_FIELD);
        ObjectNode entry = ((ArrayNode) call.get(COMMANDS_FIELD)).addObject();
        entry.put(COMMAND_FIELD, String.join(COMMAND_SEPARATOR, command));
        entry.set(DIRECTORY_FIELD, directory == null ? json.nullNode() : json.valueToTree(directory.toString()));
        entry.put(STATUS_FIELD, outcome.status().name());
        entry.put(EXIT_CODE_FIELD, outcome.exitCode());
        entry.put(ELAPSED_FIELD, took.toMillis());
        Instant now = Instant.now();
        if (place == null) {
            transcript.command(callNumber(), now, command, directory, outcome, took);
        } else {
            transcript.commandEnded(place, callNumber(), now.minus(took), command, directory, outcome, took);
        }
        write();
    }

    /**
     * The number of the call this thread is making.
     *
     * @return it, from 1, or 0 outside any call
     */
    private int callNumber() {
        return numbers.getOrDefault(Thread.currentThread(), NONE);
    }

    /**
     * Opens one call, written under {@code running} until it ends.
     *
     * @param tool the tool called
     * @param arguments what it was called with
     */
    public synchronized void started(String tool, Map<String, Object> arguments) {
        Instant now = Instant.now();
        ObjectNode call = json.createObjectNode();
        call.put(TOOL_FIELD, tool);
        call.set(ARGUMENTS_FIELD, json.valueToTree(new TreeMap<>(arguments)));
        call.put(STARTED_FIELD, now.toString());
        call.putArray(ANALYSES_FIELD);
        call.putArray(REPAIR_ROUNDS_FIELD);
        call.putArray(COMMANDS_FIELD);
        running.put(Thread.currentThread(), call);
        runningSince.put(Thread.currentThread(), now);
        opened++;
        numbers.put(Thread.currentThread(), opened);
        transcript.callStarted(opened, now, tool, arguments);
        write();
    }

    /**
     * Closes one call, with the analyses it made, and writes the report.
     *
     * @param tool the tool called
     * @param arguments what it was called with
     * @param took how long it took
     * @param failed whether it answered with a failure
     */
    public synchronized void called(String tool, Map<String, Object> arguments, Duration took, boolean failed) {
        called(tool, arguments, took, failed, NO_ANSWER);
    }

    /**
     * Closes one call, with the analyses, rounds and commands it made and
     * the answer it gave, and writes the report.
     *
     * @param tool the tool called
     * @param arguments what it was called with
     * @param took how long it took
     * @param failed whether it answered with a failure
     * @param answer the text the model was given
     */
    public synchronized void called(String tool, Map<String, Object> arguments, Duration took, boolean failed,
            String answer) {
        int number = callNumber();
        ObjectNode open = running.remove(Thread.currentThread());
        runningSince.remove(Thread.currentThread());
        numbers.remove(Thread.currentThread());
        ObjectNode call = calls.addObject();
        call.put(TOOL_FIELD, tool);
        call.set(ARGUMENTS_FIELD, json.valueToTree(new TreeMap<>(arguments)));
        call.set(STARTED_FIELD, open == null ? json.nullNode() : open.get(STARTED_FIELD));
        call.put(ELAPSED_FIELD, took.toMillis());
        call.put(FAILED_FIELD, failed);
        ArrayNode made = open == null ? json.createArrayNode() : (ArrayNode) open.get(ANALYSES_FIELD);
        analyses.forEach(made::add);
        call.set(ANALYSES_FIELD, made);
        call.set(REPAIR_ROUNDS_FIELD, open == null ? json.createArrayNode() : open.get(REPAIR_ROUNDS_FIELD));
        call.set(COMMANDS_FIELD, open == null ? json.createArrayNode() : open.get(COMMANDS_FIELD));
        analyses.clear();
        transcript.callEnded(number, Instant.now(), tool, took, failed, answer);
        byTool.merge(tool, ONE, Integer::sum);
        failures += failed ? ONE : NONE;
        callsMillis += took.toMillis();
        write();
    }

    /**
     * The whole report, as it stands.
     *
     * @return the document
     */
    synchronized ObjectNode document() {
        ObjectNode root = json.createObjectNode();
        root.put(SCHEMA_FIELD, SCHEMA);
        root.put(KIND_FIELD, KIND);
        root.put(ID_FIELD, id);
        root.put(STARTED_FIELD, started.toString());
        root.put(UPDATED_FIELD, Instant.now().toString());
        ObjectNode client = root.putObject(CLIENT_FIELD);
        client.set(NAME_FIELD, json.valueToTree(clientName));
        client.set(VERSION_FIELD, json.valueToTree(clientVersion));
        new TreeMap<>(context).forEach((section, values) -> root.set(section, json.valueToTree(values)));
        ObjectNode totals = root.putObject(TOTALS_FIELD);
        totals.put(CALLS_FIELD, calls.size());
        totals.set(BY_TOOL_FIELD, json.valueToTree(byTool));
        totals.put(FAILURES_FIELD, failures);
        totals.put(CALLS_MS_FIELD, callsMillis);
        totals.set(FIRST_FIELD, first == null ? json.nullNode() : first);
        totals.set(LAST_FIELD, json.valueToTree(lastByComponent));
        root.set(CALLS_FIELD, calls.deepCopy());
        ArrayNode open = root.putArray(RUNNING_FIELD);
        Instant now = Instant.now();
        running.forEach((thread, call) -> open.add(call.deepCopy()
                .put(ELAPSED_FIELD, Duration.between(runningSince.get(thread), now).toMillis())));
        return root;
    }

    /**
     * The errors of one analysis: how many, in how many files, rule by rule.
     *
     * @param analysis the analysis
     * @return those counts
     */
    private ObjectNode counts(AnalysisResult analysis) {
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
     * Writes the report and its text whole, each to a file beside it, then
     * moved into place.
     */
    private void write() {
        Optional<Path> target = file();
        Optional<Path> text = textFile();
        if (target.isEmpty() || text.isEmpty()) {
            return;
        }
        Path file = target.get();
        try {
            ObjectNode report = document();
            writeWhole(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
            writeWhole(text.get(), transcript.render(report));
        } catch (IOException exception) {
            if (!warned) {
                warned = true;
                System.err.printf(NOT_WRITTEN, file, exception.getMessage());
            }
        }
    }

    /**
     * Writes one file whole: to a file beside it, then moved into place, so
     * a reader never sees half of it.
     *
     * @param file the file
     * @param content what it holds
     * @throws IOException when it cannot be written
     */
    private static void writeWhole(Path file, String content) throws IOException {
        Path partial = file.resolveSibling(file.getFileName() + PARTIAL_SUFFIX);
        Files.createDirectories(file.toAbsolutePath().getParent());
        Files.writeString(partial, content, StandardCharsets.UTF_8);
        try {
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
