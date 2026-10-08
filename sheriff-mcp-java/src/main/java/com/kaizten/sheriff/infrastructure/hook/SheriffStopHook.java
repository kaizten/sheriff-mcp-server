package com.kaizten.sheriff.infrastructure.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaizten.sheriff.domain.valueobject.VerificationResult;
import com.kaizten.sheriff.infrastructure.git.GitVersionControl;
import com.kaizten.sheriff.infrastructure.git.UntestedMethods;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * be taken as "what is left cannot be fixed". A second such stop in a row goes
 * through, since a model whose edits are being denied cannot change anything,
 * and sending it back would only loop. A session the payload does not name is
 * still blocked once, since there is nothing to count it by.
 *
 * <p>Only what the turn changed is checked. The turn hook ({@link #startTurn})
 * records the fingerprint of every component git already reported when the
 * prompt arrived, and a component still the same at the stop is left alone.
 * That used to be a paragraph of the message asking the model to tell for
 * itself whether the turn wrote code, and the message was most of a screen. It
 * is now a few lines ending in the step, and every decision in it is made here: whether there
 * are errors, whose turn they belong to, when to stop asking. A weaker model
 * reads a long message as a list of options, and took the ones that let it
 * stop.
 */
public final class SheriffStopHook {

    private static final String STOP_HOOK_ACTIVE = "stop_hook_active";
    private static final String SESSION_ID = "session_id";
    private static final int BLOCK_ONCE = 1;
    private static final String CAP_REACHED =
            "The code standards Stop hook sent this turn back %d times in a row with errors left -- "
            + "letting it end so that it does not loop.%n";
    private static final String NO_PROGRESS =
            "Nothing changed twice in a row since the code standards Stop hook sent this turn back -- "
            + "letting it end.%n";
    private static final String STALLED = """
            Nothing changed since the last time this turn was sent back, and the errors below
            are still there. Fix them now. Stop again without changing anything only if none of
            them can be fixed, and then name each one and say why.

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
    private static final String COMPONENT_REPORT = "%s: %s error(s)%n%s";
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
    private String reason = "";
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
    public SheriffStopHook(
            ComponentGate gate,
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
        Map<String, String> fingerprints = new LinkedHashMap<>();
        for (String component : touchedComponents()) {
            fingerprints.put(component, gate.fingerprintOf(component));
        }
        turns.record(session, fingerprints);
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
        if (stalledNow && blocks.stalled(session)) {
            System.err.printf(NO_PROGRESS);
            exit = HookExit.ALLOW;
        }
        if (exit == HookExit.BLOCK) {
            System.err.print(stalledNow ? STALLED + reason : reason);
            blocks.recordBlock(session, sources, stalledNow);
        } else {
            blocks.reset(session);
        }
        return exit;
    }

    /**
     * The check itself: the standards of every component this turn changed,
     * then their tests when asked for. It prints nothing: it keeps the reason
     * and the signature of the sources, and the caller prints the reason only
     * if it does block.
     *
     * @param session the session, whose turn-start record says which
     *     components the turn left as they were
     * @return the exit code
     */
    private int check(String session) {
        Set<String> touched = touchedComponents();
        turns.recorded(session).ifPresent(start -> touched.removeIf(component -> unchanged(component, start)));
        sources = touched.stream().map(gate::fingerprintOf).collect(Collectors.joining(SOURCES_SEPARATOR));
        List<String> dirty = new ArrayList<>();
        String first = NO_COMPONENT;
        for (String component : touched) {
            GateVerdict verdict = gate.verdictFor(component);
            if (verdict.known() && !verdict.clean()) {
                dirty.add(String.format(COMPONENT_REPORT, component, gate.countOf(verdict), verdict.sample()));
                first = first.isEmpty() ? component : first;
            }
        }
        if (!dirty.isEmpty()) {
            reason = String.format(BLOCKED, String.join(System.lineSeparator(), dirty) + gate.floorNote(),
                    gate.repairStep(first));
            return HookExit.BLOCK;
        }
        if (untestedDecide(touched) == HookExit.BLOCK) {
            return HookExit.BLOCK;
        }
        return testsDecide(touched);
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
                String output = result.output().strip();
                String tail = output.length() <= TEST_OUTPUT_LIMIT
                        ? output
                        : output.substring(output.length() - TEST_OUTPUT_LIMIT);
                failing.add(String.format(TESTS_REPORT, component, tail));
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
     * The components whose files git says were touched.
     *
     * @return those component names, in a stable order
     */
    Set<String> touchedComponents() {
        Set<String> components = new LinkedHashSet<>();
        for (String path : changedFiles()) {
            String component = gate.componentFor(path);
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
    private List<String> changedFiles() {
        try {
            return git.changedPaths().stream().map(Path::toString).toList();
        } catch (IllegalStateException exception) {
            System.err.printf(NO_GIT, String.valueOf(exception.getMessage()).strip());
            return List.of();
        }
    }
}
