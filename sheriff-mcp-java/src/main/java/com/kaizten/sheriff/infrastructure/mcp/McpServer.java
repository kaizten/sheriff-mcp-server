package com.kaizten.sheriff.infrastructure.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaizten.sheriff.infrastructure.cli.Agent;
import com.kaizten.sheriff.infrastructure.docker.ImageProvisioning;
import com.kaizten.sheriff.infrastructure.docker.SheriffImage;
import com.kaizten.sheriff.infrastructure.mcp.protocol.JsonRpcServer;
import com.kaizten.sheriff.infrastructure.mcp.protocol.ToolOutcome;
import com.kaizten.sheriff.infrastructure.mcp.tool.ToolCall;
import com.kaizten.sheriff.infrastructure.mcp.tool.Tools;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import com.kaizten.sheriff.infrastructure.process.SystemProcessRunner;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Entry point of the one jar: wires the configuration, the Sheriff runner and
 * the rule catalog into the tools, then speaks MCP over stdio -- or, with
 * {@code --check}, runs the same analysis once as a CI gate, and with
 * {@code --agent}, hands the rest of the command line to the agent's own
 * CLI.
 *
 * <p>This is a driving adapter over the agent's hexagonal core, which lives
 * in this same module: the order the tools follow is developer, assistant,
 * this server, the agent, Sheriff. Only what the MCP contract itself demands
 * is here -- the protocol layer, the tool definitions, the tasks, and the
 * checks {@link SheriffRunner} documents.
 */
public final class McpServer {

