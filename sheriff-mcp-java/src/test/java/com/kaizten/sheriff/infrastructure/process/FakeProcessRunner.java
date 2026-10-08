package com.kaizten.sheriff.infrastructure.process;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * A ProcessRunner that answers from a script and records what it was asked.
 *
 * <p>This is what the seam buys: the command an adapter builds becomes an
 * assertion about a recorded value, instead of an inspection of a mock's call
 * arguments.
 */
public final class FakeProcessRunner implements ProcessRunner {

    private final Deque<ProcessOutcome> outcomes;
    private final boolean recordsSheriffReport;
    private final List<List<String>> commands = new ArrayList<>();
    private final List<Map<String, String>> environments = new ArrayList<>();
    private final List<Path> directories = new ArrayList<>();
    private final List<String> inputs = new ArrayList<>();

    /**
     * @param outcomes what successive runs should return
     */
    public FakeProcessRunner(List<ProcessOutcome> outcomes) {
        this(outcomes, true);
    }

    private FakeProcessRunner(List<ProcessOutcome> outcomes, boolean recordsSheriffReport) {
        this.outcomes = new ArrayDeque<>(outcomes);
        this.recordsSheriffReport = recordsSheriffReport;
    }

    /**
     * A runner that always answers the same way.
     *
     * @param outcome what every run returns
     * @return the runner
     */
    public static FakeProcessRunner always(ProcessOutcome outcome) {
        return new FakeProcessRunner(List.of(outcome));
    }

    /**
     * A runner that does not simulate Sheriff's findings state file.
     *
     * @param outcome what every run returns
     * @return the runner
     */
    public static FakeProcessRunner withoutSheriffReport(ProcessOutcome outcome) {
        return new FakeProcessRunner(List.of(outcome), false);
    }

    @Override
    public ProcessOutcome run(List<String> command, Path workingDirectory, Map<String, String> environment,
            Duration timeout, String standardInput) {
        inputs.add(standardInput);
        return run(command, workingDirectory, environment, timeout);
    }

    /**
     * What the last run was given on its standard input.
     *
     * @return that input, or {@code null} when none was given
     */
    public String input() {
        return inputs.isEmpty() ? null : inputs.get(inputs.size() - 1);
    }

    @Override
    public ProcessOutcome run(
            List<String> command, Path workingDirectory, Map<String, String> environment, Duration timeout) {
        commands.add(command);
        environments.add(environment);
        directories.add(workingDirectory);
        ProcessOutcome outcome = outcomes.size() == 1 ? outcomes.peek() : outcomes.removeFirst();
        if (recordsSheriffReport) {
            recordSheriffTest(outcome, command, workingDirectory);
        }
        return outcome;
    }

    private static void recordSheriffTest(ProcessOutcome outcome, List<String> command, Path directory) {
        int test = command.indexOf("test");
        int profile = command.indexOf("--test");
        int component = command.indexOf("--component");
        if (!outcome.succeeded() || test < 0 || profile < 0 || component < 0
                || profile + 1 >= command.size() || component + 1 >= command.size()) {
            return;
        }
        String uri = "file:/data";
        int uriFlag = command.indexOf("--uri");
        if (uriFlag >= 0 && uriFlag + 1 < command.size()) {
            uri = command.get(uriFlag + 1);
        }
        String report = "{\"" + uri + "\":{\"" + command.get(component + 1) + "\":{\""
                + command.get(profile + 1) + "\":" + findingsOf(outcome.standardOutput()) + "}}}";
        try {
            Files.writeString(directory.resolve("sheriff_errors.json"), report);
        } catch (IOException ignored) {
            // The fake still returns the process result when its side effect cannot be represented.
        }
    }

    /**
     * The findings array real Sheriff would record for what it printed: the
     * same array its stdout carries after the header, or an empty one.
     *
     * @param standardOutput what the scripted run prints
     * @return that array, as JSON text
     */
    private static String findingsOf(String standardOutput) {
        if (standardOutput.stripLeading().startsWith("Usage: Sheriff")) {
            return "null";
        }
        int start = standardOutput.indexOf('[');
        return start < 0 ? "[]" : standardOutput.substring(start);
    }

    /**
     * Everything this runner was asked to run, in order.
     *
     * @return the recorded command lines
     */
    public List<List<String>> commands() {
        return commands;
    }

    /**
     * The first command, which is what most adapters only issue once.
     *
     * @return that command line
     */
    public List<String> command() {
        return commands.get(0);
    }

    /**
     * The environment overrides of the first run.
     *
     * @return those variables
     */
    public Map<String, String> environment() {
        return environments.get(0);
    }

    /**
     * The working directory of the first run.
     *
     * @return that directory
     */
    public Path directory() {
        return directories.get(0);
    }
}
