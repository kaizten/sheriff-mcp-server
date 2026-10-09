package com.kaizten.sheriff.infrastructure.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.infrastructure.config.Composition;
import com.kaizten.sheriff.infrastructure.config.Configuration;
import com.kaizten.sheriff.infrastructure.mcp.tool.ToolCall;
import com.kaizten.sheriff.infrastructure.process.Platform;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * What both hooks need: which component a file belongs to, and whether that
 * component is currently clean.
 *
 * <p>The component is taken from the edited file's own path rather than from
 * configuration, so every component in a repository is guarded rather than
 * whichever one happens to be configured. The agent's own tree is never
 * guarded: it is the tool, not something Sheriff analyzes.
 *
 * <p>A verdict is cached per component and expires two ways — on time, and on
 * a fingerprint of the component's source files. Time alone failed in both
 * directions: a dirty verdict outlived the fix, so the loop would clean a
 * component and the gate would keep refusing edits for another quarter of an
 * hour; and a clean verdict outlived any breakage introduced by a route the
 * gate cannot see, which is most of them.
 */
public final class ComponentGate {

    private static final String AT_LEAST = "at least %d";
    private static final String FLOOR_NOTE = "%nSheriff was asked to stop at the first error, so this is a floor and not the total: "
            + "sheriff_test lists every one.%n";
    private static final String FAIL_FAST_MARK = "fail-fast";
    private static final String JAVA_SUFFIX = ".java";
    private static final String TYPESCRIPT_SUFFIX = ".ts";
    private static final String TYPESCRIPT_JSX_SUFFIX = ".tsx";
    private static final String VUE_SUFFIX = ".vue";
    private static final String PERL_SCRIPT_SUFFIX = ".pl";
    private static final String PERL_MODULE_SUFFIX = ".pm";
    private static final String PYTHON_SUFFIX = ".py";

    /**
     * Extensions Sheriff actually analyzes.
     *
     * <p>Editing a README is not this gate's business.
     */
    static final Set<String> GUARDED_SUFFIXES = Set.of(
            JAVA_SUFFIX,
            TYPESCRIPT_SUFFIX,
            TYPESCRIPT_JSX_SUFFIX,
            VUE_SUFFIX,
            PERL_SCRIPT_SUFFIX,
            PERL_MODULE_SUFFIX,
            PYTHON_SUFFIX);

    private static final String DEPENDENCIES_FOLDER = "node_modules";
    private static final String GIT_FOLDER = ".git";
    private static final Set<String> NEVER_SOURCES = Set.of(DEPENDENCIES_FOLDER, GIT_FOLDER);

    private static final String COMPONENTS_FIELD = "components";
    private static final String CHECKED_AT_FIELD = "checked_at";
    private static final String ERRORS_FIELD = "errors";
    private static final String SAMPLE_FIELD = "sample";
    private static final String FINGERPRINT_FIELD = "fingerprint";
    private static final String FILES_FIELD = "files";
    private static final String STATE_FILE = "gate_state.json";
    private static final String ALLOWED_COMPONENTS = "SHERIFF_GATE_COMPONENTS";
    private static final String TTL_VARIABLE = "SHERIFF_GATE_TTL";
    private static final String SAMPLE_LINE = "  - %s: %s";
    private static final String MORE = "%n  ... and %d more";
    private static final String SEPARATOR = ":";
    private static final String LIST_SEPARATOR = ",";
    private static final String ENTRY_SEPARATOR = "|";
    private static final long DEFAULT_TTL_SECONDS = 900L;
    private static final int SAMPLE_SIZE = 5;
    private static final int FINGERPRINT_LENGTH = 16;
    private static final int COMPONENT_DEPTH = 2;
    private static final String DIGEST = "SHA-256";

    private static final String FIX_TOOL = "sheriff_fix";
    private static final String NO_PROFILE = "";
    private static final String DEFAULT_JAR = "sheriff-mcp.jar";
    private static final String JAR_SUFFIX = ".jar";
    private static final String REPAIR_STEP = "Next step: call the sheriff_fix tool with component '%s'. Then do "
            + "the step each answer ends with, and the one after it, until an answer ends with \"Next step: none, "
            + "the component is done\". Do not ask the user whether to.%nWithout the sheriff tools, %s instead: "
            + "it gives the same answers, with each step written as a command.";
    private static final String UNTESTED_STEP = "Next step: add a test under the component's src/test for "
            + "each method above that calls it and checks what it returns, edge cases included (nothing to "
            + "return, a null among the inputs). Then run %s, and call the sheriff_test tool with component '%s' "
            + "once more. Do not ask the user whether to.";
    private static final String TESTS_STEP = "Next step: run %s and fix what fails until it passes, then call "
            + "the sheriff_test tool with component '%s' once more, as a fix can break a rule. If the tests "
            + "already failed before this turn, say so instead of leaving them.";