    private static final String TOOLS_FLAG = "--tools";
    private static final String CHECK_FLAG = "--check";
    private static final String HELP_FLAG = "--help";
    private static final String SHORT_HELP_FLAG = "-h";
    private static final String VERSION_FLAG = "--version";
    private static final String VERSION_LINE = "sheriff-mcp %s%n";
    private static final String HOOK_GATE_FLAG = "--hook-gate";
    private static final String HOOK_STOP_FLAG = "--hook-stop";
    private static final String HOOK_TURN_FLAG = "--hook-turn";
    private static final String CALL_FLAG = "--call";
    private static final String ARGUMENT_SEPARATOR = "=";
    private static final String CALL_USAGE = "--call takes a tool's name, then its arguments as name=value.";
    private static final int CALL_FAILED = 2;
    private static final int CALL_ANSWERED = 0;
    private static final String INSTRUCTIONS_FLAG = "--instructions";
    private static final String INSTALL_HOOKS_FLAG = "--install-hooks";
    private static final String UNINSTALL_HOOKS_FLAG = "--uninstall-hooks";
    private static final String USER_SCOPE_FLAG = "--user";
    private static final String INSTALL_CODEX_FLAG = "--install-codex";
    private static final String UNINSTALL_CODEX_FLAG = "--uninstall-codex";
    private static final String INSTALL_ANTIGRAVITY_FLAG = "--install-antigravity";
    private static final String UNINSTALL_ANTIGRAVITY_FLAG = "--uninstall-antigravity";
    private static final String FAIL_FAST_FLAG = "--fail-fast";
    private static final String NO_HOOKS_FLAG = "--no-hooks";
    private static final String PULL_ALWAYS_FLAG = "--pull-always";
    private static final String FAIL_FAST_VARIABLE = "SHERIFF_FAIL_FAST";
    private static final String ENABLED = "1";
    private static final String AGENT_FLAG = "--agent";
    private static final String OPTION_START = "-";
    private static final String UNKNOWN_OPTION = "Unknown option '%s': java -jar sheriff-mcp.jar --help lists them.%n";
    private static final int UNKNOWN_OPTION_EXIT = 2;
    private static final List<String> OWN_OPTIONS = List.of(TOOLS_FLAG, CHECK_FLAG, HELP_FLAG, SHORT_HELP_FLAG,
            VERSION_FLAG, HOOK_GATE_FLAG, HOOK_STOP_FLAG, HOOK_TURN_FLAG, CALL_FLAG, INSTRUCTIONS_FLAG,
            INSTALL_HOOKS_FLAG, UNINSTALL_HOOKS_FLAG, USER_SCOPE_FLAG, INSTALL_CODEX_FLAG, UNINSTALL_CODEX_FLAG,
            INSTALL_ANTIGRAVITY_FLAG, UNINSTALL_ANTIGRAVITY_FLAG, FAIL_FAST_FLAG, NO_HOOKS_FLAG, PULL_ALWAYS_FLAG);
    private static final String JAVA_VARIABLE = "SHERIFF_JAVA";
    private static final String NOT_A_JAVA =
            "SHERIFF_JAVA is '%s', which is not a file that can be run; the java on the PATH is used instead.%n";
    private static final String TARGET_REPO_VARIABLE = "TARGET_REPO";
    private static final String REPOSITORY_VARIABLE = "SHERIFF_REPO";
    private static final int FIRST_OPTION = 0;
    private static final int AFTER_FIRST_OPTION = 1;
    private static final String USER_DIRECTORY = "user.dir";
    private static final String USER_HOME = "user.home";
    private static final String INSTRUCTIONS_RESOURCE = "/instructions.md";
    private static final String NO_INSTRUCTIONS = "";
    private static final String USAGE = """
            Sheriff as an MCP server, for any project Sheriff can analyze.

              java -jar sheriff-mcp.jar            serve MCP over stdio (what a client runs)
              java -jar sheriff-mcp.jar --check    analyze every component here; exit 0 clean,
                  [--component C] [--profile P]    1 with errors, 2 when nothing could be analyzed
              java -jar sheriff-mcp.jar --tools    print the tool definitions
              java -jar sheriff-mcp.jar --version  print which version this jar is
              java -jar sheriff-mcp.jar --install-hooks [--user]
                                                   guard this project with the two Claude Code
                                                   hooks, in .claude/settings.json, or with --user
                                                   every project, in ~/.claude/settings.json
                                                   (--uninstall-hooks [--user] takes them out)
              java -jar sheriff-mcp.jar --install-codex
                                                   set Codex up for every project: the server, with the
                                                   time and approval it needs, the order of work in
                                                   ~/.codex/AGENTS.md and its hooks in hooks.json
                                                   (--uninstall-codex takes them out; --no-hooks leaves
                                                   hooks.json alone; --pull-always has the server pull
                                                   a newer Sheriff image each time it starts). With --fail-fast,
                                                   here or with --install-hooks, the hooks stop Sheriff
                                                   at the first error
              java -jar sheriff-mcp.jar --install-antigravity
                                                   set Antigravity's agy up for every project: the server
                                                   in ~/.gemini/config/mcp_config.json, and the four tools
                                                   that do not commit allowed in its settings.json
                                                   (--uninstall-antigravity takes them out; --pull-always
                                                   as for Codex). It has no Sheriff hooks
              java -jar sheriff-mcp.jar --instructions
                                                   the order of work the server hands a client, for an
                                                   assistant that does not read MCP instructions
              java -jar sheriff-mcp.jar --call sheriff_fix [component=api] [...]
                                                   one tool, without an MCP client: the same answer
                                                   the server gives, its steps written as commands
              java -jar sheriff-mcp.jar --hook-gate | --hook-stop | --hook-turn
                                                   the hooks themselves, as that file runs them
              java -jar sheriff-mcp.jar --agent [...]
                                                   the agent's own CLI: the fix loop with no more
                                                   arguments, --check-only, --rules, --extract-rules;
                                                   --agent --help lists them

            Run it in the project: it works out what to mount, which components there are and
            which profile each one's sources call for, and extracts Sheriff's rule catalog from
            the image by itself. Every SHERIFF_* variable in the readme only overrides one of
            those answers.
            """;
    private static final String ERROR_UTILITY_CLASS = "This is a utility class and cannot be instantiated.";
    private static final String SHUTDOWN_THREAD = "sheriff-shutdown";

    /**
     * Refuses to be instantiated: every member here is static.
     */
    private McpServer() {
        throw new UnsupportedOperationException(ERROR_UTILITY_CLASS);
    }

