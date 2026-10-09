package com.kaizten.sheriff.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kaizten.sheriff.infrastructure.process.Platform;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Wires this jar's hooks into Claude Code's settings, for one project or for
 * every project the user opens, or its turn and Stop hooks into Codex's, or
 * takes them out again.
 *
 * <p>{@code scripts/install.sh} wires them for the user, so that nobody has
 * to remember a step per project. The MCP server is registered for the user
 * too, and its instructions already ask for the standards in every project;
 * the hooks only hold the model to them. They were opt-in per project until
 * 29 September, for fear of a JVM and a Sheriff container on every edit of
 * every project. Measured that day, a hook where there is nothing Sheriff
 * analyzes starts, decides and lets the work through in about 0.1 s, and a
 * container runs only for a component in a language Sheriff analyzes, whose
 * verdict is then cached while its files stay the same. Per project, in its
 * {@code .claude/settings.json}, is still how a team shares them by
 * committing that file, and how a machine installed without them opts in.
 *
 * <p>Installing twice leaves one copy. Any earlier entry that runs either
 * hook is replaced, which is also what retires the shell script the skill
 * folder used to ship. Everything else in the file is kept as it was.
 *
 * <p>The turn hook goes in beside the Stop hook wherever that goes: it
 * records, when a prompt arrives, what the components already changed look
 * like, so that the Stop hook checks only what the turn itself changed.
 *
 * <p>Codex reads the same shape from {@code ~/.codex/hooks.json}, and only the
 * turn and Stop hooks apply there: Codex edits with {@code apply_patch}, whose
 * call carries a patch rather than a file path, so the gate would have nothing
 * to look at.
 */
public final class HookInstaller {

    private static final String PROJECT_VARIABLE = "CLAUDE_PROJECT_DIR";
    private static final String SETTINGS_DIRECTORY = ".claude";
    private static final String SETTINGS_FILE = "settings.json";
    private static final String CODEX_HOOKS_FILE = "hooks.json";
    private static final String FAIL_FAST_OPTION = " --fail-fast";
    private static final String NO_OPTION = "";
    private static final String HOOKS = "hooks";
    private static final String PRE_TOOL_USE = "PreToolUse";
    private static final String STOP = "Stop";
    private static final String USER_PROMPT_SUBMIT = "UserPromptSubmit";
    private static final String MATCHER = "matcher";
    private static final String TIMEOUT = "timeout";
    private static final String PERMISSIONS = "permissions";
    private static final String ALLOW = "allow";
    private static final String TEST_TOOL = "mcp__sheriff__sheriff_test";
    private static final String FIX_TOOL = "mcp__sheriff__sheriff_fix";
    private static final String GUIDELINES_TOOL = "mcp__sheriff__sheriff_guidelines";
    private static final String TASK_TOOL = "mcp__sheriff__sheriff_task";
    private static final List<String> ALLOWED_TOOLS = List.of(TEST_TOOL, FIX_TOOL, GUIDELINES_TOOL, TASK_TOOL);
    private static final int STOP_TIMEOUT_SECONDS = 900;
    private static final int FIRST_ENTRY = 0;
    private static final String EDITING_TOOLS = "Edit|Write|MultiEdit|NotebookEdit";
    private static final String TYPE = "type";
    private static final String COMMAND_TYPE = "command";
    private static final String COMMAND = "command";
    private static final String GATE_FLAG = "--hook-gate";
    private static final String STOP_FLAG = "--hook-stop";
    private static final String TURN_FLAG = "--hook-turn";
    private static final String HOOK_COMMAND = "%s -jar %s %s%s";
    private static final String JAVA_ON_THE_PATH = "java";
    private static final String QUOTED = "\"%s\"";
    private static final String HOME_REFERENCE = "$HOME/";
    private static final char BACKSLASH = '\\';
    private static final char SLASH = '/';
    private static final String NOT_AN_OBJECT = "%s is not a JSON object; left it untouched.%n";
    private static final String INSTALLED = """
            Sheriff's hooks are in %s:
              before an edit     refuse new code on a component that already fails the standards
              when a turn ends   refuse to finish while what the turn changed has errors
              when a turn starts note what was already changed, so that it is not the turn's
            sheriff_test, sheriff_fix, sheriff_guidelines and sheriff_task run without asking.
            Commit that file to share them; each machine needs the jar at %s.
            Undo with: java -jar sheriff-mcp.jar --uninstall-hooks
            """;
    private static final String INSTALLED_FOR_USER = """
            Sheriff's hooks are in %s, for every project Claude Code opens:
              before an edit     refuse new code on a component that already fails the standards
              when a turn ends   refuse to finish while what the turn changed has errors
              when a turn starts note what was already changed, so that it is not the turn's
            sheriff_test, sheriff_fix, sheriff_guidelines and sheriff_task run without asking.
            Where there is nothing Sheriff analyzes, they let everything through.
            Undo with: java -jar sheriff-mcp.jar --uninstall-hooks --user
            """;
    private static final String INSTALLED_FOR_CODEX = """
            Sheriff's hooks are in %s, for every project Codex opens:
              when a turn ends   refuse to finish while what the turn changed has errors
              when a turn starts note what was already changed, so that it is not the turn's
            Codex runs them once you approve them: it asks the first time a session starts, or use /hooks.
            Until then a turn can end with errors left: approve them in your first session.
            """;
    private static final String UNINSTALLED = "Sheriff's hooks are no longer in %s.%n";
    private static final int SUCCESS = 0;
    private static final int FAILURE = 2;

