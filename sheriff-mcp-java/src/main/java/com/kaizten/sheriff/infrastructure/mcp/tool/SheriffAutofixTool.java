package com.kaizten.sheriff.infrastructure.mcp.tool;

import com.kaizten.sheriff.domain.valueobject.RunSummary;
import com.kaizten.sheriff.infrastructure.config.Composition;
import com.kaizten.sheriff.infrastructure.config.Configuration;
import com.kaizten.sheriff.infrastructure.mcp.McpConfig;
import com.kaizten.sheriff.infrastructure.mcp.task.Task;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code sheriff_autofix}: the agent's whole repair loop, run in the
 * background as a task.
 *
 * <p>The one tool that is not a wrapper over one {@code docker run}: it builds
 * the same {@link com.kaizten.sheriff.application.service.FixLoop} the CLI
 * does, AI backend, scope check, git commits and all, through
 * {@link Composition}, the composition root the agent's own entry point uses.
 * That is why the agent lives behind this server rather than beside it.
 */
final class SheriffAutofixTool {

    private static final String DESCRIPTION =
            "Only when the user asks for it: hands a component to an autonomous repair loop that "
            + "edits it with another model on a branch of its own, commits each pass and spends "
            + "tokens, in the background. Otherwise errors are fixed here, with sheriff_fix and by hand.";
    private static final String TITLE = "Repair a component with a model, on its own branch";
    private static final ToolAnnotations ANNOTATIONS = ToolAnnotations.committingThroughAModel(TITLE);
    private static final String MAX_ITERATIONS_ARGUMENT = "max_iterations";
    private static final String MAX_ITERATIONS_HELP =
            "How many passes the loop may take, as a whole number. Left out, it is worked out.";
    private static final String WHOLE_NUMBER = "[1-9][0-9]{0,8}";
    private static final String NOT_A_CAP =
            "max_iterations must be a whole number of at least 1, but it is '%s'. Nothing was started.%n"
            + NextStep.MARK + " call this tool again with max_iterations left out, or set to a whole number.";
    private static final String ACCEPTED =
            "Task %s accepted: %s. It runs in the background and takes minutes: it edits files, "
            + "commits on its own branch and may spend tokens.%n" + NextStep.MARK + " tell the user it is "
            + "running, then call %s with id='%s' until its state is completed or failed.";
    private static final String SHERIFF_TEST_TYPE_VARIABLE = "SHERIFF_TEST_TYPE";
    private static final String RULES_CATALOG_VARIABLE = "RULES_CATALOG";
    private static final String MAX_ITERATIONS_VARIABLE = "SHERIFF_MAX_ITERATIONS";
    private static final String VERIFICATION_VARIABLE = "VERIFICATION_TEST_CMD";
    private static final String PROMPT_LOG_VARIABLE = "PROMPT_LOG_DIR";
    private static final String EXPORT_DIRECTORY_VARIABLE = "SHERIFF_EXPORT_DIR";
    private static final String LOGS_DIRECTORY = "logs";
    private static final String VERIFIED_WITH = "%nThe project's own tests were run with: %s";
    private static final String SUMMARY =
            "Ran the fix loop on %s under %s via AI_BACKEND '%s': %s (%s). %d iteration(s) used%s. "
            + "Repair pass %s.";
    private static final String GOAL_REACHED = "goal reached";
    private static final String GOAL_NOT_REACHED = "goal NOT reached";
    private static final String OF_CAP = " of %d";
    private static final String REPAIR_USED = "used";
    private static final String REPAIR_NOT_USED = "not used";
    private static final String PARKED_FILES_LINE = "%nFiles parked without progress: %s";
    private static final String FILE_SEPARATOR = ", ";
    private static final String WORKING_BRANCH_LINE =
            "%nThe repository is now on branch '%s', with each pass as its own commit; review it there, "
            + "then merge or delete it.";
    private static final String TESTS_PASSED_LINE = "%nThe project's own tests pass on what the run left behind.";
    private static final String TESTS_FAILED_LINE =
            "%nThe project's own tests FAIL on what the run left behind: review the branch before using it.";
    private static final String TESTS_NOT_RUN_LINE = "%nThe project's own tests were not run.";
    private static final String NO_TESTS_LINE =
            "%nNo build tool with tests was found in the component, so no tests were run: only Sheriff checked "
            + "this code.";

    private final ToolContext context;

    /**
     * Wires the tool to what it shares with the others.
     *
     * @param context the configuration, the catalog's location and the tasks
     */
    SheriffAutofixTool(ToolContext context) {
        this.context = context;
    }

    /**
     * The tool's definition, bound to its handler.
     *
     * @return that tool
     */
    Tool definition() {
        Map<String, Object> schema = Schemas.objectSchema(List.of(
                Map.entry(ToolContext.COMPONENT_ARGUMENT, Schemas.stringProperty(ToolContext.COMPONENT_HELP)),
                Map.entry(ToolContext.PROFILE_ARGUMENT, Schemas.stringProperty(ToolContext.PROFILE_HELP)),
                Map.entry(MAX_ITERATIONS_ARGUMENT, Schemas.positiveIntegerProperty(MAX_ITERATIONS_HELP))));
        return new Tool(context.autofixTool(), DESCRIPTION, schema, ANNOTATIONS, this::handle);
    }

