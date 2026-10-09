package com.kaizten.sheriff.infrastructure.docker;

import com.kaizten.sheriff.domain.port.FixerScriptSource;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads a fixer script out of the Sheriff image.
 *
 * <p>Sheriff ships the scripts its {@code fix} subcommand runs under
 * {@code /sheriff-fixers}, one directory per repairable rule, and the catalog
 * already records each rule's path among them. What the catalog does not
 * record is the text, which is the part worth having.
 *
 * <p>Nothing is mounted and nothing is analyzed: this reads a file out of the
 * image with the image's own shell, the same read-only trick the rule
 * extractor uses.
 */
public final class DockerFixerScriptSource implements FixerScriptSource {

    /**
     * Where Sheriff keeps the scripts, inside the image.
     */
    public static final String FIXERS_ROOT = "/sheriff-fixers";

    private static final String ERROR_NOT_A_SCRIPT_PATH = "Not a fixer script path: ";
    private static final String ERROR_COULD_NOT_READ = "Could not read ";
    private static final String PARENT_SEGMENT = "..";
    private static final String SEPARATOR = "/";
    private static final String READ_COMMAND = "cat '%s'";
    private static final String SAFE_PATH_EXPRESSION = "^[A-Za-z0-9._/-]+$";
    private static final Pattern SAFE_PATH = Pattern.compile(SAFE_PATH_EXPRESSION);
    private static final String DOCKER = "docker";
    private static final String RUN = "run";
    private static final String REMOVE = "--rm";
    private static final String ENTRYPOINT = "--entrypoint";
    private static final String SHELL = "sh";
    private static final String COMMAND_FLAG = "-c";
    private static final int SUCCESS_EXIT_CODE = 0;

    private final ProcessRunner processes;
    private final String image;
    private final Duration timeout;

    /**
     * Builds a source reading from one image.
     *
     * @param processes how to run docker
     * @param image the Sheriff image to read from
     * @param timeout how long to wait for it
     */
    public DockerFixerScriptSource(ProcessRunner processes, String image, Duration timeout) {
        this.processes = processes;
        this.image = image;
        this.timeout = timeout;
    }

    /**
     * Reads one fixer script out of the image.
     *
     * @param scriptPath the script's path relative to the fixers directory
     * @return the script's text
     * @throws IllegalArgumentException when the path is not one this will read
     * @throws IllegalStateException when docker could not produce the file
     */
    @Override
    public String read(String scriptPath) {
        String path = FIXERS_ROOT + SEPARATOR + cleaned(scriptPath);
        ProcessOutcome outcome = processes.run(
                List.of(DOCKER, RUN, REMOVE, ENTRYPOINT, SHELL, image, COMMAND_FLAG, String.format(READ_COMMAND, path)),
                null,
                Map.of(),
                timeout);
        if (!outcome.ran() || outcome.exitCode() != SUCCESS_EXIT_CODE) {
            throw new IllegalStateException(ERROR_COULD_NOT_READ + reason(outcome, path));
        }
        return outcome.standardOutput();
    }

    /**
     * What to report when the read did not produce a script.
     *
     * @param outcome what running docker produced
     * @param path the script that was asked for
     * @return the most specific explanation available
     */
    private static String reason(ProcessOutcome outcome, String path) {
        if (!outcome.failure().isBlank()) {
            return outcome.failure().strip();
        }
        return outcome.standardError().isBlank() ? path : outcome.standardError().strip();
    }

    /**
     * The path as it will be read, refusing anything that is not plainly a
     * path.
     *
     * <p>The result is interpolated into a shell command inside the
     * container, so rejecting only {@code ..} was not enough: a single quote
     * closes the quoting and everything after it runs, and this path comes
     * from the catalog — which the RULES_CATALOG setting deliberately lets
     * point at a file this project did not write. All 88 real fixer paths
     * are {@code [a-z./_-]} — the only way a catalog entry could reach an arbitrary
     * file in the image.
     *
     * @param scriptPath the path as the catalog records it
     * @return that path without its leading separator
     */
    private static String cleaned(String scriptPath) {
        if (scriptPath == null) {
            throw new IllegalArgumentException(ERROR_NOT_A_SCRIPT_PATH + "null");
        }
        String path = scriptPath.strip();
        while (path.startsWith(SEPARATOR)) {
            path = path.substring(1);
        }
        if (path.isEmpty()
                || List.of(path.split(SEPARATOR, -1)).contains(PARENT_SEGMENT)
                || !SAFE_PATH.matcher(path).matches()) {
            throw new IllegalArgumentException(ERROR_NOT_A_SCRIPT_PATH + scriptPath);
        }
        return path;
    }
}
