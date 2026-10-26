package com.kaizten.sheriff.infrastructure.mcp.tool;

import com.kaizten.sheriff.infrastructure.config.Settings;
import com.kaizten.sheriff.infrastructure.mcp.SettingsPage;
import com.kaizten.sheriff.infrastructure.mcp.SettingsPageOpener;
import java.io.IOException;
import java.io.UncheckedIOException;
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
 *
 * <p>With {@code page}, it opens the settings page ({@link SettingsPage}) and
 * answers with its address, for a user who would rather change them there:
 * one page a session, opened again when it has closed.
 */
final class SheriffConfigTool implements AutoCloseable {

    private static final String DESCRIPTION =
            "Sheriff's settings: with no arguments, the files they are read from, each setting with its value "
            + "and where it comes from, and what is wrong in them; with setting and value, changes one in the "
            + "user's settings file (or the project's, with project true); with page true, opens a page on this "
            + "machine where the user sees and changes every setting, and answers with its address. Use it when the "
            + "user asks how Sheriff is configured, where its settings are, or to change how Sheriff or its hooks "
            + "behave; page only when the user asks for a page or an interface to change them in.";
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
    private static final String PAGE_ARGUMENT = "page";
    private static final String PAGE_HELP = "true to open the settings page in the user's browser on this machine "
            + "and answer with its address, instead of listing them.";
    private static final String PAGE_OPEN = "Sheriff's settings page is at %s%n%nGive the user this address to open "
            + "in a browser on this machine. It lists every setting under its section, what it takes and where "
            + "its value comes from, and changes one in the user's file or the project's. It closes when the user "
            + "presses Done, after %d minutes unused, or when this session ends. The tools take a change when the "
            + "next session starts; the hooks, from their next run.";
    private static final String YES = "true";
    private static final String HOW_TO_CHANGE = "%n%nTo change one, call %s with setting and value, and project "
            + "true for this project only; an empty value puts the default back.";
    private static final String NO_VALUE = "Give the value to set %s to, or an empty one to put its default back.";

    private final ToolContext context;
    private final SettingsPageOpener opener;
    private SettingsPage page;

    /**
     * Wires the tool to what it shares with the others.
     *
     * @param context where the project and its environment are
     */
    SheriffConfigTool(ToolContext context) {
        this(context, SettingsPage::open);
    }

    /**
     * Wires the tool to what it shares with the others, and to what opens
     * its settings page.
     *
     * @param context where the project and its environment are
     * @param opener what opens the settings page
     */
    SheriffConfigTool(ToolContext context, SettingsPageOpener opener) {
        this.context = context;
        this.opener = opener;
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
                Map.entry(PROJECT_ARGUMENT, Schemas.booleanProperty(PROJECT_HELP)),
                Map.entry(PAGE_ARGUMENT, Schemas.booleanProperty(PAGE_HELP))));
        return new Tool(context.configTool(), DESCRIPTION, schema, ANNOTATIONS, this::handle);
    }

    /**
     * Lists the settings, or changes one.
     *
     * @param arguments the call's arguments
     * @return the text to answer with
     */
    private String handle(Map<String, Object> arguments) {
        if (YES.equalsIgnoreCase(ToolContext.textArgument(arguments, PAGE_ARGUMENT))) {
            return String.format(PAGE_OPEN, openPage().address(), SettingsPage.IDLE_LIMIT.toMinutes());
        }
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

    /**
     * The session's settings page, opened when there is none or it has
     * closed.
     *
     * @return that page, serving
     * @throws UncheckedIOException when no port can be opened
     */
    private synchronized SettingsPage openPage() {
        if (page == null || !page.isOpen()) {
            try {
                page = opener.open(context.config().givenEnvironment(), context.config().settingsProject(),
                        SettingsPage.IDLE_LIMIT);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
        return page;
    }

    /**
     * Closes the settings page, when one is open.
     */
    @Override
    public synchronized void close() {
        if (page != null) {
            page.close();
        }
    }
}
