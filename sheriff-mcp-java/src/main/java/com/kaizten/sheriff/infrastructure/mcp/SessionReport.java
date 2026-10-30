package com.kaizten.sheriff.infrastructure.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

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
 * <p>Written after each call rather than at the end, as a client may stop the
 * server without letting it end (Codex does). Writing never fails a call: a
 * report that cannot be written is said once on standard error.
 */
public final class SessionReport {

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
    private final Path file;
    private final Map<String, Object> context;
    private final Instant started = Instant.now();
    private final String id = UUID.randomUUID().toString().substring(ID_START, ID_LENGTH);
    private final ObjectMapper json = new ObjectMapper();
    private final ArrayNode calls = json.createArrayNode();
    private final List<ObjectNode> analyses = new ArrayList<>();
    private final Map<String, Integer> byTool = new TreeMap<>();
    private final Map<String, ObjectNode> lastByComponent = new TreeMap<>();
    private ObjectNode first;
    private String clientName;
    private String clientVersion;
    private int failures;
    private long callsMillis;
    private boolean warned;

    /**
     * A report of the session that starts now.
     *
     * @param file where it is written
     * @param context the sections that do not change during the session,
     *     each a map: the tools, Sheriff's image, the project
     */
    public SessionReport(Path file, Map<String, Object> context) {
        this.file = file;
        this.context = Map.copyOf(context);
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
        analyses.add(analysis);
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
        ObjectNode call = calls.addObject();
        call.put(TOOL_FIELD, tool);
        call.set(ARGUMENTS_FIELD, json.valueToTree(new TreeMap<>(arguments)));
        call.put(ELAPSED_FIELD, took.toMillis());
        call.put(FAILED_FIELD, failed);
        call.set(ANALYSES_FIELD, json.valueToTree(analyses));
        analyses.clear();
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
     * Writes the report whole: to a file beside it, then moved into place.
     */
    private void write() {
        Path partial = file.resolveSibling(file.getFileName() + PARTIAL_SUFFIX);
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.writeString(partial, json.writerWithDefaultPrettyPrinter().writeValueAsString(document()),
                    StandardCharsets.UTF_8);
            try {
                Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            if (!warned) {
                warned = true;
                System.err.printf(NOT_WRITTEN, file, exception.getMessage());
            }
        }
    }
}