    private final Configuration configuration;
    private final Composition composition;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Wires the gate.
     *
     * @param configuration what was configured
     * @param composition what builds the analyzer
     */
    public ComponentGate(Configuration configuration, Composition composition) {
        this.configuration = configuration;
        this.composition = composition;
    }

    /**
     * How many errors a verdict names, for a message a model reads.
     *
     * <p>Stopping at the first error means the count is a floor, and a bare
     * "1" would read as a small job.
     *
     * @param verdict what Sheriff said
     * @return the number, or "at least" it when Sheriff stopped at the first
     */
    public String countOf(GateVerdict verdict) {
        return configuration.failFast() ? String.format(AT_LEAST, verdict.errors()) : String.valueOf(verdict.errors());
    }

    /**
     * What to add to a message so that a count which is a floor is not read
     * as the whole job.
     *
     * @return a sentence saying where the full list is, or nothing when
     *     Sheriff ran to the end
     */
    public String floorNote() {
        return configuration.failFast() ? String.format(FLOOR_NOTE) : "";
    }

    /**
     * The step a hook ends with when a component has errors: the same one an
     * analysis by the MCP ends with, the repair, with the component named and
     * with the command that does the same without the MCP's tools.
     *
     * <p>A hook has already analyzed the component, so it does not send the
     * model to analyze it again. It used to say "call sheriff_test" and
     * nothing more, and a model whose session had no sheriff tools (the
     * server not registered, or not found by Codex's tool search) had nothing
     * it could do.
     *
     * @param component the component with errors
     * @return the step, in two lines
     */
    public String repairStep(String component) {
        String command = ToolCall.throughCommandLine(configuration.projectDirectory(), runningJar())
                .phrase(FIX_TOOL, component, NO_PROFILE);
        return String.format(REPAIR_STEP, component, command);
    }

    /**
     * The step a hook ends with when a component is clean and its tests fail.
     *
     * @param component the component
     * @param testCommand the command that runs its tests, from anywhere
     * @return the step
     */
    public String testsStep(String component, String testCommand) {
        return String.format(TESTS_STEP, testCommand, component);
    }

    /**
     * The step a hook ends with when a component's new methods have no test.
     *
     * @param component the component
     * @param testCommand the command that runs its tests, from anywhere
     * @return the step
     */
    public String untestedStep(String component, String testCommand) {
        return String.format(UNTESTED_STEP, testCommand, component);
    }

    /**
     * The directory Sheriff mounts, which every component is a folder of.
     *
     * @return that directory
     */
    public Path mount() {
        return configuration.targetRepository();
    }

    /**
     * A file as Sheriff names it: relative to the mount, with {@code /}.
     *
     * @param filePath the file, as the editor named it
     * @return that name, or the empty string when the file is outside the
     *     mount
     */
    public String sheriffName(String filePath) {
        Path repository = configuration.targetRepository();
        Path candidate = Path.of(filePath).toAbsolutePath().normalize();
        return candidate.startsWith(repository) ? Platform.slashes(repository.relativize(candidate)) : "";
    }

