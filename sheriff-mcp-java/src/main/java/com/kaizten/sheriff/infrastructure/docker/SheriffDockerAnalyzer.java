package com.kaizten.sheriff.infrastructure.docker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaizten.sheriff.domain.Rules;
import com.kaizten.sheriff.domain.port.CodeAnalyzer;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.domain.valueobject.TrackedFile;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The real analyzer: {@code kaizten/sheriff} in an ephemeral container, with
 * the analyzed repository mounted as a volume.
 *
 * <pre>
 * docker run --rm --name sheriff-agent-&lt;uuid&gt; -v &lt;repository&gt;:/data kaizten/sheriff:latest \
 *     test --test JAVA --uri file:/data --component &lt;component&gt;
 * </pre>
 *
 * <p>Three things about Sheriff's real interface are encoded here, none of
 * them documented anywhere and all of them learned by running it:
 *
 * <ul>
 *   <li>The exit code is always zero once the analysis runs, whatever it
 *       found. A non-zero code means the invocation itself failed, with the
 *       reason on stderr as plain text. Reading the exit code as "clean" is
 *       the single most dangerous mistake available here.</li>
 *   <li>With no issues, stdout is completely empty. Otherwise it is a short
 *       human-readable header followed by a bare JSON array — not an object —
 *       whose entries carry absolute container paths under {@code /data}.
 *       The same entries are in {@code sheriff_errors.json}, under the URI,
 *       the component and the profile of the run, with none of the noise
 *       around them, so that file is where findings are read from. Stdout is
 *       only the fallback for a file that cannot be read.</li>
 *   <li>Every run drops three state files at the root of the mounted volume.
 *       Left there they appear as untracked files in the analyzed repository,
 *       which makes the loop's own scope check read them as "the fixer touched
 *       files nobody flagged" and refuse to continue. They are deleted after
 *       every run. They are cleared before the next run too: a stale tracked
 *       file hash can make Sheriff skip analysis and leave an old verdict.
 *       Two of them are worth keeping, and are read first and optionally
 *       copied out: the findings, and {@code sheriff_tracked_files.json}, the
 *       path and SHA-256 of every file analyzed. The summary has been an
 *       empty object in every run seen, and is only deleted.</li>
 *   <li>A profile Sheriff does not know exits zero too, with a usage banner
 *       on stdout and the reason on stderr. Looking for the first bracket
 *       would parse the banner's {@code [options]}, so the banner is
 *       recognised and the reason reported in Sheriff's own words.</li>
 *   <li>Zero errors is also what a run that analyzed nothing looks like: an
 *       empty stdout and exit zero. Sheriff's own findings file records which
 *       component and profile it analyzed, and it is read before the clean-up
 *       so that an empty result it does not vouch for is reported as a failure
 *       rather than as a clean component. One such run once answered "0
 *       errors" for a component that had 221.</li>
 * </ul>
 *
 * <p>This is the only place any of that is decided. The MCP server's
 * {@code sheriff_test} and {@code sheriff_fix} go through it too, so the CLI,
 * the hooks, the Maven plugin and the MCP tools cannot disagree about what a
 * run meant.
 */
public final class SheriffDockerAnalyzer implements CodeAnalyzer {

    private static final String ERRORS_STATE_FILE = "sheriff_errors.json";
    private static final String SUMMARY_STATE_FILE = "sheriff_summary.json";
    private static final String TRACKED_FILES_STATE_FILE = "sheriff_tracked_files.json";

    /**
     * The state Sheriff writes at the root of the mounted volume.
     */
    public static final List<String> STATE_FILES =
            List.of(ERRORS_STATE_FILE, SUMMARY_STATE_FILE, TRACKED_FILES_STATE_FILE);

    /**
     * The state worth keeping: the findings and the tracked files. The
     * summary has been empty in every run seen.
     */
    public static final List<String> EXPORTED_STATE_FILES = List.of(ERRORS_STATE_FILE, TRACKED_FILES_STATE_FILE);

