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
import java.util.regex.Pattern;

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
 *
 * <p>What a stream says is kept in memory up to a limit far above any real
 * output (tens of megabytes): its start and its end, with the middle left out
 * and a line saying how much. A process that writes without end, such as a
 * test stuck in a loop that logs, would otherwise take the server's memory
 * with it. The end is what Maven and Sheriff close on, and the start where
 * Maven puts the cause.
 */
public final class SystemProcessRunner implements ProcessRunner {

    private static final String TIMEOUT_MESSAGE = "did not respond within %ds.";
    private static final String CANCELLED_MESSAGE = "was cancelled, and stopped.";
    private static final String UNAVAILABLE_MESSAGE = "'%s' is not installed or not on the PATH.";
    private static final String COULD_NOT_START = "Could not start '%s': %s";
    private static final String NO_SUCH_FILE_PATTERN = "error(=|: )2\\b";
    private static final Pattern NO_SUCH_FILE = Pattern.compile(NO_SUCH_FILE_PATTERN);
    private static final int STREAM_COUNT = 3;
    private static final String NO_INPUT = "";
    private static final int MEBIBYTE = 1024 * 1024;
    private static final int HEAD_KEPT = 8 * MEBIBYTE;
    private static final int TAIL_KEPT = 24 * MEBIBYTE;
    private static final int CHUNK = 64 * 1024;
    private static final int START = 0;
    private static final int END_OF_STREAM = -1;
    private static final String LEFT_OUT = "%n[... %d bytes of output left out ...]%n";
    private final int headKept;
    private final int tailKept;

    /**
     * A runner that keeps the first 8 MiB and the last 24 MiB of each stream.
     */
    public SystemProcessRunner() {
        this(HEAD_KEPT, TAIL_KEPT);
    }

    /**
     * A runner that keeps other amounts of each stream, for the tests.
     *
     * @param headKept how many bytes of a stream's start are kept
     * @param tailKept how many bytes of its end are kept, at least one
     */
    SystemProcessRunner(int headKept, int tailKept) {
        this.headKept = headKept;
        this.tailKept = tailKept;
    }

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
    public ProcessOutcome run(List<String> command,
            Path workingDirectory, Map<String, String> environment, Duration timeout) {
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
        ProcessObservers.starting(command, workingDirectory);
        long started = System.nanoTime();
        ProcessOutcome outcome = launched(command, workingDirectory, environment, timeout, standardInput);
        ProcessObservers.ran(command, workingDirectory, outcome, Duration.ofNanos(System.nanoTime() - started));
        return outcome;
    }