    /**
     * The jar this code runs from.
     *
     * @return its path, or its usual name when the code is not in a jar
     */
    private static Path runningJar() {
        try {
            Path location = Path.of(ComponentGate.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            boolean jar = Files.isRegularFile(location) && location.toString().endsWith(JAR_SUFFIX);
            return jar ? location : Path.of(DEFAULT_JAR);
        } catch (URISyntaxException | RuntimeException exception) {
            return Path.of(DEFAULT_JAR);
        }
    }

    /**
     * The component a file belongs to.
     *
     * @param filePath the file that is about to be edited
     * @return its component, or the empty string when it is not one this gate
     *     guards
     */
    public String componentFor(String filePath) {
        if (filePath == null || filePath.isEmpty()) {
            return "";
        }
        Path repository = configuration.targetRepository();
        Path candidate = Path.of(filePath).toAbsolutePath().normalize();
        if (!candidate.startsWith(repository) || !isGuardedSuffix(candidate)) {
            return "";
        }
        Path relative = repository.relativize(candidate);
        if (relative.getNameCount() < COMPONENT_DEPTH) {
            return "";
        }
        String component = relative.getName(0).toString();
        if (component.equals(configuration.agentDirectory().getFileName().toString())) {
            return "";
        }
        List<String> allowed = allowedComponents();
        return allowed.isEmpty() || allowed.contains(component) ? component : "";
    }

    /**
     * Whether a component is clean, from cache when that can be trusted.
     *
     * @param component the component to judge
     * @return its verdict, unavailable when Sheriff could not run
     */
    public GateVerdict verdictFor(String component) {
        String fingerprint = fingerprintOf(component);
        GateVerdict cached = cachedVerdict(component, fingerprint);
        if (cached != null) {
            return cached;
        }
        AnalysisResult result = composition.analyzerFor(component).analyze();
        if (result.error()) {
            return GateVerdict.UNAVAILABLE;
        }
        GateVerdict verdict = new GateVerdict(result.total(), sampleOf(result), filesOf(result));
        remember(component, verdict, fingerprint);
        return verdict;
    }

    /**
     * A cheap signature of a component's source files.
     *
     * <p>Any edit changes it, whichever tool made the edit, which is what lets
     * a cached verdict expire on content instead of only on time. An
     * unreadable or missing tree returns the empty string, meaning "cannot
     * tell" — which must stay distinguishable from "no files". It also tells
     * a verdict reached stopping at the first error from a full one, so that
     * neither is served for the other.
     *
     * @param component the component to fingerprint
     * @return that signature, or the empty string
     */
    String fingerprintOf(String component) {
        Path root = configuration.targetRepository().resolve(component);
        if (!Files.isDirectory(root)) {
            return "";
        }
        try {
            List<String> entries = new ArrayList<>();
            for (Path file : guardedFilesUnder(root)) {
                entries.add(file + SEPARATOR + Files.size(file) + SEPARATOR
                        + Files.getLastModifiedTime(file).toMillis());
            }
            entries.sort(String::compareTo);
            if (configuration.failFast()) {
                entries.add(FAIL_FAST_MARK);
            }
            return digest(String.join(ENTRY_SEPARATOR, entries));
        } catch (IOException exception) {
            return "";
        }
    }

    /**
     * Every file of a guarded language under a directory, without going into
     * the folders that never hold a project's own sources.
     *
     * <p>{@code node_modules} alone can hold a hundred thousand files, and
     * walking them only to filter them out afterwards cost every edit and
     * every end of a turn once the hooks were on in every project.
     *
     * @param directory where to look
     * @return those files
     * @throws IOException when a directory cannot be listed
     */
    private static List<Path> guardedFilesUnder(Path directory) throws IOException {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> children = Files.list(directory)) {
            for (Path child : children.toList()) {
                if (Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                    if (!NEVER_SOURCES.contains(child.getFileName().toString())) {
                        found.addAll(guardedFilesUnder(child));
                    }
                } else if (Files.isRegularFile(child) && isGuardedSuffix(child)) {
                    found.add(child);
                }
            }
        }
        return found;
    }

    /**
     * The verdict already on record for a component, when it can still be
     * trusted.
     *
     * @param component the component to look up
     * @param fingerprint that component's current signature
     * @return the cached verdict, or {@code null} when there is none, it has
     *     expired, or the sources moved underneath it
     */
    private GateVerdict cachedVerdict(String component, String fingerprint) {
        JsonNode entry = readState().path(COMPONENTS_FIELD).path(component);
        if (entry.isMissingNode() || !entry.has(CHECKED_AT_FIELD) || !entry.has(ERRORS_FIELD)) {
            return null;
        }
        long age = System.currentTimeMillis() / 1000L - entry.path(CHECKED_AT_FIELD).asLong();
        if (age > ttlSeconds()) {
            return null;
        }
        if (!fingerprint.isEmpty() && !fingerprint.equals(entry.path(FINGERPRINT_FIELD).asText())) {
            return null;
        }
        Set<String> files = new TreeSet<>();
        entry.path(FILES_FIELD).forEach(file -> files.add(file.asText()));
        return new GateVerdict(entry.path(ERRORS_FIELD).asInt(), entry.path(SAMPLE_FIELD).asText(), files);
    }

    /**
     * Puts a fresh verdict on record, together with the signature it was
     * measured against.
     *
     * @param component the component it judges
     * @param verdict what Sheriff said about it
     * @param fingerprint the signature of the sources that produced it
     */
    private void remember(String component, GateVerdict verdict, String fingerprint) {
        ObjectNode state = (ObjectNode) readState();
        ObjectNode components = state.has(COMPONENTS_FIELD)
                ? (ObjectNode) state.get(COMPONENTS_FIELD)
                : state.putObject(COMPONENTS_FIELD);
        ObjectNode entry = components.putObject(component);
        entry.put(CHECKED_AT_FIELD, System.currentTimeMillis() / 1000L);
        entry.put(ERRORS_FIELD, verdict.errors());
        entry.put(SAMPLE_FIELD, verdict.sample());
        entry.put(FINGERPRINT_FIELD, fingerprint);
        verdict.files().stream().sorted().forEach(entry.putArray(FILES_FIELD)::add);
        writeState(state);
    }