    /**
     * Runs the server: either prints the tool definitions and exits, or
     * serves MCP requests from standard input until it closes.
     *
     * @param arguments the command line
     * @throws IOException when standard input cannot be read
     */
    public static void main(String[] arguments) throws IOException {
        List<String> options = List.of(arguments);
        if (!options.isEmpty() && AGENT_FLAG.equals(options.get(FIRST_OPTION))) {
            Agent.main(agentEnvironment(System.getenv(), Path.of(System.getProperty(USER_DIRECTORY))),
                    options.subList(AFTER_FIRST_OPTION, options.size()).toArray(String[]::new));
            return;
        }
        Optional<String> unknown = unknownOption(options);
        if (unknown.isPresent()) {
            System.err.printf(UNKNOWN_OPTION, unknown.get());
            System.exit(UNKNOWN_OPTION_EXIT);
        }
        PrintStream protocol = claimStandardOutput();
        if (options.contains(HELP_FLAG) || options.contains(SHORT_HELP_FLAG)) {
            protocol.print(USAGE);
            return;
        }
        if (options.contains(VERSION_FLAG)) {
            protocol.printf(VERSION_LINE, ServerVersion.current());
            return;
        }
        if (options.contains(INSTRUCTIONS_FLAG)) {
            protocol.print(instructions());
            return;
        }
        for (String hook : List.of(HOOK_GATE_FLAG, HOOK_STOP_FLAG, HOOK_TURN_FLAG)) {
            if (options.contains(hook)) {
                System.exit(HookCommand.run(hook, hookEnvironment(System.getenv(), options),
                        Path.of(System.getProperty(USER_DIRECTORY))));
            }
        }
        if (options.contains(INSTALL_HOOKS_FLAG) || options.contains(UNINSTALL_HOOKS_FLAG)) {
            Path home = Path.of(System.getProperty(USER_HOME));
            HookInstaller chosen = options.contains(USER_SCOPE_FLAG)
                    ? forUser(HookInstaller.forUser(ownJar(), home, protocol))
                    : HookInstaller.forProject(System.getenv(), Path.of(System.getProperty(USER_DIRECTORY)), ownJar(),
                            home, protocol);
            HookInstaller installer = options.contains(FAIL_FAST_FLAG) ? chosen.failingFast() : chosen;
            System.exit(options.contains(INSTALL_HOOKS_FLAG) ? installer.install() : installer.uninstall());
        }
        if (options.contains(INSTALL_CODEX_FLAG) || options.contains(UNINSTALL_CODEX_FLAG)) {
            CodexInstaller chosen = CodexInstaller.forUser(System.getenv(), Path.of(System.getProperty(USER_HOME)),
                    ownJar(), protocol, instructions(), options.contains(FAIL_FAST_FLAG));
            CodexInstaller hooked = options.contains(NO_HOOKS_FLAG) ? chosen.withoutStopHook() : chosen;
            CodexInstaller pulling = options.contains(PULL_ALWAYS_FLAG) ? hooked.pullingAlways() : hooked;
            CodexInstaller installer = chosenJava(System.getenv()).map(pulling::runningWith).orElse(pulling);
            System.exit(options.contains(INSTALL_CODEX_FLAG) ? installer.install() : installer.uninstall());
        }
        if (options.contains(INSTALL_ANTIGRAVITY_FLAG) || options.contains(UNINSTALL_ANTIGRAVITY_FLAG)) {
            AntigravityInstaller chosen = AntigravityInstaller.forUser(Path.of(System.getProperty(USER_HOME)),
                    ownJar(), protocol);
            AntigravityInstaller pulling = options.contains(PULL_ALWAYS_FLAG) ? chosen.pullingAlways() : chosen;
            AntigravityInstaller installer = chosenJava(System.getenv()).map(pulling::runningWith).orElse(pulling);
            System.exit(options.contains(INSTALL_ANTIGRAVITY_FLAG) ? installer.install() : installer.uninstall());
        }
        McpConfig config = McpConfig.fromEnvironment();
        SystemProcessRunner processes = new SystemProcessRunner();
        SheriffRunner runner = new SheriffRunner(processes, config.image(), config.repository(), config.timeout());
        if (options.contains(CHECK_FLAG)) {
            if (config.problem().isPresent()) {
                System.err.println(config.problem().get());
                System.exit(CheckCommand.NOT_ANALYZED);
            }
            ImageProvisioning image = imageFor(config, processes);
            if (!image.now()) {
                image.blocker().ifPresent(System.err::println);
                System.exit(CheckCommand.NOT_ANALYZED);
            }
            System.exit(new CheckCommand(config, runner, protocol).run(options));
        }
        if (options.contains(CALL_FLAG)) {
            System.exit(call(options, config, processes, runner, protocol));
        }
        if (options.contains(TOOLS_FLAG)) {
            printDefinitions(new Tools(config, runner, List::of), protocol);
            return;
        }
        ImageProvisioning image = imageFor(config, processes).inBackground();
        ProvisionedCatalog catalog = ProvisionedCatalog.forServer(config, processes, image.ready());
        Tools tools = new Tools(config, runner, catalog, () -> pathOr(catalog, config), image);
        JsonRpcServer server = new JsonRpcServer(tools::definitions, tools::call, config.serverName(), instructions());
        Runtime.getRuntime().addShutdownHook(new Thread(tools::interruptUnfinished, SHUTDOWN_THREAD));
        try (tools; BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            server.serve(input, protocol);
        }
    }