    /**
     * Queues the repair loop as a background task and answers with its id.
     *
     * <p>A cap that is not a whole number is refused here, before anything
     * is accepted: the loop refused it too, but only once it ran in the
     * background, so the client was told the task had started and learnt
     * otherwise from {@code sheriff_task}.
     *
     * @param arguments the call's arguments
     * @return the text to answer with
     */
    private String handle(Map<String, Object> arguments) {
        String component = context.componentArgument(arguments);
        String profile = context.profileArgument(arguments, component);
        String maxIterations = ToolContext.textArgument(arguments, MAX_ITERATIONS_ARGUMENT);
        if (!maxIterations.isEmpty() && !maxIterations.matches(WHOLE_NUMBER)) {
            throw new IllegalArgumentException(String.format(NOT_A_CAP, maxIterations));
        }
        Map<String, String> resolved = new LinkedHashMap<>();
        resolved.put(ToolContext.COMPONENT_ARGUMENT, component);
        resolved.put(ToolContext.PROFILE_ARGUMENT, profile);
        resolved.put(MAX_ITERATIONS_ARGUMENT, maxIterations);
        String command = ToolContext.commandLine(context.autofixTool(), resolved);
        Task task = context.tasks().submit(command, accepted -> autofix(accepted, component, profile, maxIterations));
        return String.format(ACCEPTED, task.id(), command, context.taskTool(), task.id());
    }

    /**
     * The repair loop itself, run in the background as a task.
     *
     * @param task the task this runs as; the loop exports Sheriff's JSON into
     *     its folder
     * @param component the component to repair
     * @param profile the profile to repair it under
     * @param maxIterations the cap on passes, empty to let the loop size it
     * @return the text the task finishes with
     */
    private String autofix(Task task, String component, String profile, String maxIterations) {
        McpConfig config = context.config();
        Map<String, String> inherited = System.getenv();
        boolean tested = inherited.containsKey(VERIFICATION_VARIABLE) || config.layout().hasTests(component);
        Map<String, String> environment = config.agentEnvironment(inherited, component);
        environment.put(SHERIFF_TEST_TYPE_VARIABLE, profile);
        environment.put(EXPORT_DIRECTORY_VARIABLE, task.directory().toString());
        Path catalogFile = context.catalogPath().get();
        environment.put(RULES_CATALOG_VARIABLE, catalogFile.toString());
        environment.putIfAbsent(PROMPT_LOG_VARIABLE, config.cachedCatalog().resolveSibling(LOGS_DIRECTORY).toString());
        if (!maxIterations.isEmpty()) {
            environment.put(MAX_ITERATIONS_VARIABLE, maxIterations);
        }
        Path agentDirectory = catalogFile.toAbsolutePath().getParent();
        Configuration configuration = new Configuration(environment, agentDirectory);
        RunSummary summary = Composition.real(configuration).fixLoop().execute();
        return renderSummary(component, profile, configuration.backend(), summary, tested)
                + String.format(VERIFIED_WITH, configuration.verificationCommand());
    }

    /**
     * The text a finished run answers with.
     *
     * @param component the component that was repaired
     * @param profile the profile it was repaired under
     * @param backend which {@code AI_BACKEND} did the writing
     * @param summary what the loop reports about the run
     * @return that text
     */
    static String renderSummary(String component, String profile, String backend, RunSummary summary) {
        return renderSummary(component, profile, backend, summary, true);
    }

    /**
     * The text a finished run answers with, saying plainly when the project
     * had no tests to run, rather than that they passed.
     *
     * @param component the component that was repaired
     * @param profile the profile it was repaired under
     * @param backend which {@code AI_BACKEND} did the writing
     * @param summary what the loop reports about the run
     * @param tested whether the component has tests that were run
     * @return that text
     */
    static String renderSummary(String component, String profile, String backend, RunSummary summary,
            boolean tested) {
        String capSuffix = summary.maxIterations() == null
                ? ToolContext.EMPTY
                : String.format(OF_CAP, summary.maxIterations());
        String repairNote = summary.repairPassUsed() ? REPAIR_USED : REPAIR_NOT_USED;
        StringBuilder body = new StringBuilder(String.format(
                SUMMARY, component, profile, backend, summary.ok() ? GOAL_REACHED : GOAL_NOT_REACHED,
                summary.stoppedReason().wireName(), summary.iterationsUsed(), capSuffix, repairNote));
        if (!summary.parkedFiles().isEmpty()) {
            body.append(String.format(PARKED_FILES_LINE, String.join(FILE_SEPARATOR, summary.parkedFiles())));
        }
        if (!summary.workingBranch().isEmpty()) {
            body.append(String.format(WORKING_BRANCH_LINE, summary.workingBranch()));
        }
        body.append(String.format(tested ? testsLine(summary.testsPassed()) : NO_TESTS_LINE));
        return body.toString();
    }

    /**
     * The line saying how the project's tests stand after a run.
     *
     * @param testsPassed the verdict, {@code null} when they were not run
     * @return that line, as a format with no arguments
     */
    private static String testsLine(Boolean testsPassed) {
        if (testsPassed == null) {
            return TESTS_NOT_RUN_LINE;
        }
        return testsPassed ? TESTS_PASSED_LINE : TESTS_FAILED_LINE;
    }
}
