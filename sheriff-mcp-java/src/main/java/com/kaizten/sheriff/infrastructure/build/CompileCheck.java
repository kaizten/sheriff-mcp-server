package com.kaizten.sheriff.infrastructure.build;

import com.kaizten.sheriff.infrastructure.process.Platform;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a component's Java still compiles, as a repair of Sheriff's is
 * checked right after it: a fixer can be right by Sheriff and break the
 * build, as {@code UnusedImport}'s did when it took out imports a file still
 * used, and the tests, which would show it, run only when asked.
 *
 * <p>Maven only, through the component's own build and wrapper:
 * {@code test-compile} first, which runs the plugins bound before it. A
 * check of the build's bound there, such as
 * {@code spring-javaformat:validate}, stopped it before compiling on
 * PetClinic, so a build stopped by a plugin other than the compiler is
 * compiled again with the compiler's goals alone, which run nothing else and
 * change nothing of the project's.
 */
public final class CompileCheck {

    private static final String BATCH = "-B";
    private static final String QUIET = "-q";
    private static final String TEST_COMPILE = "test-compile";
    private static final String COMPILE_GOAL = "compiler:compile";
    private static final String TEST_COMPILE_GOAL = "compiler:testCompile";
    private static final String COMPILER_PLUGIN = "maven-compiler-plugin";
    private static final String FAILED_GOAL_PATTERN =
            "Failed to execute goal [\\w.-]+:([\\w.-]+):[^:\\s]+:([\\w-]+)";
    private static final Pattern FAILED_GOAL = Pattern.compile(FAILED_GOAL_PATTERN);
    private static final String ERROR_PATTERN = "^\\[ERROR\\] (.+?\\.java):\\[(\\d+),\\d+\\] (.*)$";
    private static final Pattern ERROR = Pattern.compile(ERROR_PATTERN);
    private static final String DETAIL_PATTERN = "^\\[ERROR\\]\\s+(symbol|location):\\s+(.*)$";
    private static final Pattern DETAIL = Pattern.compile(DETAIL_PATTERN);
    private static final String SYMBOL = "symbol";
    private static final String SYMBOL_SEPARATOR = ", ";
    private static final String NOTHING = "";
    private static final String DETAIL_FORMAT = "%s: %s";
    private static final String GOAL_FORMAT = "%s:%s";
    private static final String LINE_BREAK = "\\R";
    private static final int FILE = 1;
    private static final int LINE = 2;
    private static final int MESSAGE = 3;
    private static final int ARTIFACT = 1;
    private static final int GOAL = 2;
    private static final int KIND = 1;
    private static final int VALUE = 2;
    private static final int NEXT = 1;
    private final ProcessRunner processes;
    private final Path mount;
    private final BiFunction<String, List<String>, Optional<String>> maven;
    private final Duration timeout;

    /**
     * Wires the check to a project.
     *
     * @param processes how to run Maven
     * @param mount the directory Sheriff mounts, where Maven runs from
     * @param maven the command that runs Maven on a component with some
     *     arguments, empty when it does not build with Maven
     * @param timeout how long a compile gets
     */
    public CompileCheck(ProcessRunner processes, Path mount, BiFunction<String, List<String>, Optional<String>> maven,
            Duration timeout) {
        this.processes = processes;
        this.mount = mount;
        this.maven = maven;
        this.timeout = timeout;
    }

    /**
     * Compiles a component's main and test sources.
     *
     * @param component the component, a folder of the mount
     * @return whether it compiled and where it did not; empty when it does
     *     not build with Maven, or the build failed for a reason that is no
     *     error of the compiler's, so nothing is known
     */
    public Optional<CompileOutcome> check(String component) {
        Optional<String> lifecycle = maven.apply(component, List.of(BATCH, QUIET, TEST_COMPILE));
        if (lifecycle.isEmpty()) {
            return Optional.empty();
        }
        ProcessOutcome first = run(lifecycle.get());
        Optional<CompileOutcome> known = outcome(first, lifecycle.get(), Optional.empty());
        if (known.isPresent() || !first.ran()) {
            return known;
        }
        Optional<String> stopping = stoppingGoal(output(first));
        if (stopping.isEmpty()) {
            return Optional.empty();
        }
        String compilerOnly = maven.apply(component, List.of(BATCH, QUIET, COMPILE_GOAL, TEST_COMPILE_GOAL))
                .orElseThrow();
        return outcome(run(compilerOnly), compilerOnly, stopping);
    }

