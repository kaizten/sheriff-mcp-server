package com.kaizten.sheriff.infrastructure.mcp.tool;

import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.infrastructure.mcp.FindingsRenderer;
import com.kaizten.sheriff.infrastructure.mcp.task.Task;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code sheriff_test}: runs Sheriff on a component and reports every error,
 * grouped by file, each with the rule it breaks and how to solve it.
 *
 * <p>Zero errors is only a pass when the component holds sources of the
 * profile's language; otherwise nothing was analyzed, and Sheriff reports
 * both the same way.
 */
final class SheriffTestTool {

    private static final String DESCRIPTION =
            "Check a component against the project's code quality rules: how many errors each "
            + "file and each rule has, and the next step. sheriff_fix lists them one by one. Run "
            + "this before changing code in a component and at the end.";
    private static final String TITLE = "Check code standards";
    private static final ToolAnnotations ANNOTATIONS = ToolAnnotations.readingOnly(TITLE);
    private static final String PASSES = "%s passes the %s analysis: 0 errors.";
    private static final String WITH_WARNINGS = " (%d warning(s), which do not have to be fixed.)";
    private static final String HEADER = "%s has %d error(s) under %s%s.";
    private static final String AND_WARNINGS = " and %d warning(s)";
    private static final int NO_ERRORS = 0;

    private final ToolContext context;

    /**
     * Wires the tool to what it shares with the others.
     *
     * @param context what runs Sheriff, the catalog and the tasks
     */
    SheriffTestTool(ToolContext context) {
        this.context = context;
    }

    /**
     * The tool's definition, bound to its handler.
     *
     * @return that tool
     */
    Tool definition() {
        Map<String, Object> schema = Schemas.objectSchema(Map.of(
                ToolContext.COMPONENT_ARGUMENT, Schemas.stringProperty(ToolContext.COMPONENT_HELP),
                ToolContext.PROFILE_ARGUMENT, Schemas.stringProperty(ToolContext.PROFILE_HELP)));
        return new Tool(context.testTool(), DESCRIPTION, schema, ANNOTATIONS, this::handle);
    }

    /**
     * Runs Sheriff on a component and reports every error, as a task.
     *
     * @param arguments the call's arguments
     * @return the text to answer with
     */
    private String handle(Map<String, Object> arguments) {
        String component = context.componentArgument(arguments);
        String profile = context.profileArgument(arguments, component);
        Map<String, String> resolved = new LinkedHashMap<>();
        resolved.put(ToolContext.COMPONENT_ARGUMENT, component);
        resolved.put(ToolContext.PROFILE_ARGUMENT, profile);
        return ToolContext.answered(context.tasks().run(ToolContext.commandLine(context.testTool(), resolved),
                task -> test(task, component, profile,
                        ToolContext.textArgument(arguments, ToolContext.PROFILE_ARGUMENT))));
    }

    /**
     * Runs Sheriff on a component and reports every error.
     *
     * @param task the task this runs as, whose folder gets Sheriff's JSON
     * @param component the component to analyze
     * @param profile the profile to analyze it under
     * @param asked the profile the caller asked for, empty when it was worked
     *     out, so that a step names it only then
     * @return the text to answer with
     */
    private String test(Task task, String component, String profile, String asked) {
        AnalysisResult analysis = context.runner().exportingTo(task.directory()).test(component, profile);
        if (analysis.total() == NO_ERRORS) {
            if (!context.config().layout().holdsSourcesFor(component, profile)) {
                throw new IllegalArgumentException(ToolContext.nothingAnalyzed(component, profile));
            }
            String body = String.format(PASSES, component, profile);
            if (!analysis.warnings().isEmpty()) {
                body += String.format(WITH_WARNINGS, analysis.warnings().size());
            }
            return body + context.cleanStep(component, asked);
        }
        String warningsSuffix = analysis.warnings().isEmpty()
                ? ToolContext.EMPTY
                : String.format(AND_WARNINGS, analysis.warnings().size());
        String header = String.format(HEADER, component, analysis.total(), profile, warningsSuffix);
        FindingsRenderer renderer = new FindingsRenderer(context.catalog().allRules());
        return header + renderer.summary(analysis.errors())
                + NextStep.afterAnalysis(context.fixCall(component, asked));
    }
}
