package com.kaizten.sheriff.infrastructure.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaizten.sheriff.domain.valueobject.VerificationResult;
import com.kaizten.sheriff.infrastructure.config.ChosenSettings;
import com.kaizten.sheriff.infrastructure.config.LoosenedSettings;
import com.kaizten.sheriff.infrastructure.config.Loosening;
import com.kaizten.sheriff.infrastructure.coverage.CoverageVerdict;
import com.kaizten.sheriff.infrastructure.git.GitVersionControl;
import com.kaizten.sheriff.infrastructure.git.UntestedMethods;
import com.kaizten.sheriff.infrastructure.process.BuildOutput;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * The Stop hook: a turn does not end with errors in a component it touched,
 * whether it brought them or found them there.
 *
 * <p>This is the half of the gate that actually holds. A PreToolUse hook only
 * sees the file-editing tools, so anything written from a shell command walks
 * straight past it — and that is not a theoretical hole: an edit the gate had
 * just refused went in unhindered when the same content was written from a
 * heredoc. Policing arbitrary shell commands is a race nobody wins, so this
 * checks the <em>result</em> instead of the intention: at the end of a turn it
 * asks git what changed, works out which components those files belong to,
 * and refuses to let the turn finish while any of them has errors.
 *
 * <p>It blocks again while errors remain and the model is still changing the
 * sources, up to a cap of blocks in a row per session
 * ({@code SHERIFF_STOP_MAX_BLOCKS}, 5 by default). It used to block once and
 * let the next stop through, whatever was left: a demo ended that way with 183
 * errors and a question to the user, after a pass that had changed sixteen
 * files. A stop with nothing changed since the last block is sent back once
 * more, saying so: Sonnet at low effort ended a turn that way, refusing to go
 * on with 177 errors it could fix, where a single stop with no changes used to
 * be taken as "what is left cannot be fixed". After {@code SHERIFF_STOP_MAX_STALLS}
 * such stops (1 by default) the next goes through, since a model whose edits
 * are being denied, or that is held by a false positive of the checker, cannot
 * change anything, and sending it back would only loop. A session the payload does not name is
 * still blocked once, since there is nothing to count it by.
 *
 * <p>Errors left for the end ({@link StuckErrors}) do not hold a turn: when the
 * stops with nothing changed run out, the errors still there are left for the
 * end, and from then on a component whose only errors are those is let
 * through, here and at the gate, so a false positive of the checker no longer
 * sends every later turn back. The MCP's answers still put them before the
 * user at the end.
 *
 * <p>Only what the turn changed is checked. The turn hook ({@link #startTurn})
 * records the fingerprint of every component git already reported when the
 * prompt arrived, and a component still the same at the stop is left alone,
 * unless the gate refused an edit to it during the turn: a turn whose only
 * edit was refused changed nothing, and a model ended one that way, asking the
 * user whether to fix the errors the gate had just shown it.
 * That used to be a paragraph of the message asking the model to tell for
 * itself whether the turn wrote code, and the message was most of a screen. It
 * is now a few lines ending in the step, and every decision in it is made here: whether there
 * are errors, whose turn they belong to, when to stop asking. A weaker model
 * reads a long message as a list of options, and took the ones that let it
 * stop.
 *
 * <p>Before any of that, a turn does not end with the project's settings
 * checking less than when it began ({@link LoosenedSettings}, against the
 * record of {@link SettingsBaseline}, committed in the turn or not): the model
 * can write {@code .sheriff.properties} and a {@code pom.xml} as it writes
 * code, and a threshold it lowered there was the one thing nothing looked
 * at. A line the user gave its value through
 * {@code sheriff_config} asked by an MCP client or the settings page
 * ({@link ChosenSettings}) is theirs: it is not sent back.
 */
public final class SheriffStopHook {

    private static final String STOP_HOOK_ACTIVE = "stop_hook_active";
    private static final String SESSION_ID = "session_id";
    private static final int BLOCK_ONCE = 1;
    private static final int DEFAULT_MAX_STALLS = 1;
    private static final String CAP_REACHED =
            "The code standards Stop hook sent this turn back %d times in a row with errors left -- "
            + "letting it end so that it does not loop.%n";
    private static final String NO_PROGRESS =
            "Nothing changed in %d stop(s) in a row after the code standards Stop hook sent this turn back -- "
            + "letting it end. SHERIFF_STOP_MAX_STALLS (sheriff_config) sets how many it sends back.%n";
    private static final String STALLED = """
            Nothing changed since the last time this turn was sent back, and the errors below
            are still there. Fix them now. Stop again without changing anything only if none of
            them can be fixed, and then name each one and say why.

            """;
    private static final String SETTINGS_STALLED = """
            Nothing changed since the last time this turn was sent back, and the lines below
            still check less. Put them back now. If you stop again without changing them, tell
            the user each one first: every later turn is sent back over them too.

            """;
    private static final String SOURCES_SEPARATOR = ",";
    private static final String EMPTY_PAYLOAD = "{}";
    private static final String BLOCKED = """
            Not done: what this turn changed has Sheriff errors, those that were already there
            included. All of them are fixed before finishing; only a false positive of the checker
            may stay, named, with the reason.

            %s
            %s
            """;
    private static final String REFUSED_BLOCKED = """
            Not done: an edit this turn tried was stopped because the component already had Sheriff
            errors, and they are still there. All of them are fixed, and then the edit made again;
            only a false positive of the checker may stay, named, with the reason.

            %s
            %s
            """;
    private static final String COMPONENT_REPORT = "%s: %s error(s)%n%s";
    private static final String DEFERRED = "%s: its %s error(s) are left for the end, as they stayed through the "
            + "work done for them -- not holding the turn for them.%n";
    private static final String UNTESTED_BLOCKED = """
            Not done: methods this turn added have no test that calls them, so nothing checks they work.

            %s
            %s
            """;
    private static final String UNTESTED_LINE = "  %s: %s";
    private static final String TESTS_BLOCKED = """
            Not done: the standards check is clean, but the project's own tests fail.

            %s
            %s
            """;
    private static final String TESTS_REPORT = "%s:%n%s%n";
    private static final String COVERAGE_BLOCKED = """
            Not done: the tests pass, but they do not test enough of what this turn wrote.

            %s
            %s
            """;
    private static final String COVERAGE_REPORT = "%s: %s%s%n";
    private static final String NOT_MEASURED =
            "How well the tests test what this turn wrote in %s could not be measured (%s) -- not checked.%n";
    private static final String SETTINGS_BLOCKED = """
            Not done: the project's Sheriff settings now check less than they did, and only the
            user decides what they check.

            %s

            Next step: put each line back to its value before the arrow; committing it does not
            keep it. If the user asked for one of these changes, make it with the sheriff_config
            tool, which they approve; written any other way it is sent back again.
            """;
    private static final String SETTINGS_LINE = "  %s";
    private static final String NO_CONTENT = "";
    private static final String NO_GIT =
            "The code standards hook could not ask git what changed (%s) -- nothing is checked this turn.%n";
    private static final int TEST_OUTPUT_LIMIT = 1500;
    private static final String NO_COMPONENT = "";
    private static final String PROJECT_TESTS = "the project's tests";
    private final ComponentGate gate;
    private final GitVersionControl git;
    private final Function<String, VerificationResult> tests;
    private final StopBlocks blocks;
    private final TurnStart turns;
    private final int maxBlocks;
    private String sources = "";
    private UnaryOperator<String> testCommands = component -> PROJECT_TESTS;
    private UntestedMethods untested;
    private Function<String, CoverageVerdict> coverage;
    private int maxStalls = DEFAULT_MAX_STALLS;
    private final Map<String, GateVerdict> dirtyVerdicts = new LinkedHashMap<>();
    private String reason = "";
    private ChosenSettings chosen = new ChosenSettings();
    private SettingsBaseline baselines = new SettingsBaseline();
    private boolean gitAnswered;
    private boolean settingsBlocked;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Wires the hook.
     *
     * @param gate the shared component gate
     * @param git what to ask which files changed, run from the project the
     *     session is open on, which is inside the repository even when the
     *     directory Sheriff mounts is not
     */
    public SheriffStopHook(ComponentGate gate, GitVersionControl git) {
        this(gate, git, null, new StopBlocks(), new TurnStart(), BLOCK_ONCE);
    }

    /**
     * Wires the hook to also require the tests of every touched component
     * to pass.
     *
     * @param gate the shared component gate
     * @param git what to ask which files changed
     * @param tests what runs one component's own tests, or {@code null} to
     *     check the standards only
     */
    public SheriffStopHook(ComponentGate gate, GitVersionControl git, Function<String, VerificationResult> tests) {
        this(gate, git, tests, new StopBlocks(), new TurnStart(), BLOCK_ONCE);
    }

    /**
     * Wires the hook with how many times in a row it may send a session back.
     *
     * @param gate the shared component gate
     * @param git what to ask which files changed
     * @param tests what runs one component's own tests, or {@code null} to
     *     check the standards only
     * @param blocks where the blocks in a row are counted
     * @param turns what each component looked like when the turn began
     * @param maxBlocks how many blocks in a row before it lets the turn end
     */
    public SheriffStopHook(ComponentGate gate,
            GitVersionControl git,
            Function<String, VerificationResult> tests,
            StopBlocks blocks,
            TurnStart turns,
            int maxBlocks) {
        this.gate = gate;
        this.git = git;
        this.tests = tests;
        this.blocks = blocks;
        this.turns = turns;
        this.maxBlocks = maxBlocks;
    }

    /**
     * The same hook, naming in its step the command that runs a component's
     * tests, from wherever the model's shell is.
     *
     * @param commands the command for each component
     * @return this hook
     */
    public SheriffStopHook withTestCommands(UnaryOperator<String> commands) {
        this.testCommands = commands;
        return this;
    }

    /**
     * The same hook, also refusing to let a turn end while a method it added
     * has no test that calls it.
     *
     * @param check what finds those methods
     * @return this hook
     */
    public SheriffStopHook withUntestedMethods(UntestedMethods check) {
        this.untested = check;
        return this;
    }

    /**
     * The same hook, sending a stop with nothing changed since the last block
     * back this many times in a row before it lets the turn end.
     *
     * @param stalls that number, 0 or more
     * @return this hook
     */
    public SheriffStopHook withMaxStalls(int stalls) {
        this.maxStalls = stalls;
        return this;
    }

    /**
     * The same hook, also refusing to let a turn end while the tests, which
     * pass, do not test what it wrote as well as the project asks. It needs
     * the tests on, as the measure runs them again.
     *
     * @param measure what measures how well a component's tests test a change
     * @return this hook
     */
    public SheriffStopHook withChangeCoverage(Function<String, CoverageVerdict> measure) {
        this.coverage = measure;
        return this;
    }

    /**
     * The same hook, reading which settings the user chose from another
     * record than the user's own.
     *
     * @param record where those settings are recorded
     * @return this hook
     */
    public SheriffStopHook withChosenSettings(ChosenSettings record) {
        this.chosen = record;
        return this;
    }

    /**
     * The same hook, keeping what the settings held at the start of a turn
     * in another record than the user's own.
     *
     * @param record where those records go
     * @return this hook
     */
    public SheriffStopHook withSettingsBaseline(SettingsBaseline record) {
        this.baselines = record;
        return this;
    }

    /**
     * The turn hook: records what every component git reports as changed
     * looks like before the turn does anything. It never blocks and prints
     * nothing, since what a prompt hook prints is added to the conversation.
     *
     * @param payload the hook payload the assistant sent
     * @return the exit code, always 0
     */
    public int startTurn(String payload) {
        String session;
        try {
            session = json.readTree(payload.isBlank() ? EMPTY_PAYLOAD : payload).path(SESSION_ID).asText();
        } catch (IOException exception) {
            return HookExit.ALLOW;
        }
        List<Path> changed = changedFiles();
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (String component : componentsOf(changed)) {
            fingerprints.put(component, gate.fingerprintOf(component));
        }
        turns.record(session, fingerprints);
        if (gitAnswered) {
            baselines.recordTurn(session, snapshotOf(changed));
        }
        return HookExit.ALLOW;
    }

    /**
     * Decides whether the turn may end.
     *
     * @param payload the hook payload Claude Code sent
     * @return the exit code: 0 to let it end, 2 to send it back to work
     */
    public int decide(String payload) {
        JsonNode call;
        try {
            call = json.readTree(payload.isBlank() ? EMPTY_PAYLOAD : payload);
        } catch (IOException exception) {
            return HookExit.ALLOW;
        }
        String session = call.path(SESSION_ID).asText();
        boolean again = call.path(STOP_HOOK_ACTIVE).asBoolean();
        if (!again) {
            blocks.reset(session);
        } else if (session.isEmpty()) {
            return HookExit.ALLOW;
        } else if (blocks.count(session) >= maxBlocks) {
            System.err.printf(CAP_REACHED, blocks.count(session));
            blocks.reset(session);
            return HookExit.ALLOW;
        }
        int exit = check(session);
        boolean stalledNow = exit == HookExit.BLOCK && again && sources.equals(blocks.lastSources(session));
        if (stalledNow && blocks.stalls(session) >= maxStalls) {
            dirtyVerdicts.forEach(gate::stalledOn);
            System.err.printf(NO_PROGRESS, blocks.stalls(session) + BLOCK_ONCE);
            exit = HookExit.ALLOW;
        }
        if (exit == HookExit.BLOCK) {
            System.err.print(stalledNow ? stalledPreface() + reason : reason);
            blocks.recordBlock(session, sources, stalledNow);
        } else {
            blocks.reset(session);
        }
        return exit;
    }

    /**
     * What goes before a block's reason when nothing changed since the last
     * one.
     *
     * @return the preface for the settings, or for errors
     */
    private String stalledPreface() {
        return settingsBlocked ? SETTINGS_STALLED : STALLED;
    }

    /**
     * The check itself: the standards of every component this turn changed,
     * then their tests when asked for. It prints nothing: it keeps the reason
     * and the signature of the sources, and the caller prints the reason only
     * if it does block.
     *
     * @param session the session, whose turn-start record says which
     *     components the turn left as they were and which the gate refused an
     *     edit to
     * @return the exit code
     */
    private int check(String session) {
        List<Path> changed = changedFiles();
        SettingsSnapshot baseline = baselines.heldFor(gate.mount()).or(() -> baselines.turn(session))
                .orElse(SettingsSnapshot.lastCommit());
        List<String> loosened = loosenedSettings(changed, baseline);
        if (!loosened.isEmpty()) {
            baselines.hold(gate.mount(), baseline.hasCommit() ? baseline
                    : new SettingsSnapshot(git.headCommit().orElse(NO_CONTENT), baseline.contents()));
            settingsBlocked = true;
            sources = String.join(SOURCES_SEPARATOR, loosened);
            reason = String.format(SETTINGS_BLOCKED, String.join(System.lineSeparator(), loosened));
            return HookExit.BLOCK;
        }
        if (gitAnswered) {
            baselines.release(gate.mount());
        }
        Set<String> touched = componentsOf(changed);
        Optional<Map<String, String>> start = turns.recorded(session);
        start.ifPresent(recorded -> touched.removeIf(component -> unchanged(component, recorded)));
        boolean changedNone = touched.isEmpty();
        if (start.isPresent()) {
            touched.addAll(turns.refusedThisTurn(session));
        }
        sources = touched.stream().map(gate::fingerprintOf).collect(Collectors.joining(SOURCES_SEPARATOR));
        List<String> dirty = new ArrayList<>();
        String first = NO_COMPONENT;
        for (String component : touched) {
            GateVerdict verdict = gate.verdictFor(component);
            if (!verdict.known() || verdict.clean()) {
                continue;
            }
            if (gate.onlyDeferred(component, verdict)) {
                System.err.printf(DEFERRED, component, gate.countOf(verdict));
            } else {
                dirtyVerdicts.put(component, verdict);
                dirty.add(String.format(COMPONENT_REPORT, component, gate.countOf(verdict), verdict.sample()));
                first = first.isEmpty() ? component : first;
            }
        }
        if (!dirty.isEmpty()) {
            reason = String.format(changedNone ? REFUSED_BLOCKED : BLOCKED,
                    String.join(System.lineSeparator(), dirty) + gate.floorNote(), gate.repairStep(first));
            return HookExit.BLOCK;
        }
        if (untestedDecide(touched) == HookExit.BLOCK) {
            return HookExit.BLOCK;
        }
        if (testsDecide(touched) == HookExit.BLOCK) {
            return HookExit.BLOCK;
        }
        return coverageDecide(touched);
    }

    /**
     * Whether a component is as it was when the turn began.
     *
     * @param component the component
     * @param start each component's fingerprint at the start of the turn
     * @return {@code true} when its fingerprint is known and the same
     */
    private boolean unchanged(String component, Map<String, String> start) {
        String now = gate.fingerprintOf(component);
        return !now.isEmpty() && now.equals(start.get(component));
    }

    /**
     * The methods the turn added that no changed test calls, once the
     * standards are clean.
     *
     * @param touched the components the turn changed
     * @return the exit code
     */
    private int untestedDecide(Set<String> touched) {
        if (untested == null) {
            return HookExit.ALLOW;
        }
        List<String> lines = new ArrayList<>();
        String first = NO_COMPONENT;
        for (String component : touched) {
            for (String method : untested.in(gate.mount(), component)) {
                lines.add(String.format(UNTESTED_LINE, component, method));
                first = first.isEmpty() ? component : first;
            }
        }
        if (lines.isEmpty()) {
            return HookExit.ALLOW;
        }
        reason = String.format(UNTESTED_BLOCKED, String.join(System.lineSeparator(), lines),
                gate.untestedStep(first, testCommands.apply(first)));
        return HookExit.BLOCK;
    }

    /**
     * The second half, when it is on: the tests of every touched component
     * must pass too.
     *
     * @param touched the components the turn touched
     * @return the exit code
     */
    private int testsDecide(Set<String> touched) {
        if (tests == null) {
            return HookExit.ALLOW;
        }
        List<String> failing = new ArrayList<>();
        String first = NO_COMPONENT;
        for (String component : touched) {
            VerificationResult result = tests.apply(component);
            if (!result.ok()) {
                first = first.isEmpty() ? component : first;
                failing.add(String.format(TESTS_REPORT, component,
                        BuildOutput.excerpt(result.output(), TEST_OUTPUT_LIMIT)));
            }
        }
        if (failing.isEmpty()) {
            return HookExit.ALLOW;
        }
        reason = String.format(TESTS_BLOCKED, String.join(System.lineSeparator(), failing),
                gate.testsStep(first, testCommands.apply(first)));
        return HookExit.BLOCK;
    }

    /**
     * The last half, with the tests on and passing: they test what the turn
     * wrote as well as the project asks. A measure that cannot run checks
     * nothing, and says so, as the hooks do when Sheriff cannot run.
     *
     * @param touched the components the turn touched
     * @return the exit code
     */
    private int coverageDecide(Set<String> touched) {
        if (tests == null || coverage == null) {
            return HookExit.ALLOW;
        }
        List<String> weak = new ArrayList<>();
        String first = NO_COMPONENT;
        for (String component : touched) {
            CoverageVerdict verdict = coverage.apply(component);
            if (verdict.applies() && !verdict.measured()) {
                System.err.printf(NOT_MEASURED, component, verdict.problem());
            } else if (!verdict.passes()) {
                first = first.isEmpty() ? component : first;
                weak.add(String.format(COVERAGE_REPORT, component, verdict.summary(), verdict.shortfall()));
            }
        }
        if (weak.isEmpty()) {
            return HookExit.ALLOW;
        }
        reason = String.format(COVERAGE_BLOCKED, String.join(System.lineSeparator(), weak), gate.coverageStep(first));
        return HookExit.BLOCK;
    }

    /**
     * The components whose files git says were touched.
     *
     * @return those component names, in a stable order
     */
    Set<String> touchedComponents() {
        return componentsOf(changedFiles());
    }

    /**
     * The components some files belong to.
     *
     * @param changed the files
     * @return those component names, in a stable order
     */
    private Set<String> componentsOf(List<Path> changed) {
        Set<String> components = new LinkedHashSet<>();
        for (Path path : changed) {
            String component = gate.componentFor(path.toString());
            if (!component.isEmpty()) {
                components.add(component);
            }
        }
        return components;
    }

    /**
     * Every file git reports as touched, modified or untracked.
     *
     * <p>Best effort: no git, no check. This is a guardrail, not a dependency,
     * but it says so: a session opened outside any repository used to have
     * every turn end with nothing checked and nothing said.
     *
     * @return those paths, absolute
     */
    private List<Path> changedFiles() {
        try {
            List<Path> changed = git.changedPaths();
            gitAnswered = true;
            return changed;
        } catch (IllegalStateException exception) {
            gitAnswered = false;
            System.err.printf(NO_GIT, String.valueOf(exception.getMessage()).strip());
            return List.of();
        }
    }

    /**
     * What the settings hold as a turn begins: the commit, and the settings
     * files that differ from it, with their text.
     *
     * @param changed the files git reports as changed
     * @return that record, its commit empty when there is none yet
     */
    private SettingsSnapshot snapshotOf(List<Path> changed) {
        Map<Path, String> contents = new LinkedHashMap<>();
        for (Path file : changed.stream().filter(LoosenedSettings::holdsSettings).toList()) {
            contents.put(file, textOf(file));
        }
        return new SettingsSnapshot(git.headCommit().orElse(NO_CONTENT), contents);
    }

    /**
     * The lines of the settings files that check less than they did in a
     * record, and that the user did not give their value: those changed
     * since its commit, committed or not, and those it kept the text of.
     * Best effort, as the rest: a file git cannot show is not compared.
     *
     * @param changed the files git reports as changed
     * @param baseline what the settings held before
     * @return each such line, as the message shows it
     */
    private List<String> loosenedSettings(List<Path> changed, SettingsSnapshot baseline) {
        Set<Path> files = new LinkedHashSet<>(changed);
        files.addAll(baseline.contents().keySet());
        if (baseline.hasCommit()) {
            files.addAll(changedSince(baseline.commit()));
        }
        List<String> lines = new ArrayList<>();
        for (Path file : files.stream().filter(LoosenedSettings::holdsSettings).toList()) {
            for (Loosening loosening : loosenedIn(file, baseline)) {
                if (!chosen.chose(file, loosening.key(), loosening.after())) {
                    lines.add(String.format(SETTINGS_LINE, loosening.describeFrom(gate.mount())));
                }
            }
        }
        return lines;
    }

    /**
     * The lines of one settings file that check less than in a record.
     *
     * @param file the file
     * @param baseline what the settings held before
     * @return those lines, none when git cannot show the file
     */
    private List<Loosening> loosenedIn(Path file, SettingsSnapshot baseline) {
        try {
            String before = baseline.contents().containsKey(file) ? baseline.contents().get(file)
                    : git.contentAt(baseline.revision(), file);
            return LoosenedSettings.between(file, before, textOf(file));
        } catch (IllegalStateException exception) {
            return List.of();
        }
    }

    /**
     * The tracked files changed since a commit, best effort.
     *
     * @param commit the commit
     * @return those files, none when git cannot compare
     */
    private List<Path> changedSince(String commit) {
        try {
            return git.changedSince(commit);
        } catch (IllegalStateException exception) {
            return List.of();
        }
    }

    /**
     * A file's text, empty when it is gone or cannot be read.
     *
     * @param file the file
     * @return its text
     */
    private static String textOf(Path file) {
        try {
            return Files.isRegularFile(file) ? Files.readString(file) : NO_CONTENT;
        } catch (IOException exception) {
            return NO_CONTENT;
        }
    }
}