    /**
     * What a compile said, when it says anything.
     *
     * @param outcome what the run did
     * @param command the command run
     * @param bypassed the goal the command left out, or empty
     * @return that it compiled, or where it did not; empty when it failed
     *     with no error of the compiler's, or did not run
     */
    private Optional<CompileOutcome> outcome(ProcessOutcome outcome, String command, Optional<String> bypassed) {
        if (!outcome.ran()) {
            return Optional.empty();
        }
        if (outcome.succeeded()) {
            return Optional.of(new CompileOutcome(true, List.of(), command, bypassed));
        }
        List<CompileError> errors = errors(output(outcome), mount);
        if (errors.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new CompileOutcome(false, errors, command, bypassed));
    }

    /**
     * Runs one command from the mount.
     *
     * @param command the command
     * @return what it did
     */
    private ProcessOutcome run(String command) {
        return processes.run(Platform.shell(command), mount, Map.of(), timeout);
    }

    /**
     * Everything a run printed.
     *
     * @param outcome what the run did
     * @return its output and its errors
     */
    private static String output(ProcessOutcome outcome) {
        return outcome.standardOutput() + outcome.standardError();
    }

    /**
     * The goal that stopped a build, when it is not the compiler's: a check
     * bound before compiling, such as a formatter's validation.
     *
     * @param output what the build printed
     * @return that goal, as {@code artifactId:goal}; empty when the compiler
     *     stopped it, or no goal did
     */
    public static Optional<String> stoppingGoal(String output) {
        Matcher matcher = FAILED_GOAL.matcher(output);
        if (!matcher.find() || COMPILER_PLUGIN.equals(matcher.group(ARTIFACT))) {
            return Optional.empty();
        }
        return Optional.of(String.format(GOAL_FORMAT, matcher.group(ARTIFACT), matcher.group(GOAL)));
    }

    /**
     * The compiler's errors in a build's output, each once: Maven prints
     * them as they come and again in its summary. A {@code symbol:} line
     * after one is part of it, as {@code cannot find symbol} alone does not
     * say which.
     *
     * @param output what the build printed
     * @param mount the directory the files are named relative to
     * @return the errors, in the order printed
     */
    static List<CompileError> errors(String output, Path mount) {
        String[] lines = output.split(LINE_BREAK);
        Set<CompileError> errors = new LinkedHashSet<>();
        for (int index = 0; index < lines.length; index++) {
            Matcher matcher = ERROR.matcher(lines[index]);
            if (matcher.matches()) {
                String message = matcher.group(MESSAGE).strip() + symbolOf(lines, index + NEXT);
                errors.add(new CompileError(relative(matcher.group(FILE), mount),
                        Integer.parseInt(matcher.group(LINE)), message));
            }
        }
        return errors.stream().filter(error -> errors.stream().noneMatch(other -> saysMoreOf(other, error))).toList();
    }

    /**
     * Whether one error is another with the symbol it names: Maven prints an
     * error first without the lines after it, and again in its summary with
     * them.
     *
     * @param fuller the error that may name a symbol
     * @param error the error that may be the same one without it
     * @return {@code true} when they are one error
     */
    private static boolean saysMoreOf(CompileError fuller, CompileError error) {
        return fuller.file().equals(error.file()) && fuller.line() == error.line()
                && fuller.message().startsWith(error.message() + SYMBOL_SEPARATOR);
    }

    /**
     * The symbol an error names on the lines after it, when it does.
     *
     * @param lines the build's lines
     * @param from the line after the error's
     * @return {@code ", symbol: ..."}, or nothing
     */
    private static String symbolOf(String[] lines, int from) {
        for (int index = from; index < lines.length; index++) {
            Matcher matcher = DETAIL.matcher(lines[index]);
            if (!matcher.matches()) {
                return NOTHING;
            }
            if (SYMBOL.equals(matcher.group(KIND))) {
                return SYMBOL_SEPARATOR + String.format(DETAIL_FORMAT, matcher.group(KIND), matcher.group(VALUE).strip());
            }
        }
        return NOTHING;
    }

    /**
     * A file the compiler named, as Sheriff names it: relative to the mount,
     * with {@code /}.
     *
     * @param file the path the compiler wrote
     * @param mount the mount
     * @return it relative to the mount, or as written when it is not inside
     */
    private static String relative(String file, Path mount) {
        try {
            Path path = Path.of(file).toAbsolutePath().normalize();
            Path root = mount.toAbsolutePath().normalize();
            return path.startsWith(root) ? Platform.slashes(root.relativize(path)) : file;
        } catch (InvalidPathException exception) {
            return file;
        }
    }
}