    private static final String TEST_SUBCOMMAND = "test";
    private static final String TEST_FLAG = "--test";
    private static final String URI_FLAG = "--uri";
    private static final String COMPONENT_FLAG = "--component";
    private static final String FAIL_FAST_FLAG = "--fail-fast";
    private static final int NOT_FOUND = -1;
    private static final int ONLY_ONE = 1;
    private static final String CONTAINER_MOUNT = "/data";
    private static final String CONTAINER_PATH_PREFIX = CONTAINER_MOUNT + "/";
    private static final String URI_ARGUMENT = "file:" + CONTAINER_MOUNT;
    private static final String PARSE_FAILURE = "Could not parse Sheriff's output.";
    private static final String EXIT_FAILURE = "Sheriff exited with code %d.";
    private static final String ARRAY_START = "[";
    private static final String ERRORS_FIELD = "errors";
    private static final String FILE_FIELD = "file";
    private static final String DESCRIPTION_FIELD = "description";
    private static final String HOW_TO_SOLVE_FIELD = "howToSolve";
    private static final String REFERENCE_CODE_FIELD = "referenceCode";
    private static final String TYPE_FIELD = "type";
    private static final String HASH_FIELD = "hash";
    private static final String USAGE_BANNER = "Usage: Sheriff";
    private static final String COMMAND_TRACE = "COMMAND:";
    private static final String NEWLINE = "\n";
    private static final String LINE_SEPARATOR_PATTERN = "\\R";
    private static final String NO_REASON = "";
    private static final String REJECTED_ARGUMENTS = "Sheriff rejected the arguments for component '%s' under '%s'.";
    private static final String NOT_RECORDED =
            "Sheriff produced no report for '%s' under '%s' and did not record the run in its own "
            + "state file, so nothing was analyzed. That is not the same as a clean component, and "
            + "Sheriff gives no other way to tell the two apart: it exits 0 either way and says "
            + "nothing on an empty result.";

    private final SheriffContainer container;
    private final ObjectMapper json = new ObjectMapper();
    private final String testType;
    private final String component;
    private final Path repositoryRoot;
    private final boolean cleanUpState;
    private final Optional<Path> exportDirectory;
    private final boolean failFast;

    /**
     * Wires the analyzer to one Sheriff image and one component.
     *
     * @param processes how to run Docker
     * @param image the Sheriff image to run
     * @param testType the {@code --test} profile
     * @param component the folder to analyze, relative to the repository root
     * @param repositoryRoot the repository to mount
     * @param timeout how long Sheriff is given before being killed
     */
    public SheriffDockerAnalyzer(
            ProcessRunner processes,
            String image,
            String testType,
            String component,
            Path repositoryRoot,
            Duration timeout) {
        this(processes, image, testType, component, repositoryRoot, timeout, true);
    }

    /**
     * The same, saying explicitly whether to clean up after the run.
     *
     * <p>There is exactly one reason to keep Sheriff's state: its own
     * {@code fix} subcommand reads the findings file that an analysis writes,
     * and with no such file it repairs nothing at all. So the deterministic
     * pass analyzes without cleaning, fixes, and cleans up afterwards. Every
     * other caller wants the default, because state left behind makes a later
     * analysis report a component clean when it is not.
     *
     * @param processes how to run Docker
     * @param image the Sheriff image to run
     * @param testType the {@code --test} profile
     * @param component the folder to analyze
     * @param repositoryRoot the repository to mount
     * @param timeout how long Sheriff is given
     * @param cleanUpState whether to delete Sheriff's state after the run
     */
    public SheriffDockerAnalyzer(
            ProcessRunner processes,
            String image,
            String testType,
            String component,
            Path repositoryRoot,
            Duration timeout,
            boolean cleanUpState) {
        this(new SheriffContainer(processes, image, repositoryRoot, timeout),
                testType, component, repositoryRoot, cleanUpState, Optional.empty(), false);
    }

    /**
     * Every field, for the constructors above and for {@link #exportingTo}.
     *
     * @param container what runs Sheriff
     * @param testType the {@code --test} profile
     * @param component the folder to analyze
     * @param repositoryRoot the repository to mount
     * @param cleanUpState whether to delete Sheriff's state after the run
     * @param exportDirectory where to copy the findings and tracked files, if
     *     anywhere
     * @param failFast whether Sheriff stops at the first error it finds
     */
    private SheriffDockerAnalyzer(
            SheriffContainer container,
            String testType,
            String component,
            Path repositoryRoot,
            boolean cleanUpState,
            Optional<Path> exportDirectory,
            boolean failFast) {
        this.container = container;
        this.testType = testType;
        this.component = component;
        this.repositoryRoot = repositoryRoot;
        this.cleanUpState = cleanUpState;
        this.exportDirectory = exportDirectory;
        this.failFast = failFast;
    }

