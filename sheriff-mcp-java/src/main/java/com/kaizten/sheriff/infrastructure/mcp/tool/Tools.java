package com.kaizten.sheriff.infrastructure.mcp.tool;

import com.kaizten.sheriff.domain.port.RuleCatalog;
import com.kaizten.sheriff.domain.valueobject.VerificationResult;
import com.kaizten.sheriff.infrastructure.docker.ImageProvisioning;
import com.kaizten.sheriff.infrastructure.git.UntestedMethods;
import com.kaizten.sheriff.infrastructure.mcp.McpConfig;
import com.kaizten.sheriff.infrastructure.mcp.ServerVersion;
import com.kaizten.sheriff.infrastructure.mcp.SheriffRunner;
import com.kaizten.sheriff.infrastructure.mcp.SheriffUnavailableException;
import com.kaizten.sheriff.infrastructure.mcp.protocol.ToolOutcome;
import com.kaizten.sheriff.infrastructure.mcp.task.Task;
import com.kaizten.sheriff.infrastructure.mcp.task.TaskFailure;
import com.kaizten.sheriff.infrastructure.mcp.task.TaskRegistry;
import com.kaizten.sheriff.infrastructure.process.SystemProcessRunner;
import com.kaizten.sheriff.infrastructure.shell.ShellTestRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The five tools this server exposes, by name, and how a call to one of them
 * becomes an answer. Each tool is its own class; this one wires them to what
 * they share and turns whatever they throw into a failure the client can read.
 *
 * <p>Four of the five call no model at all. There the model is the client:
 * it reads a finding, edits the code, and calls {@code sheriff_test} again,
 * which is what lets one server work from Claude or from an OpenAI client
 * with no provider-specific code and no tokens spent here.
 * {@code sheriff_autofix} is the deliberate exception: it runs the agent's own
 * loop, so it does call a model, edit files and commit.
 *
 * <p>Every call that runs Sheriff is a task: it gets an id, its command and
 * its state are recorded, and Sheriff's findings and tracked files are
 * exported into the task's folder. {@code sheriff_test} and
 * {@code sheriff_fix} run at once and answer with their result and their id;
 * {@code sheriff_autofix} answers with the id alone and runs in the
 * background, and {@code sheriff_task} is how the client asks after it.
 */
public final class Tools implements AutoCloseable {

    private static final String NO_TOOL = "No tool named '%s'. Available: %s.";
    private static final String NAME_SEPARATOR = ", ";
    private static final String ANALYZER_UNAVAILABLE = "The analyzer could not run: %s";
    private static final String EXCEPTION_MESSAGE_SEPARATOR = ": ";
    private static final String FAILED_TASK_FOOTER =
            "%n%nRecorded as task %s, failed. Whatever Sheriff wrote before it failed is in %s.";
    private static final Duration TEST_TIMEOUT = Duration.ofMinutes(10);
    private static final String NOTICE_FORMAT = "%n%n%s";
    private static final String MISCONFIGURED =
            "The server is misconfigured: %s Fix it in the MCP server's environment (for Claude Code, in "
            + "the registration: claude mcp add ... -e) and restart the session.";
    private static final String EMPTY = "";
    private static final String SERVER_VERSION_FIELD = "server_version";

    private final TaskRegistry tasks;
    private final Map<String, Tool> byName;
    private final ImageProvisioning image;
    private final String taskTool;
    private final String testCall;
    private final String guidelinesTool;
    private final McpConfig configuration;
    private final RuleCatalog catalog;
    private final AtomicBoolean noticeGiven = new AtomicBoolean();

    /**
     * Wires the tools to what actually runs Sheriff and reads its rules.
     *
     * @param config where the repository, image and catalog are
     * @param runner what runs Sheriff itself
     * @param catalog what reads the extracted rules
     */
    public Tools(McpConfig config, SheriffRunner runner, RuleCatalog catalog) {
        this(config, runner, catalog, config::rulesCatalog);
    }

    /**
     * Wires the tools, saying where the catalog the rules come from is, for
     * the one tool that hands it on to the agent's loop.
     *
     * @param config where the project, image and catalog are
     * @param runner what runs Sheriff itself
     * @param catalog what reads the extracted rules
     * @param catalogPath where that catalog is, once it is known
     */
    public Tools(McpConfig config, SheriffRunner runner, RuleCatalog catalog, Supplier<Path> catalogPath) {
        this(config, runner, catalog, catalogPath, projectTests(config));
    }

