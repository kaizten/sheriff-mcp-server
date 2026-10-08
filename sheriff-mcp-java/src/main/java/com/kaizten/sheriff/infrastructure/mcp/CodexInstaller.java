package com.kaizten.sheriff.infrastructure.mcp;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sets Codex up for this jar, for every project it opens, or takes it out
 * again: the MCP server, the order of work and the turn and Stop hooks.
 *
 * <p>All three used to be steps the user did by hand, and each one missed
 * cost a demo. {@code codex mcp add} cannot give the server what it needs:
 * Codex cuts a call at 60 s, and {@code codex exec} refuses
 * {@code sheriff_fix} unless the entry approves it. So the entry is written
 * here, in {@code config.toml}, replacing any earlier one of the same name
 * with its subtables.
 *
 * <p>The order of work goes in Codex's global {@code AGENTS.md}, which Codex
 * reads in every project, as Claude Code reads the server's MCP instructions.
 * Codex shows those instructions only in the description of the tool that
 * finds tools, and asked for a feature without them it called no Sheriff tool
 * at all. The text sits between two markers, so a second install replaces it
 * and everything else in the file stays as it was.
 *
 * <p>Approving the hook stays with the user: Codex asks once, by design, and
 * writing the approval for it would skip the one review it insists on.
 */
public final class CodexInstaller {

    private static final String CODEX_HOME_VARIABLE = "CODEX_HOME";
    private static final String CODEX_DIRECTORY = ".codex";
    private static final String CONFIG_FILE = "config.toml";
    private static final String AGENTS_FILE = "AGENTS.md";
    private static final String TEMPORARY_SUFFIX = ".tmp";
    private static final String SERVER_NAME = "sheriff";
    private static final String TABLE_START = "[";
    private static final String SERVER_TABLE = "[mcp_servers." + SERVER_NAME + "]";
    private static final String SERVER_SUBTABLE = "[mcp_servers." + SERVER_NAME + ".";
    private static final int TOOL_TIMEOUT_SECONDS = 600;
    private static final String SERVER_ENTRY = """
            [mcp_servers.%s]
            command = "java"
            args = ["-jar", "%s"]
            tool_timeout_sec = %d
            %s
            [mcp_servers.%s.tools.sheriff_fix]
            approval_mode = "approve"
            """;
    private static final String PULL_ALWAYS_LINE = "env = { SHERIFF_PULL = \"always\" }\n";
    private static final String PULL_ALWAYS_NOTE =
            "  image          pulled again each time the server starts, when a newer one is published\n";
    private static final String BEGIN_MARKER =
            "<!-- sheriff: begin. Written by sheriff-mcp.jar --install-codex; this block is replaced on each install. -->";
    private static final String END_MARKER = "<!-- sheriff: end -->";
    private static final String BACKSLASH = "\\";
    private static final String ESCAPED_BACKSLASH = "\\\\";
    private static final String QUOTE = "\"";
    private static final String ESCAPED_QUOTE = "\\\"";
    private static final String BLANK = "";
    private static final String INSTALLED = """
            Codex is set up for every project it opens:
              MCP server     '%s' in %s, with %d s per call and sheriff_fix approved
              order of work  in %s
            %sUndo with: java -jar sheriff-mcp.jar --uninstall-codex
            """;
    private static final String UNINSTALLED = "Sheriff is no longer in %s or %s.%n";
    private static final int SUCCESS = 0;
    private static final int FAILURE = 2;

    private final Path codexHome;
    private final Path jar;
    private final PrintStream output;
    private final String instructions;
    private final Optional<HookInstaller> hooks;
    private final boolean pullAlways;

    /**
     * Wires the installer.
     *
     * @param codexHome Codex's home, {@code CODEX_HOME} or {@code ~/.codex}
     * @param jar this jar, which the server and the hook will run
     * @param home the user's home
     * @param output where to report what was done
     * @param instructions the order of work, as the server hands it out
     * @param failFast whether the Stop hook asks Sheriff to stop at the first
     *     error
     */
    public CodexInstaller(Path codexHome, Path jar, Path home, PrintStream output, String instructions,
            boolean failFast) {
        this.codexHome = codexHome;
        this.jar = jar;
        this.output = output;
        this.instructions = instructions;
        HookInstaller stopHook = HookInstaller.forCodex(jar, codexHome, home, output);
        this.hooks = Optional.of(failFast ? stopHook.failingFast() : stopHook);
        this.pullAlways = false;
    }