    private final ObjectMapper json = new ObjectMapper();
    private final Path settings;
    private final Path jar;
    private final Path home;
    private final PrintStream output;
    private final String installed;
    private final boolean withGate;
    private final boolean failFast;
    private final String java;

    /**
     * Wires an installer for one project.
     *
     * @param project the project whose settings get the hooks
     * @param jar this jar, which the hooks will run
     * @param home the user's home, so a jar under it is written portably
     * @param output where to report what was done
     */
    public HookInstaller(Path project, Path jar, Path home, PrintStream output) {
        this(project.resolve(SETTINGS_DIRECTORY).resolve(SETTINGS_FILE), jar, home, output, INSTALLED, true, false,
                JAVA_ON_THE_PATH);
    }

    /**
     * Every field, for the constructor above and the factories below.
     *
     * @param settings the file that gets the hooks
     * @param jar this jar, which the hooks will run
     * @param home the user's home, so a jar under it is written portably
     * @param output where to report what was done
     * @param installed what to report once they are in
     * @param withGate whether the gate goes in beside the turn and Stop hooks
     * @param failFast whether the hooks ask Sheriff to stop at the first error
     * @param java the Java the hooks run with: {@code java}, found on the
     *     PATH when they run, or a path to one
     */
    private HookInstaller(Path settings, Path jar, Path home, PrintStream output, String installed,
            boolean withGate, boolean failFast, String java) {
        this.settings = settings;
        this.jar = jar;
        this.home = home;
        this.output = output;
        this.installed = installed;
        this.withGate = withGate;
        this.failFast = failFast;
        this.java = java;
    }

    /**
     * The same installer, with hooks that ask Sheriff to stop at the first
     * error: {@code --fail-fast} in the command itself, since Codex passes a
     * hook almost none of the shell's variables, and an assignment before the
     * command is not something every platform's shell understands.
     *
     * @return that installer
     */
    public HookInstaller failingFast() {
        return new HookInstaller(settings, jar, home, output, installed, withGate, true, java);
    }

    /**
     * The same installer, with hooks that run a given Java rather than the
     * {@code java} found on the PATH when they run.
     *
     * <p>For the hooks of every project, which {@code install.sh} writes with
     * the Java it checked: an editor started from the desktop can have
     * another PATH than the terminal, with an older Java or none on it, and
     * then every hook failed to start. A project's own settings, which a team
     * commits, keep {@code java}.
     *
     * @param executable the Java to run, by its path
     * @return that installer
     */
    public HookInstaller runningWith(Path executable) {
        return new HookInstaller(settings, jar, home, output, installed, withGate, failFast,
                pathReference(executable));
    }

    /**
     * Adds the hooks to the project's settings, replacing any earlier copy.
     *
     * @return 0 when the settings were written, 2 when they could not be
     */
    public int install() {
        return rewrite(true);
    }

    /**
     * Takes the hooks out of the project's settings.
     *
     * @return 0 when the settings were written, 2 when they could not be
     */
    public int uninstall() {
        return rewrite(false);
    }