    /**
     * Wires the tools with a chosen way of running a component's tests, so
     * the verification can be tested without a build.
     *
     * @param config where the project, image and catalog are
     * @param runner what runs Sheriff itself
     * @param catalog what reads the extracted rules
     * @param catalogPath where that catalog is, once it is known
     * @param tests what runs a component's own tests
     */
    public Tools(McpConfig config, SheriffRunner runner, RuleCatalog catalog, Supplier<Path> catalogPath,
            Function<String, VerificationResult> tests) {
        this(config, runner, catalog, catalogPath, tests, ImageProvisioning.alreadyReady());
    }

    /**
     * Wires the tools to a server whose Sheriff image is being made sure of in
     * the background, so they can say so instead of failing while it is.
     *
     * @param config where the project, image and catalog are
     * @param runner what runs Sheriff itself
     * @param catalog what reads the extracted rules
     * @param catalogPath where that catalog is, once it is known
     * @param image the image, pulled or checked in the background
     */
    public Tools(McpConfig config, SheriffRunner runner, RuleCatalog catalog, Supplier<Path> catalogPath,
            ImageProvisioning image) {
        this(config, runner, catalog, catalogPath, projectTests(config), image);
    }

    /**
     * Wires everything, with a chosen way of running a component's tests.
     *
     * @param config where the project, image and catalog are
     * @param runner what runs Sheriff itself
     * @param catalog what reads the extracted rules
     * @param catalogPath where that catalog is, once it is known
     * @param tests what runs a component's own tests
     * @param image the image, pulled or checked in the background
     */
    public Tools(McpConfig config, SheriffRunner runner, RuleCatalog catalog, Supplier<Path> catalogPath,
            Function<String, VerificationResult> tests, ImageProvisioning image) {
        this(config, runner, catalog, catalogPath, tests, image, ToolCall.throughMcp());
    }

    /**
     * Every collaborator, with how the steps name the next call: as tool
     * calls for an MCP client, as commands for {@code --call}.
     *
     * @param config where the server runs and with what
     * @param runner what runs Sheriff
     * @param catalog the rules
     * @param catalogPath where the catalog file is, for the fix loop
     * @param tests what runs a component's own tests
     * @param image whether Sheriff's image is here yet
     * @param toolCall how a step names the next call
     */
    public Tools(McpConfig config, SheriffRunner runner, RuleCatalog catalog, Supplier<Path> catalogPath,
            Function<String, VerificationResult> tests, ImageProvisioning image, ToolCall toolCall) {
        this.image = image;
        this.tasks = new TaskRegistry(config.tasksDirectory(), () -> provenance(image));
        ToolContext context = new ToolContext(config, runner, catalog, catalogPath, tests, tasks, toolCall,
                new AnswerMemory(), new UntestedMethods(new SystemProcessRunner()));
        Map<String, Tool> definitions = new LinkedHashMap<>();
        for (Tool tool : List.of(new SheriffTestTool(context).definition(), new SheriffFixTool(context).definition(),
                new SheriffGuidelinesTool(context).definition(), new SheriffAutofixTool(context).definition(),
                new SheriffTaskTool(context).definition())) {
            definitions.put(tool.name(), tool);
        }
        this.byName = Collections.unmodifiableMap(definitions);
        this.taskTool = context.taskTool();
        this.testCall = context.testCall(ToolContext.EMPTY, ToolContext.EMPTY);
        this.guidelinesTool = context.guidelinesTool();
        this.configuration = config;
        this.catalog = catalog;
    }

    /**
     * What runs a component's own tests: its build tool's test command, from
     * the mount, with ten minutes to finish.
     *
     * @param config where the project is
     * @return the runner, one component at a time
     */
    public static Function<String, VerificationResult> projectTests(McpConfig config) {
        return component -> new ShellTestRunner(new SystemProcessRunner(),
                config.layout().verificationCommand(component), config.repository(), TEST_TIMEOUT).run();
    }