    /**
     * Every field, for {@link #withoutStopHook} and {@link #pullingAlways}.
     *
     * @param codexHome Codex's home
     * @param jar this jar
     * @param output where to report
     * @param instructions the order of work
     * @param hooks what installs the turn and Stop hooks, if anything
     * @param pullAlways whether the server's entry sets {@code SHERIFF_PULL=always}
     */
    private CodexInstaller(Path codexHome, Path jar, PrintStream output, String instructions,
            Optional<HookInstaller> hooks, boolean pullAlways) {
        this.codexHome = codexHome;
        this.jar = jar;
        this.output = output;
        this.instructions = instructions;
        this.hooks = hooks;
        this.pullAlways = pullAlways;
    }

    /**
     * The same installer, leaving {@code hooks.json} alone: the server and the
     * order of work only.
     *
     * @return that installer
     */
    public CodexInstaller withoutStopHook() {
        return new CodexInstaller(codexHome, jar, output, instructions, Optional.empty(), pullAlways);
    }

    /**
     * The same installer, with a server that pulls a newer Sheriff image each
     * time it starts. Codex starts its servers with almost none of the
     * shell's variables, so {@code SHERIFF_PULL} has to be in the entry.
     *
     * @return that installer
     */
    public CodexInstaller pullingAlways() {
        return new CodexInstaller(codexHome, jar, output, instructions, hooks, true);
    }

    /**
     * The installer for the user running it: Codex's home from
     * {@code CODEX_HOME}, as Codex itself reads it, or else {@code ~/.codex}.
     *
     * @param environment the process environment
     * @param home the user's home
     * @param jar this jar
     * @param output where to report
     * @param instructions the order of work
     * @param failFast whether the Stop hook stops at the first error
     * @return the installer
     */
    public static CodexInstaller forUser(Map<String, String> environment, Path home, Path jar, PrintStream output,
            String instructions, boolean failFast) {
        String configured = environment.get(CODEX_HOME_VARIABLE);
        Path codexHome = configured == null || configured.isBlank() ? home.resolve(CODEX_DIRECTORY)
                : Path.of(configured);
        return new CodexInstaller(codexHome, jar, home, output, instructions, failFast);
    }

    /**
     * Writes the server, the order of work and the hooks, replacing any
     * earlier copy of each.
     *
     * @return 0 when everything was written, 2 when something could not be
     */
    public int install() {
        try {
            rewriteServer(true);
            rewriteInstructions(true);
        } catch (IOException exception) {
            output.println(exception.getMessage());
            return FAILURE;
        }
        String pulling = pullAlways ? PULL_ALWAYS_NOTE : BLANK;
        output.printf(INSTALLED, SERVER_NAME, configFile(), TOOL_TIMEOUT_SECONDS, agentsFile(), pulling);
        return hooks.map(HookInstaller::install).orElse(SUCCESS);
    }

    /**
     * Takes the server, the order of work and the hooks out, leaving the
     * rest of each file as it was.
     *
     * @return 0 when everything was written, 2 when something could not be
     */
    public int uninstall() {
        try {
            rewriteServer(false);
            rewriteInstructions(false);
        } catch (IOException exception) {
            output.println(exception.getMessage());
            return FAILURE;
        }
        output.printf(UNINSTALLED, configFile(), agentsFile());
        return hooks.map(HookInstaller::uninstall).orElse(SUCCESS);
    }

    /**
     * Rewrites {@code config.toml} without the server's tables, and with the
     * current entry at the end when adding.
     *
     * @param add whether to add the entry after removing the old one
     * @throws IOException when the file cannot be read or written
     */
    private void rewriteServer(boolean add) throws IOException {
        Path config = configFile();
        if (!add && !Files.exists(config)) {
            return;
        }
        List<String> kept = withoutServer(Files.exists(config) ? Files.readAllLines(config) : List.of());
        dropTrailingBlanks(kept);
        if (add) {
            if (!kept.isEmpty()) {
                kept.add(BLANK);
            }
            kept.addAll(serverEntry().lines().toList());
        }
        write(config, kept);
    }