    /**
     * The same analyzer, copying Sheriff's findings and tracked files to a
     * directory after every run, before they are cleaned up.
     *
     * <p>The copies are overwritten by each run, so the directory always holds
     * the last one. They go outside the mount because anything left inside it
     * reads as an untracked change to the scope check, and a stale tracked
     * file there makes Sheriff skip its next analysis.
     *
     * @param directory where to copy them, created when missing
     * @return that analyzer
     */
    public SheriffDockerAnalyzer exportingTo(Path directory) {
        return new SheriffDockerAnalyzer(
                container, testType, component, repositoryRoot, cleanUpState, Optional.of(directory), failFast);
    }

    /**
     * The same analyzer, with Sheriff stopping at the first error it finds.
     *
     * <p>For a caller that only needs to know whether a component complies.
     * The result then holds at most one finding, so it must never feed a
     * repair, which needs all of them.
     *
     * @return that analyzer
     */
    public SheriffDockerAnalyzer failingFast() {
        return new SheriffDockerAnalyzer(
                container, testType, component, repositoryRoot, cleanUpState, exportDirectory, true);
    }

    /**
     * Runs Sheriff once over the configured component and reads its verdict.
     *
     * <p>The exit code is never read as "clean": a successful run reports its
     * findings on stdout whatever their number, so only a non-zero code, an
     * unrunnable Docker, a rejected profile, a run Sheriff did not record or
     * unparseable output count as a failure. The verdict is read before the
     * clean-up, because Sheriff's own record of the run is one of the files
     * the clean-up deletes.
     *
     * @return the findings, or the reason the analysis could not be trusted
     */
    @Override
    public AnalysisResult analyze() {
        return MountLock.holding(repositoryRoot, this::analyzeLocked);
    }

    /**
     * The run, the verdict and the clean-up, with the mount's lock held
     * across all three: another run in between would read or delete the
     * state this one's verdict depends on.
     *
     * <p>The state is cleared before the run as well as after it: a stale
     * tracked-file hash can make Sheriff skip this run entirely. Several
     * profiles are one run each, all under the same lock, merged into one
     * result; the first that fails is the result.
     *
     * @return the findings, or the reason the analysis could not be trusted
     */
    private AnalysisResult analyzeLocked() {
        List<SheriffDockerAnalyzer> analyzers = perProfile();
        if (analyzers.size() == ONLY_ONE) {
            return analyzeOnce();
        }
        List<AnalysisResult> results = new ArrayList<>();
        for (SheriffDockerAnalyzer single : analyzers) {
            AnalysisResult result = single.analyzeOnce();
            if (result.error()) {
                return result;
            }
            results.add(result);
        }
        return AnalysisResult.merged(results);
    }

    /**
     * One analyzer per profile this one runs, since Sheriff runs a single
     * profile at a time: this one itself when it runs only one.
     *
     * <p>A repair has to go through these one by one: {@code fix} reads the
     * state the {@code test} before it left, and after a run of several
     * profiles that state is only the last one's. Each exports into a folder
     * of its own, named after its profile, so that none overwrites another.
     *
     * @return the analyzers, in the order the profiles are listed
     */
    public List<SheriffDockerAnalyzer> perProfile() {
        List<String> profiles = Rules.profilesIn(testType);
        if (profiles.size() == ONLY_ONE) {
            return List.of(this);
        }
        return profiles.stream().map(profile -> new SheriffDockerAnalyzer(container, profile, component,
                repositoryRoot, cleanUpState, exportDirectory.map(directory -> directory.resolve(profile)), failFast)).toList();
    }

    /**
     * One run of Sheriff under a single profile, with its state cleared
     * before and, unless it is being kept for a repair, after.
     *
     * @return what that run found
     */
    private AnalysisResult analyzeOnce() {
        removeStateFiles();
        ProcessOutcome outcome = container.run(arguments());
        try {
            AnalysisResult result = verdictOf(outcome);
            exportDirectory.ifPresent(this::export);
            return result;
        } finally {
            if (cleanUpState) {
                removeStateFiles();
            }
        }
    }

    /**
     * What one run means.
     *
     * @param outcome what running Docker produced
     * @return the findings, or why they cannot be trusted
     */
    private AnalysisResult verdictOf(ProcessOutcome outcome) {
        if (!outcome.ran()) {
            return AnalysisResult.failure(outcome.failure());
        }
        if (!outcome.succeeded()) {
            return AnalysisResult.failure(invocationFailure(outcome));
        }
        String output = withoutTraces(outcome.standardOutput());
        if (output.stripLeading().startsWith(USAGE_BANNER)) {
            String reason = reasonOf(outcome.standardError());
            return AnalysisResult.failure(
                    reason.isEmpty() ? String.format(REJECTED_ARGUMENTS, component, testType) : reason);
        }
        List<TrackedFile> tracked = trackedFiles();
        Optional<JsonNode> recorded = recordedEntries();
        if (recorded.isPresent()) {
            return AnalysisResult.of(KnownFalsePositives.without(toFindings(recorded.get())), tracked);
        }
        if (output.isBlank()) {
            return AnalysisResult.failure(String.format(NOT_RECORDED, component, testType));
        }
        try {
            return AnalysisResult.of(KnownFalsePositives.without(parse(output)), tracked);
        } catch (IOException exception) {
            return AnalysisResult.failure(PARSE_FAILURE);
        }
    }

