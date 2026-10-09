package com.kaizten.sheriff.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Sets Antigravity's command line, {@code agy}, up for this jar, for every
 * project it opens, or takes it out again: the MCP server, and permission for
 * the tools that neither commit nor spend tokens.
 *
 * <p>{@code agy} is an MCP client. With the server in
 * {@code ~/.gemini/config/mcp_config.json} it loads the five tools and the
 * server's instructions, and asked on 7 October to fix a small Maven
 * project's standards, {@code agy} 1.3.1 went {@code sheriff_test},
 * {@code sheriff_fix}, the rest by hand, {@code sheriff_fix},
 * {@code sheriff_test} and the project's tests, ending at 0 errors.
 * {@code agy mcp add} writes the same entry; it is written here, as Codex's
 * is, so that one command sets Antigravity up and one takes it out.
 *
 * <p>{@code agy} asks before every tool, and in headless mode it refuses
 * whatever it cannot ask about, an MCP tool included, unless
 * {@code permissions.allow} in {@code ~/.gemini/antigravity-cli/settings.json}
 * names it, as {@code mcp(sheriff/sheriff_test)}. These are the four that
 * Claude Code runs without asking; {@code sheriff_autofix} still asks.
 *
 * <p>Sheriff's hooks are not wired into Antigravity: nothing stops a turn
 * there from ending with errors left.
 */
public final class AntigravityInstaller {

    private static final String GEMINI_DIRECTORY = ".gemini";
    private static final String CONFIG_DIRECTORY = "config";
    private static final String SERVERS_FILE = "mcp_config.json";
    private static final String CLI_DIRECTORY = "antigravity-cli";
    private static final String SETTINGS_FILE = "settings.json";
    private static final String SERVERS = "mcpServers";
    private static final String SERVER_NAME = "sheriff";
    private static final String COMMAND = "command";
    private static final String JAVA = "java";
    private static final String ARGUMENTS = "args";
    private static final String JAR_OPTION = "-jar";
    private static final String DISABLED = "disabled";
    private static final String ENVIRONMENT = "env";
    private static final String PULL_VARIABLE = "SHERIFF_PULL";
    private static final String PULL_ALWAYS = "always";
    private static final String PERMISSIONS = "permissions";
    private static final String ALLOW = "allow";
    private static final String TOOL_RULE = "mcp(" + SERVER_NAME + "/%s)";
    private static final String TEST_TOOL = "sheriff_test";
    private static final String FIX_TOOL = "sheriff_fix";
    private static final String GUIDELINES_TOOL = "sheriff_guidelines";
    private static final String TASK_TOOL = "sheriff_task";
    private static final List<String> ALLOWED_TOOLS = List.of(TEST_TOOL, FIX_TOOL, GUIDELINES_TOOL, TASK_TOOL);
    private static final String NOT_AN_OBJECT = "%s is not a JSON object; left it untouched.%n";
    private static final String PULL_ALWAYS_NOTE =
            "  image          pulled again each time the server starts, when a newer one is published\n";
    private static final String BLANK = "";
    private static final String INSTALLED = """
            Antigravity is set up for every project agy opens:
              MCP server     '%s' in %s
              permissions    sheriff_test, sheriff_fix, sheriff_guidelines and sheriff_task run without asking,
                             in %s
            %sIt has no Sheriff hooks: a turn there can end with errors left.
            Undo with: java -jar sheriff-mcp.jar --uninstall-antigravity
            """;
    private static final String UNINSTALLED = "Sheriff is no longer in %s or %s.%n";
    private static final int SUCCESS = 0;
    private static final int FAILURE = 2;

    private final ObjectMapper json = new ObjectMapper();
    private final Path geminiHome;
    private final Path jar;
    private final PrintStream output;
    private final boolean pullAlways;
    private final String java;

    /**
     * Wires the installer.
     *
     * @param geminiHome where {@code agy} keeps its configuration,
     *     {@code ~/.gemini}
     * @param jar this jar, which the server will run
     * @param output where to report what was done
     */
    public AntigravityInstaller(Path geminiHome, Path jar, PrintStream output) {
        this(geminiHome, jar, output, false, JAVA);
    }

    /**
     * Every field, for {@link #pullingAlways} and {@link #runningWith}.
     *
     * @param geminiHome where {@code agy} keeps its configuration
     * @param jar this jar
     * @param output where to report
     * @param pullAlways whether the server's entry sets {@code SHERIFF_PULL=always}
     * @param java the Java the server runs with: {@code java}, or a path
     */
    private AntigravityInstaller(Path geminiHome, Path jar, PrintStream output, boolean pullAlways, String java) {
        this.geminiHome = geminiHome;
        this.jar = jar;
        this.output = output;
        this.pullAlways = pullAlways;
        this.java = java;
    }

    /**
     * The installer for the user running it, whose home holds
     * {@code agy}'s {@code .gemini} directory.
     *
     * @param home the user's home
     * @param jar this jar
     * @param output where to report
     * @return the installer
     */
    public static AntigravityInstaller forUser(Path home, Path jar, PrintStream output) {
        return new AntigravityInstaller(home.resolve(GEMINI_DIRECTORY), jar, output);
    }

    /**
     * The same installer, with a server that pulls a newer Sheriff image each
     * time it starts, as {@code --pull-always} asks of every assistant.
     *
     * @return that installer
     */
    public AntigravityInstaller pullingAlways() {
        return new AntigravityInstaller(geminiHome, jar, output, true, java);
    }

