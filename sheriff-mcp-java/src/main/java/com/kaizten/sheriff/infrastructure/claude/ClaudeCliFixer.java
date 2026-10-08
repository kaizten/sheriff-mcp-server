package com.kaizten.sheriff.infrastructure.claude;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaizten.sheriff.domain.port.CodeFixer;
import com.kaizten.sheriff.domain.valueobject.FixRequest;
import com.kaizten.sheriff.domain.valueobject.FixResult;
import com.kaizten.sheriff.infrastructure.process.Platform;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The fixer, backed by the Claude Code CLI rather than a billed API: it rides
 * on whatever session is already authenticated on the machine.
 *
 * <p>Four consequences of that choice are visible here. Claude Code brings
 * its own file tools, so this adapter does no file I/O and the request's file
 * scope is only advisory — the loop verifies compliance afterwards through
 * version control. The session it spawns is a full Claude Code session, so
 * the repository's own hooks fire inside it; one of those hooks blocks edits
 * to a component with outstanding errors, which would stop the very tool meant
 * to clear them, so children are marked with an environment variable the hook
 * stands aside for. The CLI is asked for its structured result
 * (`--output-format json`) rather than plain text, so what it actually cost —
 * tokens, cache reads, wall time — is something this logs rather than
 * something nobody can answer afterwards; a response that does not parse as
 * that shape is treated exactly as plain text always was, so a CLI version
 * that changed its output, or a failure before any JSON was written, degrades
 * rather than breaks. And the one shell command allowed through is
 * {@code git mv}, and only that one, scoped by Claude Code's own tool
 * pattern rather than opened as plain {@code Bash} — measured on Spring
 * PetClinic: a rule that renames a reserved Java filename
 * ({@code package-info.java}) cannot be satisfied by editing an existing
 * file, and without a real rename the loop's own scope check correctly, but
 * unhelpfully, read the resulting new-file-plus-orphan as untracked changes
 * and cut every single time. A scoped {@code git mv} is what the fix needed,
 * not a general shell.
 *
 * <p>Granting the tool was not enough on its own, and that is worth
 * remembering as its own fact: three more measured runs, on the same
 * repository, kept cutting in the same place, because the loop's shared
 * prompt template never mentioned {@code git mv} existed — it is shared
 * with backends that have no such tool, so it cannot say so. This class is
 * where that gap gets closed instead, appending a short, CLI-specific line
 * to every prompt it sends, because it is the one adapter that actually
 * knows the capability is there.
 *
 * <p>Edits are allowed inside the component and nowhere else, by Claude
 * Code's own permission rule, {@code Edit(//<absolute component path>/**)}, rather than by
 * {@code --permission-mode acceptEdits}, which accepts an edit anywhere under
 * the directory the CLI runs in. That directory is the one Sheriff mounts,
 * and for a repository that is itself one module it is the repository's
 * parent: every sibling project on the machine was editable, and none of
 * them is in the repository whose status the scope check reads. An edit the
 * rule refuses is reported back to the model as refused, and counted in the
 * log and on the console.
 *
 * <p>The path in the rule is absolute on purpose. A relative one,
 * {@code Edit(<component>/**)}, is resolved against the session's current
 * directory, and that moves: a model that runs {@code cd <component>} before
 * editing, which models often do, turns it into
 * {@code <component>/<component>/**}, and every edit after that is denied.
 * Reproduced with Claude Code 2.1.284 on 28 September: it made a repair pass
 * deny all three of its edits, and an earlier run deny all four files.
 *
 * <p>It may also run the project's tests, and only them: the command the
 * loop verifies with, as an exact rule, without the {@code cd} in front of
 * it. Without that, a repair pass handed a failing build worked blind: it
 * fixed the one error the output showed and could not see the next, while
 * the Codex backend, whose sandbox runs commands, reached green on the same
 * project. The rule is exact, so extra goals ({@code mvn -q test deploy})
 * are still refused; the loop runs the same tests itself after every pass.
 */
public final class ClaudeCliFixer implements CodeFixer {

    private static final String EXECUTABLE = "claude";
    private static final String PRINT_FLAG = "-p";
    private static final String PERMISSION_FLAG = "--permission-mode";
    private static final String PERMISSION_MODE = "acceptEdits";
    private static final String TOOLS_FLAG = "--allowedTools";
    private static final String ALLOWED_TOOLS = "Read,Edit,Bash(git mv:*)";
    private static final String SCOPED_TOOLS = "Read,Edit(/%s/**),Bash(git mv:*)";
    private static final String TEST_RULE = ",Bash(%s)";
    private static final String LEADING_CD_PATTERN = "^cd\\s+('[^']*'|\"[^\"]*\"|\\S+)\\s*&&\\s*";
    private static final Pattern LEADING_CD = Pattern.compile(LEADING_CD_PATTERN);
    private static final String RULE_OPEN = "(";
    private static final String RULE_CLOSE = ")";
    private static final String EMPTY = "";
    private static final String TESTS_HINT = "\n\n"
            + "To check that the project still works, run its tests with exactly this command, which is "
            + "the one you are allowed to run: `%s`";
    private static final String PERMISSION_DENIALS_FIELD = "permission_denials";
    private static final String DENIALS_MESSAGE =
            "  the assistant was refused %d action(s) outside what it may touch (see the log)%n";
    private static final int NO_DENIALS = 0;
    private static final String OUTPUT_FORMAT_FLAG = "--output-format";
    private static final String OUTPUT_FORMAT_JSON = "json";
    private static final String AGENT_MARKER = "SHERIFF_AGENT_RUNNING";
    private static final String AGENT_MARKER_VALUE = "1";
    private static final String EXIT_FAILURE = "exit code %d";
    private static final String LOGGED_MESSAGE = "  prompt/response logged to %s%n";
    private static final String RESULT_FIELD = "result";
    private static final String IS_ERROR_FIELD = "is_error";
    private static final String TOTAL_COST_FIELD = "total_cost_usd";
    private static final String USAGE_FIELD = "usage";
    private static final String NUM_TURNS_FIELD = "num_turns";
    private static final String INPUT_TOKENS_FIELD = "input_tokens";
    private static final String OUTPUT_TOKENS_FIELD = "output_tokens";
    private static final String CACHE_CREATION_TOKENS_FIELD = "cache_creation_input_tokens";
    private static final String CACHE_READ_TOKENS_FIELD = "cache_read_input_tokens";
    private static final String UNKNOWN_NUMBER = "?";
    private static final int UNKNOWN_COUNT = -1;
    private static final String USAGE_SUMMARY_FORMAT =
            "%n%n=== USAGE ===%ncost_usd=%s turns=%d input_tokens=%d output_tokens=%d "
            + "cache_creation_input_tokens=%d cache_read_input_tokens=%d permission_denials=%s%n";
    private static final String GIT_MV_HINT = "\n\n"
            + "If a fix requires renaming a file (for example, to satisfy a filename-casing "
            + "rule), run `git mv <old> <new>` rather than creating a new file and leaving "
            + "the old one in place -- a real git-tracked rename is the only version of that "
            + "change the scope check running after this will recognize as the same file.";

    private final ProcessRunner processes;
    private final PromptLog promptLog;
    private final Path repositoryRoot;
    private final String component;
    private final String testCommand;
    private final Duration timeout;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * Wires the fixer to a repository, with edits accepted anywhere in it.
     *
     * @param processes how to run the CLI
     * @param promptLog where to record what was asked
     * @param repositoryRoot the directory the CLI runs in
     * @param timeout how long one invocation is given
     */
    public ClaudeCliFixer(ProcessRunner processes, PromptLog promptLog, Path repositoryRoot, Duration timeout) {
        this(processes, promptLog, repositoryRoot, "", timeout);
    }

    /**
     * Wires the fixer to one component of a repository, with edits allowed
     * inside that component only.
     *
     * @param processes how to run the CLI
     * @param promptLog where to record what was asked
     * @param repositoryRoot the directory the CLI runs in, which Sheriff mounts
     * @param component the component the edits are confined to, relative to
     *     that directory; empty to accept edits anywhere under it
     * @param timeout how long one invocation is given
     */
    public ClaudeCliFixer(
            ProcessRunner processes, PromptLog promptLog, Path repositoryRoot, String component, Duration timeout) {
        this(processes, promptLog, repositoryRoot, component, EMPTY, timeout);
    }

    /**
     * Wires the fixer to one component, and lets it run the project's tests
     * with the command the loop verifies with.
     *
     * @param processes how to run the CLI
     * @param promptLog where to record what was asked
     * @param repositoryRoot the directory the CLI runs in, which Sheriff mounts
     * @param component the component the edits are confined to; empty to
     *     accept edits anywhere under that directory
     * @param testCommand the project's test command, possibly behind a
     *     {@code cd}; empty to allow no command beyond {@code git mv}
     * @param timeout how long one invocation is given
     */
    public ClaudeCliFixer(ProcessRunner processes, PromptLog promptLog, Path repositoryRoot, String component,
            String testCommand, Duration timeout) {
        this.processes = processes;
        this.promptLog = promptLog;
        this.repositoryRoot = repositoryRoot;
        this.component = component;
        this.testCommand = testCommand;
        this.timeout = timeout;
    }

    /**
     * Runs one fixing session and reports what the CLI made of it.
     *
     * @param request the prompt to hand the CLI, and the label its log gets
     * @return what the session produced, or why it could not run
     */
    @Override
    public FixResult fix(FixRequest request) {
        String prompt = request.prompt() + GIT_MV_HINT
                + (testRule(testCommand).isEmpty() ? EMPTY : String.format(TESTS_HINT, testCommand));
        ProcessOutcome outcome = processes.run(
                command(), repositoryRoot, Map.of(AGENT_MARKER, AGENT_MARKER_VALUE), timeout, prompt);
        if (!outcome.ran()) {
            return FixResult.failed(outcome.failure());
        }
        JsonNode parsed = parseResult(outcome.standardOutput());
        Path logged = promptLog.record(request.label(), prompt, logText(outcome, parsed));
        if (logged != null) {
            System.out.printf(LOGGED_MESSAGE, logged);
        }
        int denials = parsed == null ? NO_DENIALS : parsed.path(PERMISSION_DENIALS_FIELD).size();
        if (denials > NO_DENIALS) {
            System.out.printf(DENIALS_MESSAGE, denials);
        }
        boolean erroredOut = parsed != null && parsed.path(IS_ERROR_FIELD).asBoolean(false);
        if (!outcome.succeeded() || erroredOut) {
            return FixResult.failed(failureReason(outcome, parsed));
        }
        return FixResult.succeeded(parsed == null ? outcome.standardOutput() : parsed.path(RESULT_FIELD).asText());
    }

    /**
     * The exact command this fixer runs. The prompt is not part of it: it
     * goes in on standard input, because as an argument a prompt over 128 KB,
     * a batch of large files with many findings, failed to start at all.
     *
     * @return the argument list
     */
    List<String> command() {
        String tests = testRule(testCommand);
        if (component.isEmpty()) {
            return List.of(EXECUTABLE, PRINT_FLAG, PERMISSION_FLAG, PERMISSION_MODE,
                    TOOLS_FLAG, ALLOWED_TOOLS + tests, OUTPUT_FORMAT_FLAG, OUTPUT_FORMAT_JSON);
        }
        return List.of(EXECUTABLE, PRINT_FLAG,
                TOOLS_FLAG, String.format(SCOPED_TOOLS,
                        Platform.posixForm(repositoryRoot.resolve(component).toAbsolutePath().normalize().toString()))
                        + tests,
                OUTPUT_FORMAT_FLAG, OUTPUT_FORMAT_JSON);
    }

    /**
     * The permission rule that lets the CLI run the project's tests: the
     * command without the {@code cd} in front of it, matched exactly, so the
     * model may add a pipe or a redirection but not another goal.
     *
     * @param testCommand the loop's test command, possibly empty
     * @return {@code ,Bash(<command>)}, or empty when there is no command or
     *     it holds a parenthesis, which the rule's own syntax cannot carry
     */
    static String testRule(String testCommand) {
        String core = testCommandCore(testCommand);
        return core.isEmpty() ? EMPTY : String.format(TEST_RULE, core);
    }

    /**
     * The project's test command as a model runs it from inside the
     * component: without the {@code cd} in front of it, and empty when it
     * holds a parenthesis, which a permission rule's syntax cannot carry.
     * Antigravity's rules take the same command.
     *
     * @param testCommand the loop's test command, possibly empty
     * @return that command, or empty
     */
    public static String testCommandCore(String testCommand) {
        String core = LEADING_CD.matcher(testCommand.strip()).replaceFirst(EMPTY).strip();
        return core.contains(RULE_OPEN) || core.contains(RULE_CLOSE) ? EMPTY : core;
    }

    /**
     * The CLI's structured result, when stdout actually is one.
     *
     * <p>Not every stdout is this shape: a CLI that crashed before writing
     * anything, an older version, or a change to the flag's own output all
     * answer with something else, and none of those are a reason to lose the
     * fix result itself.
     *
     * @param standardOutput what the process wrote to stdout
     * @return the parsed result, or {@code null} when it is not that shape
     */
    private JsonNode parseResult(String standardOutput) {
        String trimmed = standardOutput.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = json.readTree(trimmed);
            return node.has(RESULT_FIELD) ? node : null;
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    /**
     * What goes in the audit trail: the answer, and what it cost, when both
     * are known.
     *
     * @param outcome what the process produced
     * @param parsed its structured result, {@code null} when there was none
     * @return the text to log
     */
    private static String logText(ProcessOutcome outcome, JsonNode parsed) {
        if (parsed == null) {
            return outcome.standardOutput() + outcome.standardError();
        }
        return parsed.path(RESULT_FIELD).asText() + usageSummary(parsed);
    }

    /**
     * The cost of one invocation, read off its structured result.
     *
     * @param parsed the CLI's structured result
     * @return a line naming the cost, the turns and the token counts
     */
    private static String usageSummary(JsonNode parsed) {
        JsonNode usage = parsed.path(USAGE_FIELD);
        return String.format(USAGE_SUMMARY_FORMAT,
                parsed.path(TOTAL_COST_FIELD).asText(UNKNOWN_NUMBER),
                parsed.path(NUM_TURNS_FIELD).asInt(UNKNOWN_COUNT),
                usage.path(INPUT_TOKENS_FIELD).asInt(UNKNOWN_COUNT),
                usage.path(OUTPUT_TOKENS_FIELD).asInt(UNKNOWN_COUNT),
                usage.path(CACHE_CREATION_TOKENS_FIELD).asInt(UNKNOWN_COUNT),
                usage.path(CACHE_READ_TOKENS_FIELD).asInt(UNKNOWN_COUNT),
                parsed.path(PERMISSION_DENIALS_FIELD).toString());
    }

    /**
     * Why an invocation failed.
     *
     * <p>Claude Code puts its own explanations on stdout, not stderr —
     * including ones worth surfacing verbatim, such as a session limit. Reading
     * stderr alone once turned a hit rate limit into a bare "exit code 1".
     * The structured result's own answer, when there is one, is preferred
     * over either: it is the CLI's own account of why, not a guess at which
     * stream it landed on.
     *
     * @param outcome what the process produced
     * @param parsed its structured result, {@code null} when there was none
     * @return the most informative message available
     */
    private static String failureReason(ProcessOutcome outcome, JsonNode parsed) {
        if (parsed != null) {
            String result = parsed.path(RESULT_FIELD).asText();
            if (!result.isEmpty()) {
                return result;
            }
        }
        String error = outcome.standardError().strip();
        if (!error.isEmpty()) {
            return error;
        }
        String output = outcome.standardOutput().strip();
        return output.isEmpty() ? String.format(EXIT_FAILURE, outcome.exitCode()) : output;
    }
}
