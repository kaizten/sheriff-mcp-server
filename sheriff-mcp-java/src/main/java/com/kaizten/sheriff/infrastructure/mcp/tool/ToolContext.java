package com.kaizten.sheriff.infrastructure.mcp.tool;

import com.kaizten.sheriff.domain.Rules;
import com.kaizten.sheriff.domain.port.RuleCatalog;
import com.kaizten.sheriff.domain.valueobject.VerificationResult;
import com.kaizten.sheriff.infrastructure.git.UntestedMethods;
import com.kaizten.sheriff.infrastructure.mcp.McpConfig;
import com.kaizten.sheriff.infrastructure.mcp.SheriffRunner;
import com.kaizten.sheriff.infrastructure.mcp.task.Task;
import com.kaizten.sheriff.infrastructure.mcp.task.TaskRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * What the five tools share: what runs Sheriff, what reads its rules, the
 * session's tasks, the tools' names, and how a call's component and profile
 * are worked out when it does not name them.
 *
 * <p>Each tool is its own class and takes this, so a tool reads as its
 * definition and its answer, and none of them repeats how an argument is
 * resolved or how a task's footer is written.
 *
 * @param config where the project, image and catalog are
 * @param runner what runs Sheriff itself
 * @param catalog what reads the extracted rules
 * @param catalogPath where that catalog is, once it is known
 * @param tests what runs a component's own tests
 * @param tasks every task of this session
 * @param toolCall how a step names the next call
 * @param answers the errors last listed in full, per component
 * @param untested what finds the new methods no changed test calls
 */
