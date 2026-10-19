package com.kaizten.sheriff.infrastructure.hook;

import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.infrastructure.config.Composition;
import com.kaizten.sheriff.infrastructure.config.Configuration;
import com.kaizten.sheriff.infrastructure.docker.MountLock;
import com.kaizten.sheriff.infrastructure.mcp.tool.ToolCall;
import com.kaizten.sheriff.infrastructure.process.Platform;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * What both hooks need: which component a file belongs to, and whether that
 * component is currently clean.
 *
 * <p>The component is taken from the edited file's own path rather than from
 * configuration, so every component in a repository is guarded rather than
 * whichever one happens to be configured. The agent's own tree is never
 * guarded: it is the tool, not something Sheriff analyzes.
 *
 * <p>A verdict is recorded per component and profile in {@link VerdictCache},
 * which the MCP's tools write too, and expires two ways — on time, and on a
 * fingerprint of the component's source files. Time alone failed in both
 * directions: a dirty verdict outlived the fix, so the loop would clean a
 * component and the gate would keep refusing edits for another quarter of an
 * hour; and a clean verdict outlived any breakage introduced by a route the
 * gate cannot see, which is most of them.
 */
public final class ComponentGate {

    private static final String AT_LEAST = "at least %d";
    private static final String FLOOR_NOTE = "%nSheriff was asked to stop at the first error, so this is a floor and not the total: "
            + "sheriff_test lists every one.%n";
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

    private static final String ALLOWED_COMPONENTS = "SHERIFF_GATE_COMPONENTS";
    private static final String LIST_SEPARATOR = ",";
    private static final int COMPONENT_DEPTH = 2;

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
    private static final String COVERAGE_STEP = "Next step: add tests under the component's src/test that run "
            + "each line listed, take every branch of each condition listed (each side of every && and ||) and "
            + "fail on each change listed, by checking what the code returns, edge cases included. Then call the sheriff_fix tool with component '%s' and verify true, and do the step each "
            + "answer ends with. Do not ask the user whether to.%nWithout the sheriff tools, %s instead.";
    private static final String TESTS_STEP = "Next step: run %s and fix what fails until it passes, then call "
            + "the sheriff_test tool with component '%s' once more, as a fix can break a rule. If the tests "
            + "already failed before this turn, say so instead of leaving them.";

    private final Configuration configuration;
    private final Composition composition;
    private final VerdictCache verdicts;

    /**
     * Wires the gate to the user's own record of verdicts, the one the MCP's
     * tools write too.
     *
     * @param configuration what was configured
     * @param composition what builds the analyzer
     */
    public ComponentGate(Configuration configuration, Composition composition) {
        this(configuration, composition, VerdictCache.lasting(configuration.gateTtl()));
    }

    /**
     * Wires the gate to a chosen record of verdicts.
     *
     * @param configuration what was configured
     * @param composition what builds the analyzer
     * @param verdicts where verdicts are recalled from and recorded
     */
    public ComponentGate(Configuration configuration, Composition composition, VerdictCache verdicts) {
        this.configuration = configuration;
        this.composition = composition;
        this.verdicts = verdicts;
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
     * The step a hook ends with when a component's tests pass but do not test
     * what the turn wrote as well as the project asks: more tests, then the
     * repair with {@code verify}, which measures them again.
     *
     * @param component the component
     * @return the step
     */
    public String coverageStep(String component) {
        String command = ToolCall.throughCommandLine(configuration.projectDirectory(), runningJar())
                .phraseVerifying(FIX_TOOL, component, NO_PROFILE);
        return String.format(COVERAGE_STEP, component, command);
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
     * Whether a component is clean, from the record of verdicts when that can
     * be trusted.
     *
     * <p>The record is looked at twice: once before waiting for the
     * component's lock, and again once it is held, as the run that held it
     * may have just recorded the answer. Edits made in parallel each run a
     * hook, all of them before any edit lands, and they used to run Sheriff
     * one after another on the same files.
     *
     * @param component the component to judge
     * @return its verdict, unavailable when Sheriff could not run
     */
    public GateVerdict verdictFor(String component) {
        Path directory = configuration.targetRepository().resolve(component);
        String profile = composition.profileFor(component);
        Optional<GateVerdict> recorded =
                verdicts.recalled(directory, profile, configuration.failFast(), fingerprintOf(component));
        if (recorded.isPresent()) {
            return recorded.get();
        }
        return MountLock.holding(directory, () -> verdictHolding(component, directory, profile));
    }

    /**
     * The verdict with the component's lock held: the record once more, and
     * Sheriff only when it still has no answer, measured against the
     * fingerprint taken before the run.
     *
     * @param component the component to judge
     * @param directory its directory
     * @param profile the profile it is judged under
     * @return its verdict, unavailable when Sheriff could not run
     */
    private GateVerdict verdictHolding(String component, Path directory, String profile) {
        String fingerprint = fingerprintOf(component);
        Optional<GateVerdict> recorded = verdicts.recalled(directory, profile, configuration.failFast(), fingerprint);
        if (recorded.isPresent()) {
            return recorded.get();
        }
        AnalysisResult result = composition.analyzerFor(component).analyze();
        if (result.error()) {
            return GateVerdict.UNAVAILABLE;
        }
        return verdicts.remember(directory, profile, configuration.failFast(), fingerprint, result);
    }

    /**
     * A cheap signature of the sources Sheriff reads in a component, as
     * {@link VerdictCache#fingerprintOf} takes it.
     *
     * @param component the component to fingerprint
     * @return that signature, or the empty string when it cannot be told
     */
    String fingerprintOf(String component) {
        return VerdictCache.fingerprintOf(configuration.targetRepository().resolve(component));
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

}