    /**
     * Reads the settings, drops every entry that runs a hook, optionally adds
     * the current pair, and writes the file back.
     *
     * @param add whether to add the hooks after removing the old ones
     * @return the exit code
     */
    private int rewrite(boolean add) {
        try {
            JsonNode read = Files.exists(settings) ? json.readTree(settings.toFile()) : json.createObjectNode();
            if (read == null || read.isMissingNode()) {
                read = json.createObjectNode();
            }
            if (!read.isObject()) {
                output.printf(NOT_AN_OBJECT, settings);
                return FAILURE;
            }
            ObjectNode root = (ObjectNode) read;
            ObjectNode hooks = root.has(HOOKS) && root.get(HOOKS).isObject()
                    ? (ObjectNode) root.get(HOOKS)
                    : root.putObject(HOOKS);
            ArrayNode before = withoutSheriff(hooks, PRE_TOOL_USE);
            ArrayNode stop = withoutSheriff(hooks, STOP);
            ArrayNode prompt = withoutSheriff(hooks, USER_PROMPT_SUBMIT);
            if (add && withGate) {
                before.add(group(GATE_FLAG).put(MATCHER, EDITING_TOOLS));
            }
            if (add) {
                stop.add(group(STOP_FLAG, STOP_TIMEOUT_SECONDS));
                prompt.add(group(TURN_FLAG));
            }
            prune(hooks, PRE_TOOL_USE, before);
            prune(hooks, STOP, stop);
            prune(hooks, USER_PROMPT_SUBMIT, prompt);
            if (hooks.isEmpty()) {
                root.remove(HOOKS);
            }
            if (withGate) {
                rewritePermissions(root, add);
            }
            write(settings, root);
            output.print(add ? String.format(installed, settings, jar) : String.format(UNINSTALLED, settings));
            return SUCCESS;
        } catch (IOException exception) {
            output.println(exception.getMessage());
            return FAILURE;
        }
    }

    /**
     * Lets the sheriff tools the order of work goes through run without a
     * prompt, or stops letting them, keeping every other rule as it was.
     *
     * <p>Under Opus and Sonnet in auto mode they ran without asking; under
     * Haiku, on 5 October, the first call to {@code sheriff_fix} stopped for
     * a permission prompt, so the same work went differently with each
     * model and permission mode. {@code sheriff_fix} only applies
     * Sheriff's own fixers, which git undoes, and Codex already has it
     * approved. {@code sheriff_autofix} is left out: it commits and spends
     * tokens, and asking first is right.
     *
     * @param root the settings
     * @param add whether to add the rules after removing them
     */
    private void rewritePermissions(ObjectNode root, boolean add) {
        JsonNode existing = root.path(PERMISSIONS);
        if (!existing.isObject() && !add) {
            return;
        }
        ObjectNode permissions = existing.isObject() ? (ObjectNode) existing : root.putObject(PERMISSIONS);
        JsonNode rules = permissions.path(ALLOW);
        ArrayNode allow = json.createArrayNode();
        if (rules.isArray()) {
            rules.forEach(rule -> {
                if (!ALLOWED_TOOLS.contains(rule.asText())) {
                    allow.add(rule);
                }
            });
        }
        if (add) {
            ALLOWED_TOOLS.forEach(allow::add);
        }
        if (allow.isEmpty()) {
            permissions.remove(ALLOW);
        } else {
            permissions.set(ALLOW, allow);
        }
        if (permissions.isEmpty()) {
            root.remove(PERMISSIONS);
        }
    }

    /**
     * One event's hook groups, with every hook that runs Sheriff taken out
     * and every group left empty by that dropped.
     *
     * @param hooks the settings' {@code hooks} object
     * @param event the event's name
     * @return the groups that remain
     */
    private ArrayNode withoutSheriff(ObjectNode hooks, String event) {
        ArrayNode kept = json.createArrayNode();
        JsonNode groups = hooks.path(event);
        for (JsonNode group : groups) {
            if (!group.isObject() || !group.path(HOOKS).isArray()) {
                kept.add(group);
                continue;
            }
            ObjectNode copy = ((ObjectNode) group).deepCopy();
            ArrayNode entryList = (ArrayNode) copy.get(HOOKS);
            Iterator<JsonNode> entries = entryList.elements();
            while (entries.hasNext()) {
                if (runsSheriff(entries.next())) {
                    entries.remove();
                }
            }
            if (!entryList.isEmpty()) {
                kept.add(copy);
            }
        }
        return kept;
    }

    /**
     * Whether one hook entry runs one of Sheriff's hooks, from this jar or
     * from any earlier wiring.
     *
     * @param entry the entry
     * @return {@code true} when its command names a Sheriff hook
     */
    private static boolean runsSheriff(JsonNode entry) {
        String command = entry.path(COMMAND).asText();
        return command.contains(GATE_FLAG) || command.contains(STOP_FLAG) || command.contains(TURN_FLAG);
    }

