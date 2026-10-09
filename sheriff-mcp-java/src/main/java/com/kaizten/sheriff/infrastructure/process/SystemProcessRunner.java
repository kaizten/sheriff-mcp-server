package com.kaizten.sheriff.infrastructure.process;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The real {@link ProcessRunner}, on top of {@link ProcessBuilder}.
 *
 * <p>Both streams are drained on their own threads while the command runs.
 * That is not defensive programming, it is the difference between working and
 * hanging: a process whose output fills the pipe buffer blocks forever waiting
 * for someone to read it, and this agent's whole job is running a tool whose
 * output on a real repository is hundreds of kilobytes. Reading stdout only
 * after {@code waitFor} returns is the classic version of this bug, and it
 * does not show up on small fixtures.
 */
public final class SystemProcessRunner implements ProcessRunner {

    private static final String TIMEOUT_MESSAGE = "did not respond within %ds.";
    private static final String UNAVAILABLE_MESSAGE = "'%s' is not installed or not on the PATH.";
    private static final String COULD_NOT_START = "Could not start '%s': %s";
    private static final String NO_SUCH_FILE = "error=2,";
    private static final int STREAM_COUNT = 3;
    private static final String NO_INPUT = "";

    /**
     * Runs one command to completion and collects everything it said.
     *
     * <p>A command that cannot be started and one that overran its timeout are
     * both reported as outcomes rather than thrown, so a caller never has to
     * tell a missing executable from a failing one by catching.
     *
     * @param command the command and its arguments
     * @param workingDirectory where to run it, {@code null} for wherever this
     *     process already is — which is what a command that reads out of an
     *     image rather than off the disk wants
     * @param environment extra variables added to the inherited environment
     * @param timeout how long it is given before being killed
     * @return what it produced, or why it never got there
     */
    @Override
    public ProcessOutcome run(
            List<String> command, Path workingDirectory, Map<String, String> environment, Duration timeout) {
        return run(command, workingDirectory, environment, timeout, NO_INPUT);
    }

    /**
     * Runs a command, writing the given text to its standard input and then
     * closing it. With no input, standard input is closed at once, so a
     * command that would read it sees the end rather than waiting forever.
     *
     * @param command the executable and its arguments
     * @param workingDirectory where to run it
     * @param environment extra environment variables
     * @param timeout how long to wait before killing it
     * @param standardInput what to write, empty for nothing
     * @return what it produced, including whether it ran at all
     */
    @Override
    public ProcessOutcome run(List<String> command, Path workingDirectory, Map<String, String> environment,
            Duration timeout, String standardInput) {
        ProcessBuilder builder = new ProcessBuilder(Platform.resolved(command));
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        builder.environment().putAll(environment);
        ExecutorService drains = Executors.newFixedThreadPool(STREAM_COUNT);
        try {
            return runAndCollect(builder, timeout, drains, standardInput);
        } catch (IOException exception) {
            return ProcessOutcome.unavailable(launchFailure(command.get(0), exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ProcessOutcome.unavailable(exception.getMessage());
        } finally {
            drains.shutdownNow();
        }
    }

    /**
     * Starts the process and reads both its streams while it runs.
     *
     * <p>The two drains are submitted before waiting, which is what stops a
     * chatty command from filling its pipe buffer and hanging forever.
     *
     * @param builder the configured process
     * @param timeout how long it is given before being killed
     * @param drains the threads the streams are read and written on
     * @param standardInput what to write to the process's standard input
     * @return what it produced, or the timeout that ended it
     * @throws IOException when the process cannot be started or read
     * @throws InterruptedException when the wait is interrupted
     */
    private ProcessOutcome runAndCollect(
            ProcessBuilder builder, Duration timeout, ExecutorService drains, String standardInput)
            throws IOException, InterruptedException {
        Process process = builder.start();
        Future<String> standardOutput = drains.submit(() -> drain(process.getInputStream()));
        Future<String> standardError = drains.submit(() -> drain(process.getErrorStream()));
        drains.submit(() -> feed(process.getOutputStream(), standardInput));
        if (!process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return ProcessOutcome.timedOut(String.format(TIMEOUT_MESSAGE, timeout.toSeconds()));
        }
        return ProcessOutcome.completed(process.exitValue(), await(standardOutput), await(standardError));
    }

    /**
     * Why a command could not be started at all.
     *
     * <p>Only a missing executable means "not installed". The same exception
     * also carries an argument list too long for the kernel, or a file that
     * is not executable, and calling those "not installed" sent people
     * looking for a program that was there all along.
     *
     * @param executable the program that was asked for
     * @param exception what starting it threw
     * @return the reason, in words a person can act on
     */
    private static String launchFailure(String executable, IOException exception) {
        String message = String.valueOf(exception.getMessage());
        return message.contains(NO_SUCH_FILE)
                ? String.format(UNAVAILABLE_MESSAGE, executable)
                : String.format(COULD_NOT_START, executable, message);
    }

    /**
     * Writes the input to a process and closes its standard input, so it
     * reads the end rather than waiting for more.
     *
     * @param stream the process's standard input
     * @param input what to write
     * @return nothing; a callable so it can run beside the drains
     * @throws IOException when the process stopped reading, which is its own
     *     business and ends in its exit code
     */
    private static Void feed(OutputStream stream, String input) throws IOException {
        try (OutputStream open = stream) {
            open.write(input == null ? new byte[0] : input.getBytes(StandardCharsets.UTF_8));
        }
        return null;
    }

    /**
     * Reads one stream of the process to its end.
     *
     * <p>Closes the stream afterwards, since a drain that leaks its handle
     * leaks one per invocation and this runner is called in a loop.
     *
     * @param stream the process stream to read
     * @return everything it wrote, decoded as UTF-8
     * @throws IOException when the stream cannot be read
     */
    private static String drain(InputStream stream) throws IOException {
        try (InputStream open = stream) {
            return new String(open.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Waits for one drain to finish and takes what it read.
     *
     * <p>A drain that failed yields no text rather than propagating: the
     * command's exit code is the answer that matters, and losing its output is
     * not a reason to lose its verdict too.
     *
     * @param stream the drain to wait for
     * @return what it read, empty when the read itself failed
     * @throws InterruptedException when the wait is interrupted
     */
    private static String await(Future<String> stream) throws InterruptedException {
        try {
            return stream.get();
        } catch (java.util.concurrent.ExecutionException exception) {
            return "";
        }
    }
}
