package com.kaizten.sheriff.infrastructure.extractor;

import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads the Sheriff image itself, using only tooling the image already ships —
 * a JDK, {@code unzip} and {@code strings} — and with no repository mounted.
 *
 * <p>Sheriff's rules are not documented and are not a file in the analyzed
 * project: they are compiled into its jar. Everything here is the consequence
 * of that, and of one decision — that the catalog should be extracted rather
 * than transcribed by hand, so it can be regenerated when the image changes.
 */
public final class ImageReader {

    private static final String MESSAGE_PACKAGE = "com/kaizten/analysis/error/message";

    /**
     * Where the checkers live, newest layout first. They moved from {@code
     * analysis/checker} to {@code analysis/test} in the image published on
     * 2026-09-16, and that same release stopped putting the rule text in the
     * message classes. Both paths are tried, and neither is required to be
     * there, so one reader handles an image from either side of the change.
     */
    private static final String CHECKER_PACKAGES = "com/kaizten/analysis/test com/kaizten/analysis/checker";

    private static final String CHECKER_PATTERNS = "'com/kaizten/analysis/test/*' 'com/kaizten/analysis/checker/*'";

    private static final String CONSTANTS_SCRIPT = """
            cd /tmp && unzip -o -q /app.jar '%s/*' || exit 1
            for pattern in %s; do
              unzip -o -q /app.jar "$pattern" 2>/dev/null
            done
            DIRS=$(ls -d %s %s 2>/dev/null)
            CLASSES=$(find $DIRS -name '*.class' | sed 's|/|.|g; s|\\.class$||')
            javap -p -constants -cp /tmp $CLASSES 2>/dev/null
            """.formatted(MESSAGE_PACKAGE, CHECKER_PATTERNS, MESSAGE_PACKAGE, CHECKER_PACKAGES);

    /**
     * Disassembles the checkers so each message class can be paired with the
     * text the checker hands it. Only the checkers: the message classes carry
     * no code worth reading, and {@code javap -c} over the whole jar is
     * enormous.
     */
    private static final String MESSAGE_LINK_SCRIPT = """
            cd /tmp || exit 1
            for pattern in %s; do
              unzip -o -q /app.jar "$pattern" 2>/dev/null
            done
            DIRS=$(ls -d %s 2>/dev/null)
            [ -n "$DIRS" ] || exit 0
            CLASSES=$(find $DIRS -name '*.class' | sed 's|/|.|g; s|\\.class$||')
            javap -p -c -cp /tmp $CLASSES 2>/dev/null
            """.formatted(CHECKER_PATTERNS, CHECKER_PACKAGES);

    /**
     * Read from the constant pool with {@code strings} rather than javap: 650
     * classes of disassembled bytecode is tens of megabytes to say something a
     * grep answers exactly as well.
     */
    private static final String GRAPH_SCRIPT = """
            cd /tmp && unzip -o -q /app.jar 'com/kaizten/*' || exit 1
            find com/kaizten -name '*.class' | while read f; do
              echo "@@CLASS ${f%.class}"
              strings "$f" | grep -oE 'com/kaizten/[A-Za-z0-9/$]+' | sort -u
            done
            """;

    private static final String FACTORY_SCRIPT = """
            cd /tmp && unzip -o -q /app.jar 'com/kaizten/analysis/factory/*' || exit 1
            echo '@@@SWITCHMAP'
            javap -c -p -cp /tmp 'com.kaizten.analysis.factory.TestFactory$1' 2>/dev/null
            echo '@@@CREATE'
            javap -c -p -cp /tmp com.kaizten.analysis.factory.TestFactory 2>/dev/null
            """;

    private static final Duration TIMEOUT = Duration.ofSeconds(600);
    private static final String SHELL = "sh";
    private static final String SHELL_FLAG = "-c";
    private static final String ENTRYPOINT_FLAG = "--entrypoint";
    private static final String DOCKER = "docker";
    private static final String RUN_COMMAND = "run";
    private static final String REMOVE_FLAG = "--rm";
    private static final String FIX_COMMAND = "fix";
    private static final String LIST_FLAG = "-l";
    private static final String FIXER_SCRIPTS_SCRIPT = """
            for f in $(find /sheriff-fixers -name "*.py" | sort); do \
              echo "@@FIXER ${f#/sheriff-fixers/}"; cat "$f"; echo ""; done""";
    private static final String TREE_COMMAND = "tree";
    private static final String EXPORT_PROFILE_FLAG = "--export-profile";
    private static final String JSON_FORMAT = "JSON";
    private static final String HELP_FLAG = "--help";
    private static final String FAILED = "Could not read the image: %s";
    private static final String NETWORK_FLAG = "--network";
    private static final String NETWORK_VARIABLE = "SHERIFF_DOCKER_NETWORK";
    private static final int AFTER_REMOVE_FLAG = 3;

    private final ProcessRunner processes;
    private final String image;
    private final Path workingDirectory;