    /**
     * One tool, called from the command line, answering as the server does.
     *
     * <p>The hooks name this as the way to follow their steps when the model
     * has no MCP tools: the server not registered, not started, or, under
     * Codex, not found by its search. It goes through the same tools, so the
     * answer is the server's, and its steps name the next command.
     *
     * @param options the command line
     * @param config where the project is
     * @param processes how to run Docker
     * @param runner what runs Sheriff
     * @param output where the answer goes
     * @return 0 when the tool answered, 2 when it could not
     */
    private static int call(List<String> options, McpConfig config, SystemProcessRunner processes,
            SheriffRunner runner, PrintStream output) {
        int at = options.indexOf(CALL_FLAG);
        if (at + AFTER_FIRST_OPTION >= options.size()) {
            System.err.println(CALL_USAGE);
            return CALL_FAILED;
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        for (String option : options.subList(at + AFTER_FIRST_OPTION + AFTER_FIRST_OPTION, options.size())) {
            int separator = option.indexOf(ARGUMENT_SEPARATOR);
            if (separator > FIRST_OPTION) {
                arguments.put(option.substring(FIRST_OPTION, separator), option.substring(separator + AFTER_FIRST_OPTION));
            }
        }
        ImageProvisioning image = imageFor(config, processes);
        image.now();
        ProvisionedCatalog catalog = ProvisionedCatalog.forServer(config, processes, image.ready());
        ToolCall commands = ToolCall.throughCommandLine(
                Path.of(System.getProperty(USER_DIRECTORY)).toAbsolutePath().normalize(), ownJar());
        try (Tools tools = new Tools(config, runner, catalog, () -> pathOr(catalog, config),
                Tools.projectTests(config), image, commands)) {
            ToolOutcome outcome = tools.call(options.get(at + AFTER_FIRST_OPTION), arguments);
            output.println(outcome.text());
            return outcome.isError() ? CALL_FAILED : CALL_ANSWERED;
        }
    }

    /**
     * The first option on a command line that this jar does not know, if
     * there is one.
     *
     * <p>Anything that does not start with a dash is a value: a tool's name
     * or {@code name=value} after {@code --call}, a component or a profile
     * after {@code --check}. An option nobody knew used to be ignored, so
     * {@code --instal-hooks} started the MCP server, which waited on standard
     * input, or ended at once with nothing done and exit code 0.
     *
     * @param options the command line, without {@code --agent} and what
     *     follows it
     * @return that option, or empty when every one is known
     */
    static Optional<String> unknownOption(List<String> options) {
        return options.stream()
                .filter(option -> option.startsWith(OPTION_START))
                .filter(option -> !OWN_OPTIONS.contains(option) && !CheckCommand.OPTIONS.contains(option))
                .findFirst();
    }

    /**
     * The hooks of every project, running the Java {@code install.sh}
     * checked when it names one.
     *
     * @param installer the installer for the user's settings
     * @return the same, running that Java
     */
    private static HookInstaller forUser(HookInstaller installer) {
        return chosenJava(System.getenv()).map(installer::runningWith).orElse(installer);
    }

    /**
     * The Java the installers write into the user's configuration, when
     * {@code install.sh} names the one it checked in {@code SHERIFF_JAVA}.
     *
     * @param environment the process environment
     * @return that Java, or empty for the {@code java} on the PATH: when the
     *     variable is not set, or names nothing that can be run
     */
    static Optional<Path> chosenJava(Map<String, String> environment) {
        String configured = environment.get(JAVA_VARIABLE);
        if (configured == null || configured.isBlank()) {
            return Optional.empty();
        }
        Path java = Path.of(configured.strip());
        if (!Files.isRegularFile(java) || !Files.isExecutable(java)) {
            System.err.printf(NOT_A_JAVA, configured);
            return Optional.empty();
        }
        return Optional.of(java);
    }

    /**
     * The environment a hook runs with: the process's own, plus
     * {@code SHERIFF_FAIL_FAST=1} when its command says {@code --fail-fast}.
     * The option is how an installer asks for it, because a hook gets almost
     * none of the shell's variables under Codex.
     *
     * @param environment the process environment
     * @param options the command line
     * @return the environment to run the hook with
     */
    static Map<String, String> hookEnvironment(Map<String, String> environment, List<String> options) {
        if (!options.contains(FAIL_FAST_FLAG)) {
            return environment;
        }
        Map<String, String> withFailFast = new HashMap<>(environment);
        withFailFast.put(FAIL_FAST_VARIABLE, ENABLED);
        return withFailFast;
    }

    /**
     * What the client is told about using the tools together: the order a
     * change goes in, which used to live in a separate skill and now travels
     * with the jar.
     *
     * @return the instructions, or empty when the jar carries none
     * @throws IOException when the resource cannot be read
     */
    static String instructions() throws IOException {
        try (InputStream stream = McpServer.class.getResourceAsStream(INSTRUCTIONS_RESOURCE)) {
            return stream == null ? NO_INSTRUCTIONS : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Where this jar is, for the hooks to run it.
     *
     * @return its path
     */
    private static Path ownJar() {
        try {
            return Path.of(McpServer.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Where the catalog is, or where it was expected when it never turned
     * up.
     *
     * @param catalog the provisioned catalog
     * @param config where to look otherwise
     * @return a path to hand on
     */
    private static Path pathOr(ProvisionedCatalog catalog, McpConfig config) {
        Path found = catalog.path();
        return found == null ? config.rulesCatalog() : found;
    }

    /**
     * What makes sure of Sheriff's image for this server or check: pulled
     * when missing, reported when out of date, as {@code SHERIFF_PULL} says.
     *
     * @param config which image
     * @param processes how to run docker
     * @return that provisioning, not yet started
     */
    private static ImageProvisioning imageFor(McpConfig config, ProcessRunner processes) {
        return new ImageProvisioning(new SheriffImage(processes, config.image()),
                ImageProvisioning.policyIn(System.getenv()), System.err);
    }

    /**
     * Takes standard output for the protocol alone.
     *
     * <p>Over stdio every byte on standard output is read as JSON-RPC. The fix
     * loop behind {@code sheriff_autofix} reports its progress through
     * {@code System.out}, like the command line it was written for, and one
     * such line in the stream is enough to break the client parsing it. From
     * here on {@code System.out} points at standard error, which clients log,
     * and only the stream returned here reaches the client.
     *
     * @return the real standard output, for the protocol
     */
    static PrintStream claimStandardOutput() {
        PrintStream protocol = System.out;
        System.setOut(System.err);
        return protocol;
    }

    /**
     * Prints every tool's definition as formatted JSON, the diagnostic mode
     * for checking what a client would see without speaking the protocol.
     *
     * @param tools the registry to print
     * @param output where to print them
     * @throws IOException when the definitions cannot be rendered
     */
    private static void printDefinitions(Tools tools, PrintStream output) throws IOException {
        ObjectMapper json = new ObjectMapper();
        output.println(json.writerWithDefaultPrettyPrinter().writeValueAsString(tools.definitions()));
    }

    /**
     * The environment the agent's command line runs with: what to mount,
     * which component to repair, the profile it is checked under and its test
     * command, worked out from where it is run, as the server does.
     *
     * <p>Left to its own defaults the command line mounts the folder above
     * the jar and repairs {@code sheriff-mcp-java} under {@code JAVA}, which
     * is right only for this repository analyzing itself. The command the
     * hooks suggest is run from the project with {@code TARGET_REPO} and
     * {@code SHERIFF_COMPONENT} set, and still repaired a TypeScript component
     * as Java; from anywhere else, an installed jar mounted
     * {@code ~/.local/share}. What the user set is never replaced, and
     * outside any project (the home directory, say) nothing is filled in.
     *
     * @param environment the process environment
     * @param workingDirectory where the command line was started
     * @return the environment, with what could be worked out filled in
     */
    static Map<String, String> agentEnvironment(Map<String, String> environment, Path workingDirectory) {
        try {
            String target = environment.get(TARGET_REPO_VARIABLE);
            Map<String, String> read = new HashMap<>(environment);
            if (target != null) {
                read.put(REPOSITORY_VARIABLE, target);
            }
            McpConfig config = new McpConfig(read, workingDirectory);
            if (target == null && config.layout().components().isEmpty()) {
                return environment;
            }
            String component = config.defaultComponent();
            if (component.isEmpty() && target != null) {
                return environment;
            }
            return config.agentEnvironment(environment, component);
        } catch (RuntimeException exception) {
            return environment;
        }
    }
}