    /**
     * Runs a command as {@link #run} does, before it is told to the
     * observer of every command.
     *
     * @param command the executable and its arguments
     * @param workingDirectory where to run it
     * @param environment extra environment variables
     * @param timeout how long to wait before killing it
     * @param standardInput what to write, empty for nothing
     * @return what it produced, including whether it ran at all
     */
    private ProcessOutcome launched(List<String> command, Path workingDirectory, Map<String, String> environment,
            Duration timeout, String standardInput) {
        ProcessBuilder builder = new ProcessBuilder(Platform.quotedForCommandLine(Platform.resolved(Platform.dockerCommand(command)),
                Platform.windows()));
        if (workingDirectory != null) {
            builder.directory(workingDirectory.toFile());
        }
        builder.environment().putAll(environment);
        if (Thread.currentThread().isInterrupted()) {
            return ProcessOutcome.cancelled(CANCELLED_MESSAGE);
        }
        ExecutorService drains = Executors.newFixedThreadPool(STREAM_COUNT);
        try {
            return runAndCollect(builder, timeout, drains, standardInput);
        } catch (IOException exception) {
            return ProcessOutcome.unavailable(launchFailure(command.get(0), exception));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return ProcessOutcome.cancelled(CANCELLED_MESSAGE);
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
     * @throws InterruptedException when the wait is interrupted, with the
     *     process and its children killed
     */
    private ProcessOutcome runAndCollect(ProcessBuilder builder,
            Duration timeout, ExecutorService drains, String standardInput)
            throws IOException, InterruptedException {
        Process process = builder.start();
        Future<String> standardOutput = drains.submit(() -> drain(process.getInputStream(), headKept, tailKept));
        Future<String> standardError = drains.submit(() -> drain(process.getErrorStream(), headKept, tailKept));
        drains.submit(() -> feed(process.getOutputStream(), standardInput));
        boolean finished;
        try {
            finished = process.waitFor(timeout.toSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            stop(process);
            throw exception;
        }
        if (!finished) {
            stop(process);
            return ProcessOutcome.timedOut(String.format(TIMEOUT_MESSAGE, timeout.toSeconds()));
        }
        return ProcessOutcome.completed(process.exitValue(), await(standardOutput), await(standardError));
    }

    /**
     * Kills a process and every process it started, as a child is not
     * killed with its parent: a shell's {@code sleep} outlived the shell
     * (measured in {@code SystemProcessRunnerTests}), and Maven runs the
     * tests in JVMs it forks.
     *
     * @param process the process to kill
     */
    private static void stop(Process process) {
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    /**
     * Why a command could not be started at all.
     *
     * <p>Only a missing executable means "not installed". The same exception
     * also carries an argument list too long for the kernel, or a file that
     * is not executable, and calling those "not installed" sent people
     * looking for a program that was there all along.
     *
     * <p>A missing executable is error 2, written {@code error=2,} by Java 17
     * and by every Java on Windows, and {@code Exec failed, error: 2 (No such
     * file or directory)} on Linux by Temurin 21.0.12, which called a missing
     * {@code docker} a program that could not start (13 October).
     *
     * @param executable the program that was asked for
     * @param exception what starting it threw
     * @return the reason, in words a person can act on
     */
    static String launchFailure(String executable, IOException exception) {
        String message = String.valueOf(exception.getMessage());
        return NO_SUCH_FILE.matcher(message).find()
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
     * Reads one stream of the process to its end, keeping its start and its
     * end: past the two limits, the middle is left out, and a line says how
     * many bytes.
     *
     * <p>Only a stream that fills its start allocates the room for its end.
     * Closes the stream afterwards, since a drain that leaks its handle leaks
     * one per invocation and this runner is called in a loop.
     *
     * @param stream the process stream to read
     * @param headKept how many bytes of its start are kept
     * @param tailKept how many bytes of its end are kept, at least one
     * @return what it wrote, decoded as UTF-8, whole when it fits
     * @throws IOException when the stream cannot be read
     */
    static String drain(InputStream stream, int headKept, int tailKept) throws IOException {
        try (InputStream open = stream) {
            byte[] head = open.readNBytes(headKept);
            String start = new String(head, StandardCharsets.UTF_8);
            if (head.length < headKept) {
                return start;
            }
            byte[] tail = new byte[tailKept];
            byte[] chunk = new byte[CHUNK];
            long after = START;
            for (int read = open.read(chunk); read != END_OF_STREAM; read = open.read(chunk)) {
                after = kept(chunk, read, tail, after);
            }
            if (after <= tailKept) {
                return start + new String(tail, START, (int) after, StandardCharsets.UTF_8);
            }
            int oldest = (int) (after % tailKept);
            byte[] end = new byte[tailKept];
            System.arraycopy(tail, oldest, end, START, tailKept - oldest);
            System.arraycopy(tail, START, end, tailKept - oldest, oldest);
            return start + String.format(LEFT_OUT, after - tailKept) + new String(end, StandardCharsets.UTF_8);
        }
    }

    /**
     * Writes what was read into the ring that keeps a stream's end.
     *
     * @param chunk what was read
     * @param read how many of its bytes
     * @param tail the ring
     * @param written how many bytes the ring was given before
     * @return how many it has been given now
     */
    private static long kept(byte[] chunk, int read, byte[] tail, long written) {
        long total = written;
        for (int offset = START; offset < read;) {
            int position = (int) (total % tail.length);
            int length = Math.min(read - offset, tail.length - position);
            System.arraycopy(chunk, offset, tail, position, length);
            offset += length;
            total += length;
        }
        return total;
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