    /**
     * What a task runs with: this jar's version, and the image of Sheriff
     * with the digest it was pulled by.
     *
     * @param image the image the task's Sheriff runs from
     * @return those, as the task records them
     */
    private static Map<String, String> provenance(ImageProvisioning image) {
        Map<String, String> provenance = new LinkedHashMap<>();
        provenance.put(SERVER_VERSION_FIELD, ServerVersion.current());
        provenance.putAll(image.identity());
        return provenance;
    }

    /**
     * Every tool's definition, the shape {@code tools/list} answers with.
     *
     * @return those definitions, in a stable order
     */
    public List<Map<String, Object>> definitions() {
        List<Map<String, Object>> rendered = new ArrayList<>();
        for (Tool tool : byName.values()) {
            rendered.add(tool.definition());
        }
        return rendered;
    }

    /**
     * Lets background tasks finish, for when the client has gone.
     */
    @Override
    public void close() {
        tasks.close();
    }

    /**
     * Records every unfinished task as interrupted, for when the server is
     * being stopped rather than left.
     */
    public void interruptUnfinished() {
        tasks.interruptUnfinished();
    }

    /**
     * Runs one tool by name.
     *
     * @param name the tool to run
     * @param arguments its arguments, {@code null} treated as none
     * @return the text to answer with, and whether it is a failure
     */
    public ToolOutcome call(String name, Map<String, Object> arguments) {
        Tool tool = byName.get(name);
        if (tool == null) {
            return ToolOutcome.failure(String.format(NO_TOOL, name, String.join(NAME_SEPARATOR, byName.keySet())));
        }
        Map<String, Object> safeArguments = arguments == null ? Map.of() : arguments;
        Optional<String> blocked = blockerFor(name);
        if (blocked.isPresent()) {
            return ToolOutcome.failure(NextStep.afterFailure(blocked.get(), testCall));
        }
        try {
            return ToolOutcome.ok(NextStep.last(tool.handler().handle(safeArguments) + noticeOnce()));
        } catch (TaskFailure failure) {
            Task task = failure.task();
            return ToolOutcome.failure(NextStep.afterFailure(reasonFor((RuntimeException) failure.getCause())
                    + String.format(FAILED_TASK_FOOTER, task.id(), task.directory()), testCall));
        } catch (RuntimeException exception) {
            return ToolOutcome.failure(NextStep.afterFailure(reasonFor(exception), testCall));
        }
    }

    /**
     * Why a tool cannot run right now, when it cannot.
     *
     * <p>{@code sheriff_task} always can. {@code sheriff_guidelines} runs no
     * Sheriff, so a bad configuration does not stop it, and while the image
     * is being pulled it still answers when a catalog is already on disk.
     * The rest need Sheriff: a configuration problem, then the image.
     *
     * @param name the tool asked for
     * @return the reason to answer with instead, or empty
     */
    private Optional<String> blockerFor(String name) {
        if (name.equals(taskTool)) {
            return Optional.empty();
        }
        boolean runsSheriff = !name.equals(guidelinesTool);
        Optional<String> problem = configuration.problem();
        if (runsSheriff && problem.isPresent()) {
            return Optional.of(String.format(MISCONFIGURED, problem.get()));
        }
        if (!runsSheriff && catalog.availableNow()) {
            return Optional.empty();
        }
        return image.blocker();
    }

    /**
     * The note that a newer image is published, the first time there is one
     * to give: once is enough for the model to tell the user, and repeating it
     * on every answer would bury the answers.
     *
     * @return the note, on its own paragraph, or empty
     */
    private String noticeOnce() {
        Optional<String> notice = image.notice();
        if (notice.isEmpty() || !noticeGiven.compareAndSet(false, true)) {
            return EMPTY;
        }
        return String.format(NOTICE_FORMAT, notice.get());
    }

    /**
     * What to tell the client about a tool that threw: Sheriff not running,
     * a bad argument, or anything else by its type.
     *
     * @param exception what the tool threw
     * @return the text to answer with
     */
    private static String reasonFor(RuntimeException exception) {
        if (exception instanceof SheriffUnavailableException) {
            return String.format(ANALYZER_UNAVAILABLE, exception.getMessage());
        }
        if (exception instanceof IllegalArgumentException) {
            return exception.getMessage();
        }
        return exception.getClass().getSimpleName() + EXCEPTION_MESSAGE_SEPARATOR + exception.getMessage();
    }
}
