package com.kaizten.sheriff.infrastructure.hook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Set;

/**
 * The PreToolUse hook: no new code on top of a component Sheriff is already
 * unhappy with, without being told first.
 *
 * <p>Claude Code hands it the tool call as JSON on standard input. Exit 0 lets
 * the edit through; exit 2 blocks it and shows this hook's message to the
 * model, which then knows to clear the errors first. It blocks once per
 * session and component ({@link GateMemory} says why), and never an edit to a
 * file Sheriff reported errors in: that edit is the one that clears them. It
 * used to block the first edit whatever it was, so a session already
 * following the repair steps, editing the first file sheriff_fix had listed,
 * was refused; it called sheriff_fix again before editing anything, which
 * repaired nothing (a second call helps after edits, or after a fixer that
 * makes room for another, not before), and made the same edit once more. An
 * edit that puts new code into a file with errors goes through too; the Stop
 * hook is what refuses a turn that ends with errors left.
 *
 * <p>It is fast feedback, not a guarantee, and the difference is worth stating
 * plainly: a PreToolUse hook only sees the file-editing tools, so anything
 * written from a shell command never reaches it. That is what the Stop hook is
 * for. It also only ever sees the state <em>before</em> a change, so the first
 * edit that breaks something always gets through.
 *
 * <p>Three things it deliberately does not do: guard anything but what Sheriff
 * analyzes, block the fix loop's own edits (that would stop the one tool that
 * clears the errors), and fail closed — when Sheriff cannot run, the edit
 * proceeds with a warning, because a gate that cannot check must not be able
 * to brick an editor.
 */
public final class SheriffGateHook {

    private static final String TOOL_NAME = "tool_name";
    private static final String TOOL_INPUT = "tool_input";
    private static final String FILE_PATH = "file_path";
    private static final String NOTEBOOK_PATH = "notebook_path";
    private static final String SESSION_ID = "session_id";
    private static final String EDIT_TOOL = "Edit";
    private static final String WRITE_TOOL = "Write";
    private static final String MULTI_EDIT_TOOL = "MultiEdit";
    private static final String NOTEBOOK_EDIT_TOOL = "NotebookEdit";
    private static final String EMPTY_PAYLOAD = "{}";
    private static final Set<String> EDIT_TOOLS =
            Set.of(EDIT_TOOL, WRITE_TOOL, MULTI_EDIT_TOOL, NOTEBOOK_EDIT_TOOL);
    private static final String BLOCKED = """
            Blocked: %s already has %s Sheriff error(s), from before this change, and %s
            has none of them. They are fixed first, in every file of the component, before the
            new code. Edits to the files that have them go through; make this one again once
            they are fixed, or now if it is part of fixing them.

            %s

            %s
            """;
    private static final String UNAVAILABLE =
            "sheriff-agent's gate could not run Sheriff on %s -- letting the edit through unchecked.%n";

    private final ComponentGate gate;
    private final GateMemory memory;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Wires the hook, remembering its warnings in the temporary directory.
     *
     * @param gate the shared component gate
     */
    public SheriffGateHook(ComponentGate gate) {
        this(gate, new GateMemory());
    }

    /**
     * Wires the hook with a chosen memory of the warnings it gave.
     *
     * @param gate the shared component gate
     * @param memory which sessions were already warned about which components
     */
    public SheriffGateHook(ComponentGate gate, GateMemory memory) {
        this.gate = gate;
        this.memory = memory;
    }

    /**
     * Decides on one tool call.
     *
     * @param payload the hook payload Claude Code sent
     * @return the exit code: 0 to allow, 2 to block
     */
    public int decide(String payload) {
        JsonNode call;
        try {
            call = json.readTree(payload.isBlank() ? EMPTY_PAYLOAD : payload);
        } catch (IOException exception) {
            return HookExit.ALLOW;
        }
        if (!EDIT_TOOLS.contains(call.path(TOOL_NAME).asText())) {
            return HookExit.ALLOW;
        }
        String component = gate.componentFor(editedPath(call));
        String session = call.path(SESSION_ID).asText();
        if (component.isEmpty() || memory.warned(session, component)) {
            return HookExit.ALLOW;
        }
        GateVerdict verdict = gate.verdictFor(component);
        if (!verdict.known()) {
            System.err.printf(UNAVAILABLE, component);
            return HookExit.ALLOW;
        }
        String file = gate.sheriffName(editedPath(call));
        if (verdict.clean() || verdict.hasErrorsIn(file)) {
            return HookExit.ALLOW;
        }
        memory.remember(session, component);
        System.err.printf(BLOCKED, component, gate.countOf(verdict), file,
                verdict.sample() + gate.floorNote(), gate.repairStep(component));
        return HookExit.BLOCK;
    }

    /**
     * The file a tool call is about.
     *
     * <p>NotebookEdit names its argument {@code notebook_path} rather than
     * {@code file_path}; reading only the latter waved every notebook through
     * while the matcher claimed otherwise.
     *
     * @param call the tool call
     * @return the path, or the empty string
     */
    static String editedPath(JsonNode call) {
        JsonNode input = call.path(TOOL_INPUT);
        String file = input.path(FILE_PATH).asText();
        return file.isEmpty() ? input.path(NOTEBOOK_PATH).asText() : file;
    }
}