    /**
     * Sheriff's output without the commands it traces.
     *
     * <p>The TypeScript profile prints one {@code COMMAND: node ...} line per
     * checker it runs, before any findings. A clean run is those lines and
     * nothing else, which read as output that was not JSON and failed every
     * clean TypeScript component; they carry nothing a verdict needs.
     *
     * @param output what Sheriff printed on stdout
     * @return the same without those lines
     */
    private static String withoutTraces(String output) {
        return output.lines()
                .filter(line -> !line.startsWith(COMMAND_TRACE))
                .collect(Collectors.joining(NEWLINE));
    }

    /**
     * What this analyzer passes to Sheriff.
     *
     * @return the {@code test} subcommand and its flags
     */
    List<String> arguments() {
        List<String> arguments = new ArrayList<>(List.of(
                TEST_SUBCOMMAND,
                TEST_FLAG, testType,
                URI_FLAG, URI_ARGUMENT,
                COMPONENT_FLAG, component));
        if (failFast) {
            arguments.add(FAIL_FAST_FLAG);
        }
        return arguments;
    }

    /**
     * The findings Sheriff's own file records for this component under this
     * profile.
     *
     * @return the array of entries, or empty when there is no readable file
     *     or it does not record this run, which leaves stdout to decide
     */
    private Optional<JsonNode> recordedEntries() {
        Optional<JsonNode> root = stateFile(ERRORS_STATE_FILE);
        if (root.isEmpty()) {
            return Optional.empty();
        }
        Iterator<JsonNode> contexts = root.get().elements();
        while (contexts.hasNext()) {
            JsonNode entries = contexts.next().path(component).path(testType);
            if (entries.isArray()) {
                return Optional.of(entries);
            }
        }
        return Optional.empty();
    }

    /**
     * Every file Sheriff's tracked-files record lists, with its hash.
     *
     * @return those files, repository-relative, empty when there is no
     *     readable record
     */
    private List<TrackedFile> trackedFiles() {
        Optional<JsonNode> root = stateFile(TRACKED_FILES_STATE_FILE);
        List<TrackedFile> files = new ArrayList<>();
        if (root.isEmpty()) {
            return files;
        }
        Iterator<Map.Entry<String, JsonNode>> entries = root.get().fields();
        while (entries.hasNext()) {
            Map.Entry<String, JsonNode> entry = entries.next();
            files.add(new TrackedFile(normalize(entry.getKey()), entry.getValue().path(HASH_FIELD).asText()));
        }
        return files;
    }

    /**
     * One of Sheriff's state files, parsed.
     *
     * @param name the file, at the root of the mount
     * @return its JSON object, or empty when it is missing, unreadable or not
     *     an object
     */
    private Optional<JsonNode> stateFile(String name) {
        JsonNode root;
        try {
            root = json.readTree(Files.readString(repositoryRoot.resolve(name)));
        } catch (IOException exception) {
            return Optional.empty();
        }
        if (root == null || !root.isObject()) {
            return Optional.empty();
        }
        return Optional.of(root);
    }

    /**
     * Copies the findings and tracked files out of the mount.
     *
     * <p>Best effort, like the clean-up: an export that cannot be written is
     * not a reason to fail an analysis that succeeded.
     *
     * @param directory where to copy them
     */
    private void export(Path directory) {
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            return;
        }
        for (String name : EXPORTED_STATE_FILES) {
            Path source = repositoryRoot.resolve(name);
            try {
                if (Files.isRegularFile(source)) {
                    Files.copy(source, directory.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.deleteIfExists(directory.resolve(name));
                }
            } catch (IOException exception) {
                continue;
            }
        }
    }