    /**
     * The lines of a {@code config.toml} without the server's table and its
     * subtables, which run from their header to the next table's.
     *
     * @param lines the file's lines
     * @return the rest
     */
    static List<String> withoutServer(List<String> lines) {
        List<String> kept = new ArrayList<>();
        boolean inside = false;
        for (String line : lines) {
            String trimmed = line.strip();
            if (trimmed.startsWith(TABLE_START)) {
                inside = trimmed.startsWith(SERVER_TABLE) || trimmed.startsWith(SERVER_SUBTABLE);
            }
            if (!inside) {
                kept.add(line);
            }
        }
        return kept;
    }

    /**
     * The server's entry, naming this jar by its absolute path: Codex does
     * not expand {@code ~} in a server's arguments.
     *
     * @return the TOML
     */
    String serverEntry() {
        String path = jar.toAbsolutePath().normalize().toString()
                .replace(BACKSLASH, ESCAPED_BACKSLASH).replace(QUOTE, ESCAPED_QUOTE);
        String environment = pullAlways ? PULL_ALWAYS_LINE : BLANK;
        return String.format(SERVER_ENTRY, SERVER_NAME, path, TOOL_TIMEOUT_SECONDS, environment, SERVER_NAME);
    }

    /**
     * Rewrites the global {@code AGENTS.md} without the block between the
     * markers, and with the current one at the end when adding. A file left
     * with nothing in it is removed, since this installer created it.
     *
     * @param add whether to add the block after removing the old one
     * @throws IOException when the file cannot be read or written
     */
    private void rewriteInstructions(boolean add) throws IOException {
        Path agents = agentsFile();
        if (!add && !Files.exists(agents)) {
            return;
        }
        List<String> kept = withoutBlock(Files.exists(agents) ? Files.readAllLines(agents) : List.of());
        dropTrailingBlanks(kept);
        if (add) {
            if (!kept.isEmpty()) {
                kept.add(BLANK);
            }
            kept.add(BEGIN_MARKER);
            kept.addAll(instructions.strip().lines().toList());
            kept.add(END_MARKER);
        }
        if (kept.isEmpty()) {
            Files.deleteIfExists(agents);
            return;
        }
        write(agents, kept);
    }

    /**
     * The lines of an {@code AGENTS.md} without the block this installer
     * wrote, markers included.
     *
     * @param lines the file's lines
     * @return the rest
     */
    static List<String> withoutBlock(List<String> lines) {
        List<String> kept = new ArrayList<>();
        boolean inside = false;
        for (String line : lines) {
            if (line.strip().equals(BEGIN_MARKER)) {
                inside = true;
            }
            if (!inside) {
                kept.add(line);
            }
            if (inside && line.strip().equals(END_MARKER)) {
                inside = false;
            }
        }
        return kept;
    }

    /**
     * Removes the blank lines at the end, so that installing again does not
     * grow the file by one each time.
     *
     * @param lines the lines, changed in place
     */
    private static void dropTrailingBlanks(List<String> lines) {
        while (!lines.isEmpty() && lines.get(lines.size() - 1).isBlank()) {
            lines.remove(lines.size() - 1);
        }
    }

    /**
     * Writes a file through a temporary one, so an interrupted write never
     * leaves half of it behind, and where a symbolic link points, as
     * {@link HookInstaller} does for the same reason.
     *
     * @param file the file
     * @param lines its new content
     * @throws IOException when it cannot be written
     */
    private static void write(Path file, List<String> lines) throws IOException {
        Path target = Files.isSymbolicLink(file) ? file.toRealPath() : file;
        Files.createDirectories(target.getParent());
        Path temporary = target.resolveSibling(target.getFileName() + TEMPORARY_SUFFIX);
        Files.writeString(temporary, String.join(System.lineSeparator(), lines) + System.lineSeparator(),
                StandardCharsets.UTF_8);
        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /**
     * Codex's configuration file.
     *
     * @return its path
     */
    private Path configFile() {
        return codexHome.resolve(CONFIG_FILE);
    }

    /**
     * Codex's global instructions file.
     *
     * @return its path
     */
    private Path agentsFile() {
        return codexHome.resolve(AGENTS_FILE);
    }
}