    /**
     * A hook group running one of this jar's hooks.
     *
     * @param flag which hook
     * @return the group
     */
    private ObjectNode group(String flag) {
        ObjectNode group = json.createObjectNode();
        ObjectNode entry = group.putArray(HOOKS).addObject();
        entry.put(TYPE, COMMAND_TYPE);
        entry.put(COMMAND, String.format(HOOK_COMMAND, javaReference(), jarReference(), flag,
                failFast ? FAIL_FAST_OPTION : NO_OPTION));
        return group;
    }

    /**
     * A hook group running one of this jar's hooks, given longer than the
     * client's default to finish.
     *
     * <p>The Stop hook runs the project's tests with
     * {@code SHERIFF_STOP_RUNS_TESTS=1}, which take up to ten minutes, and a
     * hook the client cuts short lets the turn end with nothing checked.
     *
     * @param flag which hook
     * @param seconds how long it may run
     * @return the group
     */
    private ObjectNode group(String flag, int seconds) {
        ObjectNode group = group(flag);
        ((ObjectNode) group.path(HOOKS).get(FIRST_ENTRY)).put(TIMEOUT, seconds);
        return group;
    }

    /**
     * How the hook command names this jar: through {@code $HOME} when the jar
     * is under it, so a committed settings file works on every machine that
     * installed it the same way.
     *
     * @return the quoted path
     */
    String jarReference() {
        return pathReference(jar);
    }

    /**
     * How the hook command names the Java it runs.
     *
     * @return {@code java}, or the quoted path {@link #runningWith} gave
     */
    String javaReference() {
        return java;
    }

    /**
     * How the hook command names a file: through {@code $HOME} when it is
     * under it, quoted.
     *
     * @param file the file
     * @return the quoted path
     */
    private String pathReference(Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        boolean throughHome = absolute.startsWith(home) && !Platform.windows();
        String path = throughHome ? HOME_REFERENCE + Platform.slashes(home.relativize(absolute))
                : absolute.toString().replace(BACKSLASH, SLASH);
        return String.format(QUOTED, path);
    }

    /**
     * Puts an event's groups back, or removes the event when none are left.
     *
     * @param hooks the settings' {@code hooks} object
     * @param event the event's name
     * @param groups what remains for it
     */
    private static void prune(ObjectNode hooks, String event, ArrayNode groups) {
        if (groups.isEmpty()) {
            hooks.remove(event);
        } else {
            hooks.set(event, groups);
        }
    }

    /**
     * Writes the settings whole, where a link points and with the
     * permissions they had, as {@link ConfigFile} does for every installer.
     *
     * @param settings the file
     * @param root its new content
     * @throws IOException when it cannot be written, or is a link to nothing
     */
    private void write(Path settings, ObjectNode root) throws IOException {
        ConfigFile.replace(settings, json.writerWithDefaultPrettyPrinter().writeValueAsString(root)
                + System.lineSeparator());
    }

    /**
     * The installer for the project a command runs in.
     *
     * @param environment the process environment, for Claude Code's project
     *     directory
     * @param workingDirectory where the command was started
     * @param jar this jar
     * @param home the user's home
     * @param output where to report
     * @return the installer
     */
    public static HookInstaller forProject(
            Map<String, String> environment, Path workingDirectory, Path jar, Path home, PrintStream output) {
        String open = environment.get(PROJECT_VARIABLE);
        Path project = open == null || open.isBlank() ? workingDirectory : Path.of(open);
        return new HookInstaller(project, jar, home, output);
    }

    /**
     * The installer for every project the user opens: Claude Code's user
     * settings, {@code ~/.claude/settings.json}.
     *
     * @param jar this jar
     * @param home the user's home, whose {@code .claude} folder gets them
     * @param output where to report
     * @return the installer
     */
    public static HookInstaller forUser(Path jar, Path home, PrintStream output) {
        return new HookInstaller(home.resolve(SETTINGS_DIRECTORY).resolve(SETTINGS_FILE), jar, home, output,
                INSTALLED_FOR_USER, true, false, JAVA_ON_THE_PATH);
    }

    /**
     * The installer for every project Codex opens: its turn and Stop hooks,
     * without the gate, in {@code hooks.json} in Codex's home.
     *
     * @param jar this jar
     * @param codexHome Codex's home, {@code CODEX_HOME} or {@code ~/.codex}
     * @param home the user's home
     * @param output where to report
     * @return the installer
     */
    public static HookInstaller forCodex(Path jar, Path codexHome, Path home, PrintStream output) {
        return new HookInstaller(codexHome.resolve(CODEX_HOOKS_FILE), jar, home, output, INSTALLED_FOR_CODEX, false,
                false, JAVA_ON_THE_PATH);
    }
}