    /**
     * Sheriff's own reason for rejecting its arguments: the first line of
     * stderr that is not the usage banner repeated.
     *
     * @param standardError what Sheriff printed on stderr
     * @return that line, or the empty string when there is none
     */
    private static String reasonOf(String standardError) {
        for (String line : standardError.split(LINE_SEPARATOR_PATTERN)) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty() && !trimmed.startsWith(USAGE_BANNER)) {
                return trimmed;
            }
        }
        return NO_REASON;
    }

    /**
     * Turns Sheriff's stdout into findings.
     *
     * <p>Empty output means no issues. Otherwise the JSON starts at the first
     * bracket, after a header meant for humans.
     *
     * @param standardOutput what Sheriff printed
     * @return the findings it reported
     * @throws IOException when what follows the bracket is not JSON
     */
    List<SheriffFinding> parse(String standardOutput) throws IOException {
        String output = standardOutput.strip();
        if (output.isEmpty()) {
            return List.of();
        }
        int start = output.indexOf(ARRAY_START);
        if (start == NOT_FOUND) {
            throw new IOException(PARSE_FAILURE);
        }
        JsonNode parsed = json.readTree(output.substring(start));
        return toFindings(parsed.isArray() ? parsed : parsed.path(ERRORS_FIELD));
    }

    /**
     * Sheriff's entries as findings.
     *
     * @param entries the JSON array of entries
     * @return one finding per entry, in order
     */
    private List<SheriffFinding> toFindings(JsonNode entries) {
        List<SheriffFinding> findings = new ArrayList<>();
        for (JsonNode entry : entries) {
            findings.add(toFinding(entry));
        }
        return findings;
    }

    /**
     * One JSON entry as a finding, keeping the untouched fields alongside the
     * ones the domain reads by name.
     *
     * @param entry the entry Sheriff reported
     * @return the finding, with its path relative to the repository root
     */
    private SheriffFinding toFinding(JsonNode entry) {
        Map<String, Object> raw = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = entry.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            raw.put(field.getKey(), plain(field.getValue(), field.getKey()));
        }
        return new SheriffFinding(
                normalize(entry.path(FILE_FIELD).asText()),
                entry.path(DESCRIPTION_FIELD).asText(),
                entry.path(HOW_TO_SOLVE_FIELD).asText(),
                entry.path(REFERENCE_CODE_FIELD).asText(),
                entry.path(TYPE_FIELD).asText(),
                raw);
    }

    /**
     * One raw field, keeping the type Sheriff sent and normalizing the path.
     *
     * <p>Both halves matter for {@code --json-report}: a consumer reading the
     * report gets the same repository-relative paths the console shows, and a
     * numeric code stays a number instead of becoming a quoted string. Getting
     * either wrong makes two implementations of this tool disagree about their
     * own output, which is how a pipeline breaks on an upgrade.
     *
     * @param value the field as Sheriff sent it
     * @param name the field's name
     * @return the value to keep
     */
    private static Object plain(JsonNode value, String name) {
        if (FILE_FIELD.equals(name)) {
            return normalize(value.asText());
        }
        if (value.isNumber()) {
            return value.numberValue();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return value.asText();
    }

    /**
     * Strips the container mount prefix so a finding's path lines up with what
     * version control reports as modified.
     *
     * @param path the path Sheriff reported
     * @return the same path relative to the repository root
     */
    static String normalize(String path) {
        return path.startsWith(CONTAINER_PATH_PREFIX) ? path.substring(CONTAINER_PATH_PREFIX.length()) : path;
    }

    /**
     * Why an invocation that returned a non-zero code failed, preferring
     * Sheriff's own plain-text reason on stderr over the bare exit code.
     *
     * @param outcome what running Docker produced
     * @return the message to report
     */
    private String invocationFailure(ProcessOutcome outcome) {
        String reason = outcome.standardError().strip();
        return reason.isEmpty() ? String.format(EXIT_FAILURE, outcome.exitCode()) : reason;
    }

    /**
     * Removes the state Sheriff leaves in the analyzed repository.
     *
     * <p>Public because the deterministic pass has to do this itself: it asks
     * for an analyzer that keeps the state, uses it, and is then responsible
     * for the clean-up nobody else did.
     *
     * <p>Best effort: the files are written by the container as root, so being
     * unable to delete them is not a reason to fail an analysis that just
     * succeeded.
     */
    public void removeStateFiles() {
        removeStateFilesIn(repositoryRoot);
    }

    /**
     * Removes the state Sheriff leaves at the root of a mounted directory,
     * for a caller that ran Sheriff without an analyzer of its own to ask.
     *
     * @param repositoryRoot the directory Sheriff mounted
     */
    public static void removeStateFilesIn(Path repositoryRoot) {
        for (String name : STATE_FILES) {
            try {
                Files.deleteIfExists(repositoryRoot.resolve(name));
            } catch (IOException exception) {
                continue;
            }
        }
    }
}