    /**
     * The same installer, with a server that runs a given Java rather than
     * the {@code java} on the PATH {@code agy} starts it with, as
     * {@link HookInstaller#runningWith} explains.
     *
     * @param executable the Java to run, by its path
     * @return that installer
     */
    public AntigravityInstaller runningWith(Path executable) {
        return new AntigravityInstaller(geminiHome, jar, output, pullAlways,
                executable.toAbsolutePath().normalize().toString());
    }

    /**
     * Writes the server and the permissions, replacing any earlier copy of
     * each and keeping everything else in both files.
     *
     * @return 0 when both were written, 2 when one could not be
     */
    public int install() {
        if (!rewrite(true)) {
            return FAILURE;
        }
        String pulling = pullAlways ? PULL_ALWAYS_NOTE : BLANK;
        output.printf(INSTALLED, SERVER_NAME, serversFile(), settingsFile(), pulling);
        return SUCCESS;
    }

    /**
     * Takes the server and the permissions out, keeping everything else in
     * both files.
     *
     * @return 0 when both were written, 2 when one could not be
     */
    public int uninstall() {
        if (!rewrite(false)) {
            return FAILURE;
        }
        output.printf(UNINSTALLED, serversFile(), settingsFile());
        return SUCCESS;
    }

    /**
     * Rewrites both files, with this jar's entries when adding and without
     * them when not. A file that is not a JSON object is reported and left
     * as it was, and a missing file is not created only to remove nothing.
     *
     * @param add whether to add the entries after removing the old ones
     * @return {@code true} when both files were handled
     */
    private boolean rewrite(boolean add) {
        try {
            ObjectNode servers = read(serversFile());
            ObjectNode settings = read(settingsFile());
            if (servers == null || settings == null) {
                return false;
            }
            rewriteServer(servers, add);
            rewritePermissions(settings, add);
            if (add || Files.exists(serversFile())) {
                write(serversFile(), servers);
            }
            if (add || Files.exists(settingsFile())) {
                write(settingsFile(), settings);
            }
            return true;
        } catch (IOException exception) {
            output.println(exception.getMessage());
            return false;
        }
    }

    /**
     * A JSON file's object, an empty one when the file is missing or empty,
     * or nothing when it holds something else, which is then reported.
     *
     * @param file the file
     * @return its object, or {@code null} when it is not one
     * @throws IOException when it cannot be read
     */
    private ObjectNode read(Path file) throws IOException {
        JsonNode read = Files.exists(file) ? json.readTree(file.toFile()) : json.createObjectNode();
        if (read == null || read.isMissingNode()) {
            return json.createObjectNode();
        }
        if (!read.isObject()) {
            output.printf(NOT_AN_OBJECT, file);
            return null;
        }
        return (ObjectNode) read;
    }

    /**
     * Drops the server's entry and, when adding, puts the current one in.
     *
     * @param root the MCP configuration
     * @param add whether to add the entry
     */
    private void rewriteServer(ObjectNode root, boolean add) {
        JsonNode existing = root.path(SERVERS);
        if (!existing.isObject() && !add) {
            return;
        }
        ObjectNode servers = existing.isObject() ? (ObjectNode) existing : root.putObject(SERVERS);
        servers.remove(SERVER_NAME);
        if (add) {
            servers.set(SERVER_NAME, serverEntry());
        }
    }

    /**
     * The server's entry, as {@code agy mcp add} writes it, naming this jar
     * by its absolute path.
     *
     * @return the entry
     */
    ObjectNode serverEntry() {
        ObjectNode entry = json.createObjectNode();
        entry.put(COMMAND, java);
        ArrayNode arguments = entry.putArray(ARGUMENTS);
        arguments.add(JAR_OPTION);
        arguments.add(jar.toAbsolutePath().normalize().toString());
        entry.put(DISABLED, false);
        if (pullAlways) {
            entry.putObject(ENVIRONMENT).put(PULL_VARIABLE, PULL_ALWAYS);
        }
        return entry;
    }

    /**
     * Drops this server's rules from {@code permissions.allow} and, when
     * adding, puts the current ones in, keeping every other rule.
     *
     * @param root the settings
     * @param add whether to add the rules
     */
    private void rewritePermissions(ObjectNode root, boolean add) {
        JsonNode existing = root.path(PERMISSIONS);
        if (!existing.isObject() && !add) {
            return;
        }
        ObjectNode permissions = existing.isObject() ? (ObjectNode) existing : root.putObject(PERMISSIONS);
        List<String> rules = ALLOWED_TOOLS.stream().map(tool -> String.format(TOOL_RULE, tool)).toList();
        ArrayNode allow = json.createArrayNode();
        permissions.path(ALLOW).forEach(rule -> {
            if (!rules.contains(rule.asText())) {
                allow.add(rule);
            }
        });
        if (add) {
            rules.forEach(allow::add);
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
     * Writes a file whole, where a link points and with the permissions it
     * had, as {@link ConfigFile} does for every installer.
     *
     * @param file the file
     * @param root its new content
     * @throws IOException when it cannot be written
     */
    private void write(Path file, ObjectNode root) throws IOException {
        ConfigFile.replace(file, json.writerWithDefaultPrettyPrinter().writeValueAsString(root)
                + System.lineSeparator());
    }

    /**
     * Where {@code agy} keeps its MCP servers.
     *
     * @return its path
     */
    private Path serversFile() {
        return geminiHome.resolve(CONFIG_DIRECTORY).resolve(SERVERS_FILE);
    }

    /**
     * Where {@code agy} keeps its settings, permissions included.
     *
     * @return its path
     */
    private Path settingsFile() {
        return geminiHome.resolve(CLI_DIRECTORY).resolve(SETTINGS_FILE);
    }
}