record ToolContext(McpConfig config, SheriffRunner runner, RuleCatalog catalog, Supplier<Path> catalogPath,
        Function<String, VerificationResult> tests, TaskRegistry tasks, ToolCall toolCall, AnswerMemory answers,
        UntestedMethods untested) {

    static final String COMPONENT_ARGUMENT = "component";
    static final String PROFILE_ARGUMENT = "profile";
    static final String REFERENCE_CODE_ARGUMENT = "reference_code";
    static final String EMPTY = "";
    static final String COMPONENT_HELP =
            "The module: one folder of the project, never a path inside it. Leave it out on the first call: "
            + "it is worked out from the working directory, and every answer names it in its step.";
    static final String PROFILE_HELP =
            "Leave it out: the project's own profile is used. Only for one the user names, such as "
            + "JAVA_HEXAGONAL.";
    private static final String TEST_SUFFIX = "test";
    private static final String FIX_SUFFIX = "fix";
    private static final String GUIDELINES_SUFFIX = "guidelines";
    private static final String AUTOFIX_SUFFIX = "autofix";
    private static final String TASK_SUFFIX = "task";
    private static final String NAME_SEPARATOR = "_";
    private static final String ARGUMENT_FORMAT = " %s=%s";
    private static final String TASK_FOOTER = "%n%nTask %s.";
    private static final String NO_COMPONENT =
            "No component given, and %s holds more than one, so there is no single answer. Pass "
            + "`component`, one of: %s.%n" + NextStep.MARK + " call this tool again with `component` set to "
            + "the one that holds the files you are changing.";
    private static final String NO_COMPONENTS =
            "No component given, and %s is not a project this server can read: it holds no folder "
            + "with Java, TypeScript, Vue, Perl or Python sources, or it is the home directory or the root. "
            + "Pass `component`, or set SHERIFF_REPO to the project; a client that does not start "
            + "servers in the project, such as Claude Desktop, needs it.%n" + NextStep.MARK + " if you "
            + "know the folder that holds the sources, call this tool again with `component` set to it; "
            + "otherwise tell the user there is nothing here Sheriff can check.";
    private static final String COMPONENT_SEPARATOR = ", ";
    private static final String SLASH = "/";
    private static final String BACKSLASH = "\\";
    private static final int FIRST_SEGMENT = 0;
    private static final String THIS_FOLDER = ".";
    private static final String FOLDER_ABOVE = "..";
    private static final Set<String> RELATIVE_NAMES = Set.of(THIS_FOLDER, FOLDER_ABOVE);
    private static final String NOT_A_COMPONENT =
            "'%s' is not a component: a component is one folder directly under %s, and Sheriff analyzes all of "
            + "it.%n" + NextStep.MARK + " call this tool again with component '%s'.";
    private static final String NOT_A_COMPONENT_LIST =
            "'%s' is not a component: a component is one folder directly under %s, one of: %s.%n"
            + NextStep.MARK + " call this tool again with `component` set to the one that holds the files you "
            + "are changing.";
    private static final String NOTHING_ANALYZED =
            "Nothing was analyzed: %s holds no %s sources, so the %s profile had no files to look "
            + "at. That is not the same as passing -- Sheriff reports both as zero errors. Sheriff checks "
            + "Java, TypeScript, Vue, Perl and Python; a project in another language has nothing it can analyze. "
            + "Otherwise, check the component and the profile.%n" + NextStep.MARK + " none: tell the user "
            + "nothing was analyzed and why, and run the project's own tests if it has any.";

    /**
     * The command that runs a component's own tests.
     *
     * @param component the component
     * @return the command, or the empty string when it has no tests
     */
    String testCommand(String component) {
        return config().layout().hasTests(component) ? config().layout().testCommandForModel(component) : EMPTY;
    }

    /**
     * The step once a component is clean: a test for each new method no
     * changed test calls, else the project's tests.
     *
     * @param component the component
     * @param profile the profile the caller asked for, or empty
     * @return the step, as the answer's last line
     */
    String cleanStep(String component, String profile) {
        List<String> missing = untested.in(config.repository(), component);
        return missing.isEmpty()
                ? NextStep.afterClean(testCommand(component), testCall(component, profile))
                : NextStep.afterUntested(missing, testCommand(component), testCall(component, profile));
    }

    /**
     * The call to the repair tool for a component, as a step names it.
     *
     * @param component the component
     * @param profile the profile the caller asked for, or empty
     * @return the call, written to be made as it stands
     */
    String fixCall(String component, String profile) {
        return toolCall.phrase(fixTool(), component, profile);
    }

    /**
     * The call to the analysis tool for a component, as a step names it.
     *
     * @param component the component
     * @param profile the profile the caller asked for, or empty
     * @return the call, written to be made as it stands
     */
    String testCall(String component, String profile) {
        return toolCall.phrase(testTool(), component, profile);
    }

    /**
     * The name of {@code sheriff_test}, under the configured prefix.
     *
     * @return that name
     */
    String testTool() {
        return named(TEST_SUFFIX);
    }

    /**
     * The name of {@code sheriff_fix}, under the configured prefix.
     *
     * @return that name
     */
    String fixTool() {
        return named(FIX_SUFFIX);
    }

    /**
     * The name of {@code sheriff_guidelines}, under the configured prefix.
     *
     * @return that name
     */
    String guidelinesTool() {
        return named(GUIDELINES_SUFFIX);
    }

    /**
     * The name of {@code sheriff_autofix}, under the configured prefix.
     *
     * @return that name
     */
    String autofixTool() {
        return named(AUTOFIX_SUFFIX);
    }

    /**
     * The name of {@code sheriff_task}, under the configured prefix.
     *
     * @return that name
     */
    String taskTool() {
        return named(TASK_SUFFIX);
    }

    /**
     * A tool's name: the configured prefix and the tool's own suffix.
     *
     * @param suffix what the tool does
     * @return that name
     */
    private String named(String suffix) {
        return config.toolPrefix() + NAME_SEPARATOR + suffix;
    }

    /**
     * The component a call names, or the one the project makes obvious.
     *
     * @param arguments the call's arguments
     * @return that component
     * @throws IllegalArgumentException when there is no single answer, naming
     *     the components there are to choose from
     */
    String componentArgument(Map<String, Object> arguments) {
        String given = textArgument(arguments, COMPONENT_ARGUMENT);
        if (!given.isEmpty() && !isComponent(given)) {
            throw new IllegalArgumentException(notAComponent(given));
        }
        String component = given.isEmpty() ? config.defaultComponent() : given;
        if (!component.isEmpty()) {
            return component;
        }
        List<String> components = config.layout().components();
        if (components.isEmpty()) {
            throw new IllegalArgumentException(String.format(NO_COMPONENTS, config.repository()));
        }
        throw new IllegalArgumentException(String.format(
                NO_COMPONENT, config.repository(), String.join(COMPONENT_SEPARATOR, components)));
    }

    /**
     * The profile a call names, or the one the component's sources call for.
     *
     * @param arguments the call's arguments
     * @param component the component the call is about, possibly empty
     * @return that profile
     */
    String profileArgument(Map<String, Object> arguments, String component) {
        String given = textArgument(arguments, PROFILE_ARGUMENT);
        return given.isEmpty() ? config.profileFor(component) : Rules.withBaseProfiles(given);
    }

    /**
     * Whether a name is a component Sheriff can analyze: one folder directly
     * under the directory it mounts.
     *
     * <p>Not {@code .} nor {@code ..}: both are folders, Sheriff refuses
     * them with "Invalid context", and that read to the model as a check
     * that could not run rather than a name to correct; {@code ..} also sent
     * {@code sheriff_autofix} to make its branch in whatever repository
     * holds the folder above the project.
     *
     * @param name the name given
     * @return {@code true} for such a folder
     */
    private boolean isComponent(String name) {
        return !name.contains(SLASH) && !name.contains(BACKSLASH) && !RELATIVE_NAMES.contains(name)
                && Files.isDirectory(config.repository().resolve(name));
    }

    /**
     * What to answer a component that is not one, with the one to call
     * instead.
     *
     * <p>A model passed {@code spring-petclinic/src/main/java/.../owner},
     * the folder it was working in, and got Sheriff's own "Invalid context:
     * 'file:/data'", with nothing it could do next.
     *
     * @param given the name given
     * @return the answer, ending with a step that names the component
     */
    private String notAComponent(String given) {
        String first = given.replace(BACKSLASH, SLASH).split(SLASH)[FIRST_SEGMENT];
        String suggested = isComponent(first) ? first : config.defaultComponent();
        if (suggested.isEmpty()) {
            return String.format(NOT_A_COMPONENT_LIST, given, config.repository(),
                    String.join(COMPONENT_SEPARATOR, config.layout().components()));
        }
        return String.format(NOT_A_COMPONENT, given, config.repository(), suggested);
    }

    /**
     * One argument as text, trimmed, empty when it was not given.
     *
     * <p>A flag or a number is taken as it reads, {@code true} or
     * {@code 5}: the schema declares them that way, and a client that sent
     * {@code "verify": true} used to have it dropped without a word, the
     * tests never run.
     *
     * @param arguments the call's arguments
     * @param key the argument to read
     * @return that value
     */
    static String textArgument(Map<String, Object> arguments, String key) {
        Object value = arguments.get(key);
        if (value instanceof String text) {
            return text.strip();
        }
        if (value instanceof Boolean flag) {
            return flag.toString();
        }
        if (value instanceof Number number) {
            double asWritten = number.doubleValue();
            boolean whole = !Double.isInfinite(asWritten) && asWritten == Math.rint(asWritten);
            return whole ? Long.toString(number.longValue()) : number.toString();
        }
        return EMPTY;
    }

    /**
     * The command a task records: the tool and the arguments it resolved to,
     * leaving out the ones that were empty.
     *
     * @param tool the tool's name
     * @param arguments the resolved arguments, in order
     * @return that line
     */
    static String commandLine(String tool, Map<String, String> arguments) {
        StringBuilder line = new StringBuilder(tool);
        for (Map.Entry<String, String> argument : arguments.entrySet()) {
            if (!argument.getValue().isEmpty()) {
                line.append(String.format(ARGUMENT_FORMAT, argument.getKey(), argument.getValue()));
            }
        }
        return line.toString();
    }

    /**
     * Why zero errors was not a pass: the component holds nothing the profile
     * reads, which Sheriff reports exactly like a component that complies.
     *
     * @param component the component that was analyzed
     * @param profile the profile it was analyzed under
     * @return the message to fail with
     */
    static String nothingAnalyzed(String component, String profile) {
        return String.format(NOTHING_ANALYZED, component, Rules.languageForProfile(profile), profile);
    }

    /**
     * A task's result, with its id.
     *
     * <p>Not with its folder: named in every answer, it sent a Codex session
     * to read Sheriff's raw JSON there, 100 KB, rather than do the step.
     * {@code sheriff_task} gives the folder to whoever asks for it.
     *
     * @param task the finished task
     * @return the text to answer with
     */
    static String answered(Task task) {
        return task.result() + String.format(TASK_FOOTER, task.id());
    }
}
