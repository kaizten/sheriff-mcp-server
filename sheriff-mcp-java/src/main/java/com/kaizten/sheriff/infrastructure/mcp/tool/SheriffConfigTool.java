package com.kaizten.sheriff.infrastructure.mcp.tool;

import com.kaizten.sheriff.infrastructure.config.Settings;
import java.util.List;
import java.util.Map;

/**
 * {@code sheriff_config}: the settings a user can change, with the value each
 * one has and where it comes from, and a way to change one by asking the
 * assistant, as "keep Sheriff's image at this digest" or "let a stop with
 * nothing changed through".
 *
 * <p>The values are those {@link Settings} reads: an environment variable,
 * then the project's {@code .sheriff.properties}, then the user's own file,
 * then the default. A change goes into the user's file, or the project's when
 * asked, and is checked first, so a value the tools could not use is refused
 * here rather than read as the default later.
 */
final class SheriffConfigTool {

    private static final String DESCRIPTION =
            "Sheriff's settings: with no arguments, the files they are read from, each setting with its value "
            + "and where it comes from, and what is wrong in them; with setting and value, changes one in the "
            + "user's settings file (or the project's, with project true). Use it when the user asks how Sheriff "
            + "is configured, where its settings are, or to change how Sheriff or its hooks behave.";
    private static final String TITLE = "Read or change Sheriff's settings";
    private static final ToolAnnotations ANNOTATIONS = ToolAnnotations.changingSettings(TITLE);
    private static final String SETTING_ARGUMENT = "setting";
    private static final String SETTING_HELP = "The setting to change, as its listing names it, such as "
            + "SHERIFF_IMAGE. Leave it out to list them all.";
    private static final String VALUE_ARGUMENT = "value";
    private static final String VALUE_HELP = "Its new value; an empty one puts its default back.";
    private static final String PROJECT_ARGUMENT = "project";
    private static final String PROJECT_HELP = "true to change it in the project's .sheriff.properties, for "
            + "everyone who opens the project, rather than in the user's own file.";
    private static final String YES = "true";
    private static final String HOW_TO_CHANGE = "%n%nTo change one, call %s with setting and value, and project "
            + "true for this project only; an empty value puts the default back.";
    private static final String NO_VALUE = "Give the value to set %s to, or an empty one to put its default back.";

    private final ToolContext context;

    /**
     * Wires the tool to what it shares with the others.
     *
     * @param context where the project and its environment are
     */
    SheriffConfigTool(ToolContext context) {
        this.context = context;
    }

    /**
     * The tool's definition, bound to its handler.
     *
     * @return that tool
     */
    Tool definition() {
        Map<String, Object> schema = Schemas.objectSchema(List.of(
                Map.entry(SETTING_ARGUMENT, Schemas.stringProperty(SETTING_HELP)),
                Map.entry(VALUE_ARGUMENT, Schemas.stringProperty(VALUE_HELP)),
                Map.entry(PROJECT_ARGUMENT, Schemas.booleanProperty(PROJECT_HELP))));
        return new Tool(context.configTool(), DESCRIPTION, schema, ANNOTATIONS, this::handle);
    }

    /**
     * Lists the settings, or changes one.
     *
     * @param arguments the call's arguments
     * @return the text to answer with
     */
    private String handle(Map<String, Object> arguments) {
        String setting = ToolContext.textArgument(arguments, SETTING_ARGUMENT);
        if (setting.isEmpty()) {
            return Settings.report(context.config().givenEnvironment(), context.config().settingsProject())
                    + String.format(HOW_TO_CHANGE, context.configTool());
        }
        if (!arguments.containsKey(VALUE_ARGUMENT)) {
            throw new IllegalArgumentException(String.format(NO_VALUE, setting));
        }
        boolean project = YES.equalsIgnoreCase(ToolContext.textArgument(arguments, PROJECT_ARGUMENT));
        return Settings.change(context.config().givenEnvironment(), context.config().settingsProject(), setting,
                ToolContext.textArgument(arguments, VALUE_ARGUMENT), project);
    }
}