    /**
     * Loads the cache from disk.
     *
     * <p>Best effort, like saving it: an unreadable cache means a slower gate,
     * not a broken one, so anything unusable reads back as an empty cache.
     *
     * @return what was saved, or an empty object
     */
    private JsonNode readState() {
        try {
            JsonNode state = json.readTree(Files.readString(stateFile()));
            return state.isObject() ? state : json.createObjectNode();
        } catch (IOException exception) {
            return json.createObjectNode();
        }
    }

    /**
     * Saves the cache. Best effort: an unwritable cache means a slower gate,
     * not a broken one.
     *
     * @param state what to save
     */
    private void writeState(JsonNode state) {
        try {
            Files.createDirectories(stateFile().getParent());
            Files.writeString(stateFile(), json.writeValueAsString(state));
        } catch (IOException exception) {
            return;
        }
    }

    /**
     * Where the cache lives: beside the prompt logs, which is the agent's own
     * scratch space rather than anything in the analyzed repository.
     *
     * @return that file's path
     */
    private Path stateFile() {
        return configuration.promptLogDirectory().resolve(STATE_FILE);
    }

    /**
     * How long a verdict may be reused before it is measured again.
     *
     * <p>Only half of the expiry: content expiry is the other half, and the
     * one that catches a component cleaned or broken inside the window.
     *
     * @return the configured lifetime in seconds, or the default when it is
     *     unset or not a number; a hook must not fail over a typo
     */
    private long ttlSeconds() {
        String configured = System.getenv(TTL_VARIABLE);
        if (configured == null || configured.isBlank()) {
            return DEFAULT_TTL_SECONDS;
        }
        try {
            return Long.parseLong(configured.strip());
        } catch (NumberFormatException exception) {
            return DEFAULT_TTL_SECONDS;
        }
    }

    /**
     * The components the gate has been narrowed to.
     *
     * <p>An empty list is not "guard nothing", it is "guard everything": the
     * variable exists to narrow the default, never to widen it.
     *
     * @return those names, empty when nothing was configured
     */
    private static List<String> allowedComponents() {
        String configured = System.getenv(ALLOWED_COMPONENTS);
        if (configured == null || configured.isBlank()) {
            return List.of();
        }
        List<String> allowed = new ArrayList<>();
        for (String name : configured.split(LIST_SEPARATOR)) {
            if (!name.isBlank()) {
                allowed.add(name.strip());
            }
        }
        return allowed;
    }

    /**
     * Whether a file is of a kind Sheriff has anything to say about.
     *
     * @param path the file to judge
     * @return {@code true} when its extension is one of the guarded ones
     */
    private static boolean isGuardedSuffix(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return GUARDED_SUFFIXES.stream().anyMatch(name::endsWith);
    }

    /**
     * The first few errors, as the text a blocked edit is refused with.
     *
     * <p>A handful is the point: a hook message is read in passing, so it
     * shows enough to recognise the problem and says how much it left out.
     *
     * @param result what Sheriff reported
     * @return those lines, empty when there were no errors
     */
    private static String sampleOf(AnalysisResult result) {
        List<SheriffFinding> errors = result.errors();
        List<String> lines = new ArrayList<>();
        for (SheriffFinding finding : errors.subList(0, Math.min(SAMPLE_SIZE, errors.size()))) {
            lines.add(String.format(SAMPLE_LINE, finding.file(), finding.description()));
        }
        String sample = String.join(System.lineSeparator(), lines);
        return errors.size() > SAMPLE_SIZE
                ? sample + String.format(MORE, errors.size() - SAMPLE_SIZE)
                : sample;
    }

    /**
     * Every file Sheriff reported an error in.
     *
     * @param result what Sheriff reported
     * @return those files, as Sheriff names them
     */
    private static Set<String> filesOf(AnalysisResult result) {
        Set<String> files = new TreeSet<>();
        result.errors().forEach(finding -> files.add(finding.file()));
        return files;
    }

    /**
     * A short hash of the fingerprint material.
     *
     * <p>Short on purpose: this only has to change when the sources change,
     * not to resist anyone trying to make it collide.
     *
     * @param content what to hash
     * @return the truncated hexadecimal digest, or the empty string when the
     *     algorithm is unavailable
     */
    private static String digest(String content) {
        try {
            byte[] hash = MessageDigest.getInstance(DIGEST).digest(content.getBytes());
            return HexFormat.of().formatHex(hash).substring(0, FINGERPRINT_LENGTH);
        } catch (NoSuchAlgorithmException exception) {
            return "";
        }
    }
}
