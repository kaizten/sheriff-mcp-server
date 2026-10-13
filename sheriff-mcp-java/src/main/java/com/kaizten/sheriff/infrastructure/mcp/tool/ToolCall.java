package com.kaizten.sheriff.infrastructure.mcp.tool;

import com.kaizten.sheriff.infrastructure.process.Platform;
import java.nio.file.Path;

/**
 * How a step names the next call, so that a model can make it exactly as
 * written: as a tool call when the answer came through the MCP, as a command
 * when it came from {@code --call}, the same tools without an MCP client.
 *
 * <p>The component is always named. "Call sheriff_fix with no arguments"
 * failed in a repository holding several modules, where no argument means no
 * component, right after the model had analyzed one by name.
 */
public final class ToolCall {

    private static final String TOOL_CALL = "call the %s tool with component '%s'";
    private static final String TOOL_CALL_ALONE = "call the %s tool";
    private static final String WITH_PROFILE = " and profile '%s'";
    private static final String COMMAND = "run `cd %s && java -jar %s --call %s%s`";
    private static final String WINDOWS_COMMAND = "run `cd %s; java -jar %s --call %s%s`";
    private static final String COMPONENT_ARGUMENT = " component=%s";
    private static final String PROFILE_ARGUMENT = " profile=%s";
    private static final String QUOTE = "'";
    private static final String ESCAPED_QUOTE = "'\\''";
    private static final String WINDOWS_QUOTE = "\"";
    private static final String NOTHING = "";
    private static final char BACKSLASH = '\\';
    private static final char SLASH = '/';

    private final Path directory;
    private final Path jar;
    private final boolean windows;

    /**
     * Wires a way of naming calls.
     *
     * @param directory where a command runs, or {@code null} for tool calls
     * @param jar this jar, or {@code null} for tool calls
     * @param windows whether the command is run on Windows
     */
    private ToolCall(Path directory, Path jar, boolean windows) {
        this.directory = directory;
        this.jar = jar;
        this.windows = windows;
    }

    /**
     * Calls named as MCP tool calls.
     *
     * @return that way of naming them
     */
    public static ToolCall throughMcp() {
        return new ToolCall(null, null, false);
    }

    /**
     * Calls named as the commands that make them without an MCP client.
     *
     * @param directory the directory the first command was run in
     * @param jar this jar
     * @return that way of naming them
     */
    public static ToolCall throughCommandLine(Path directory, Path jar) {
        return throughCommandLine(directory, jar, Platform.windows());
    }

    /**
     * Calls named as commands, for a given platform.
     *
     * <p>On Windows a model's shell is Git Bash under Claude Code and
     * PowerShell under Codex, and Windows PowerShell does not know
     * {@code &&}: the command there is joined with {@code ;}, double quoted
     * and with forward slashes, which all of them read the same.
     *
     * @param directory the directory the first command was run in
     * @param jar this jar
     * @param windows whether the command is run on Windows
     * @return that way of naming them
     */
    static ToolCall throughCommandLine(Path directory, Path jar, boolean windows) {
        return new ToolCall(directory, jar, windows);
    }

    /**
     * One call, written so it can be made as it stands.
     *
     * @param tool the tool's name
     * @param component the component, or empty when there is none to name
     * @param profile the profile asked for, or empty for the one worked out
     * @return the call, as an instruction
     */
    public String phrase(String tool, String component, String profile) {
        if (directory == null) {
            if (component.isEmpty()) {
                return String.format(TOOL_CALL_ALONE, tool);
            }
            String withProfile = profile.isEmpty() ? NOTHING : String.format(WITH_PROFILE, profile);
            return String.format(TOOL_CALL, tool, component) + withProfile;
        }
        String arguments = (component.isEmpty() ? NOTHING : String.format(COMPONENT_ARGUMENT, quoted(component)))
                + (profile.isEmpty() ? NOTHING : String.format(PROFILE_ARGUMENT, quoted(profile)));
        return String.format(windows ? WINDOWS_COMMAND : COMMAND, quoted(directory.toString()),
                quoted(jar.toString()), tool, arguments);
    }

    /**
     * A word or a path quoted for the shell the command runs in.
     *
     * @param text what to quote
     * @return it, quoted
     */
    private String quoted(String text) {
        if (windows) {
            return WINDOWS_QUOTE + text.replace(BACKSLASH, SLASH) + WINDOWS_QUOTE;
        }
        return QUOTE + text.replace(QUOTE, ESCAPED_QUOTE) + QUOTE;
    }
}