    /**
     * Wires the reader to an image.
     *
     * @param processes how to run Docker
     * @param image the Sheriff image to read
     * @param workingDirectory where to run Docker from
     */
    public ImageReader(ProcessRunner processes, String image, Path workingDirectory) {
        this.processes = processes;
        this.image = image;
        this.workingDirectory = workingDirectory;
    }

    /**
     * Every fixer script in the image, in one read.
     *
     * <p>One {@code docker run} rather than ninety-five: the scripts are
     * small and the container start is what costs. The trailing echo is not
     * cosmetic -- a script that does not end in a newline would otherwise
     * have the next marker appended to its last line, where it no longer
     * starts one, which silently lost 65 of the 95 scripts.
     *
     * @return the scripts, each preceded by an {@code @@FIXER} marker
     */
    public String fixerScripts() {
        return inside(FIXER_SCRIPTS_SCRIPT);
    }

    /**
     * Sheriff's own enumeration of every profile and the checks it runs.
     *
     * <p>Authoritative where the bytecode reading could only approximate.
     * {@code --export-profile} refuses to be combined with any other tree
     * option, {@code -t} included, so this asks for everything at once.
     *
     * @return the exported profile tree, as JSON
     */
    public String testTree() {
        return run(List.of(DOCKER, RUN_COMMAND, REMOVE_FLAG, image,
                TREE_COMMAND, EXPORT_PROFILE_FLAG, JSON_FORMAT));
    }

    /**
     * The usage text, which lists the valid {@code --test} values.
     *
     * @return that text
     */
    public String profileNames() {
        return run(List.of(DOCKER, RUN_COMMAND, REMOVE_FLAG, image, TREE_COMMAND, HELP_FLAG));
    }

    /**
     * The constants of every class that carries rule text.
     *
     * @return the javap dump
     */
    public String constants() {
        return inside(CONSTANTS_SCRIPT);
    }

    /**
     * The checkers disassembled, so a message class can be matched with the
     * description and howToSolve the checker passes into it.
     *
     * <p>Empty rather than an exception when the disassembly fails: an image
     * whose message classes carry their own text needs none of this.
     *
     * @return the javap dump of every checker, or an empty string
     */
    public String messageLinks() {
        try {
            return inside(MESSAGE_LINK_SCRIPT);
        } catch (IllegalStateException unreadable) {
            return "";
        }
    }

    /**
     * Every com/kaizten class and the classes it references.
     *
     * @return the graph dump
     */
    public String referenceGraph() {
        return inside(GRAPH_SCRIPT);
    }

    /**
     * The bytecode of the factory that maps profiles to checkers.
     *
     * @return the factory dump
     */
    public String factory() {
        return inside(FACTORY_SCRIPT);
    }

    /**
     * Every reference code the image knows, and its fixer.
     *
     * @return what {@code fix -l} printed
     */
    public String fixerList() {
        return run(List.of(DOCKER, RUN_COMMAND, REMOVE_FLAG, image, FIX_COMMAND, LIST_FLAG));
    }

    /**
     * Runs a shell script inside the image, with the entrypoint replaced so
     * that Sheriff itself never starts and no repository has to be mounted.
     *
     * @param script the shell script to run in the container
     * @return whatever the script printed
     */
    private String inside(String script) {
        return run(List.of(DOCKER, RUN_COMMAND, REMOVE_FLAG, ENTRYPOINT_FLAG, SHELL, image, SHELL_FLAG, script));
    }

    /**
     * Runs one Docker command, turning a failure into an exception rather than
     * into an empty dump that would silently yield an incomplete catalog.
     *
     * @param command the command line to run
     * @return its standard output
     */
    private String run(List<String> command) {
        ProcessOutcome outcome = processes.run(withNetwork(command), workingDirectory, Map.of(), TIMEOUT);
        if (!outcome.succeeded()) {
            throw new IllegalStateException(String.format(FAILED,
                    outcome.ran() ? outcome.standardError().strip() : outcome.failure()));
        }
        return outcome.standardOutput();
    }

    /**
     * The command with a network mode spliced in when one is configured.
     *
     * <p>Nothing read out of the image needs a network, since the jar is
     * already inside it, and a host whose Docker cannot build its default
     * bridge fails every run on networking alone. Setting
     * {@code SHERIFF_DOCKER_NETWORK} to {@code none} gets the extraction
     * through such a host, and leaving it unset says nothing to Docker.
     *
     * @param command the command line, which starts with docker run --rm
     * @return that command line, carrying --network when one is configured
     */
    private static List<String> withNetwork(List<String> command) {
        String network = System.getenv(NETWORK_VARIABLE);
        if (network == null || network.isBlank()) {
            return command;
        }
        List<String> networked = new ArrayList<>(command);
        networked.addAll(AFTER_REMOVE_FLAG, List.of(NETWORK_FLAG, network.strip()));
        return List.copyOf(networked);
    }
}
