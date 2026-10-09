package com.kaizten.sheriff.infrastructure.mcp.task;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Every task this server has run in this session, and the one thread that
 * runs the long ones.
 *
 * <p>The client calls, gets an id back, and asks about that id later: the
 * shape the repair loop needs, since it takes minutes and a tool call that
 * blocks for minutes leaves the model waiting with nothing to say. The short
 * tools run in the caller's thread and are recorded the same way, so every
 * call that ran Sheriff has an id and a folder with Sheriff's own JSON.
 *
 * <p>Each task is written to {@code <directory>/task.json} on every change,
 * next to the {@code sheriff_errors.json} and
 * {@code sheriff_tracked_files.json} its analyses exported. That record is the
 * output; this map is only the session's index of it.
 *
 * <p>Background tasks run one at a time. Two repair loops on one repository
 * would each create a branch and commit onto whichever the other left
 * checked out.
 *
 * <p>Old folders are pruned, because one is written per call that runs
 * Sheriff and nothing else would ever remove them: a project checked a few
 * times a day leaves thousands behind in a directory nobody looks at. Only
 * this registry's own folders are touched, and only the oldest beyond
 * {@link #RETAINED_FOLDERS}.
 */
public final class TaskRegistry implements AutoCloseable {

    private static final String TASK_FILE = "task.json";
    private static final String PARTIAL_PREFIX = "task-";
    private static final String PARTIAL_SUFFIX = ".json.partial";
    private static final String THREAD_NAME = "sheriff-task";
    private static final int ID_LENGTH = 8;
    private static final int ID_START = 0;
    private static final long DRAIN_HOURS = 6;
    private static final long STOP_GRACE_MILLIS = 200;
    private static final String FAILURE = "%s: %s";
    private static final String INTERRUPTED = """
            The server was stopped before this task finished, most likely because its client \
            ended the session. It did not complete: check the repository before relying on \
            anything it changed. A repair loop may have left it on its own branch with a pass \
            uncommitted, and an analysis cut short leaves Sheriff's sheriff_*.json files at \
            the root of what it mounts, which the next run clears by itself.""";

    /**
     * How many finished task folders are kept on disk.
     *
     * <p>Enough that anything asked about in a session is still there, few
     * enough that the directory stays readable by a person.
     */
    static final int RETAINED_FOLDERS = 50;

    private static final String ID_PATTERN = "[0-9a-f]{" + ID_LENGTH + "}";

    private final Path base;
    private final Supplier<Map<String, String>> provenance;
    private final Map<String, Task> tasks = Collections.synchronizedMap(new LinkedHashMap<>());
    private final ExecutorService background = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, THREAD_NAME);
        thread.setDaemon(true);
        return thread;
    });
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicBoolean pruned = new AtomicBoolean();
    private final Object writing = new Object();
    private boolean stopping;

    /**
     * A registry keeping its tasks under one directory.
     *
     * @param base where each task gets its own folder, created on first use
     */
    public TaskRegistry(Path base) {
        this(base, Map::of);
    }

    /**
     * A registry keeping its tasks under one directory, each recording what
     * it ran with.
     *
     * @param base where each task gets its own folder, created on first use
     * @param provenance what a task runs with, asked once as it is accepted:
     *     an image pulled halfway through a session must not relabel the
     *     tasks that ran before it
     */
    public TaskRegistry(Path base, Supplier<Map<String, String>> provenance) {
        this.base = base;
        this.provenance = provenance;
    }

    /**
     * Runs a task now, in the caller's thread, and records it.
     *
     * @param command what is being run, as the client will see it
     * @param work the work, given the task so it can export into its folder
     * @return the task as it finished
     * @throws TaskFailure when the work threw, after recording the task as
     *     failed; its cause is what the work threw
     */
    public Task run(String command, Function<Task, String> work) {
        Task task = record(Task.accepted(newId(), command, provenance.get(), TaskState.RUNNING, null));
        return execute(task, work, true);
    }

    /**
     * Accepts a task to run in the background, and returns at once.
     *
     * @param command what is being run, as the client will see it
     * @param work the work, given the task so it can export into its folder
     * @return the task as accepted, queued
     */
    public Task submit(String command, Function<Task, String> work) {
        Task task = record(Task.accepted(newId(), command, provenance.get(), TaskState.QUEUED, null));
        background.submit(() -> execute(task, work, false));
        return task;
    }

    /**
     * One task, as it stands now.
     *
     * @param id the id the client was given
     * @return that task, or empty when this session has none with that id
     */
    public Optional<Task> find(String id) {
        return Optional.ofNullable(tasks.get(id));
    }

    /**
     * Every task of this session, newest first.
     *
     * @return those tasks
     */
    public List<Task> all() {
        List<Task> listed;
        synchronized (tasks) {
            listed = new ArrayList<>(tasks.values());
        }
        Collections.reverse(listed);
        return listed;
    }

    /**
     * Lets queued and running tasks finish, then stops the thread.
     *
     * <p>Called when the client disconnects. A repair loop cut off halfway
     * would leave the repository on its branch with a pass uncommitted; one
     * allowed to finish leaves it reviewable and its record complete.
     */
    @Override
    public void close() {
        background.shutdown();
        try {
            background.awaitTermination(DRAIN_HOURS, TimeUnit.HOURS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Records every task that has not finished as interrupted.
     *
     * <p>For the server's shutdown hook. A client that disconnects closes
     * standard input and {@link #close} lets the tasks finish; a client that
     * stops the server instead, as Codex does when a session ends (SIGTERM,
     * then SIGKILL a moment later), leaves no time for that, and the record
     * of a task cut off that way used to say {@code running} for good.
     *
     * <p>From here on nothing overwrites those records. Codex signals the
     * server's whole process group, so a repair loop's {@code git} dies too
     * and the loop, still running for a moment, recorded that as the task's
     * failure: over this record, or halfway through its own write when the
     * JVM halted, which left an empty {@code task.json}.
     */
    public void interruptUnfinished() {
        synchronized (writing) {
            stopping = true;
            for (Task task : all()) {
                if (!task.state().finished()) {
                    write(task.finishedAs(TaskState.INTERRUPTED, INTERRUPTED));
                }
            }
        }
    }

    /**
     * Runs one task's work and records how it ended; once the server is
     * stopping, a task still queued is left as it was interrupted and never
     * starts, where it would have branched and run Sheriff with no record
     * saying so.
     *
     * @param accepted the task as accepted
     * @param work the work
     * @param rethrow whether a failure goes back to the caller as well
     * @return the task as it finished
     */
    private Task execute(Task accepted, Function<Task, String> work, boolean rethrow) {
        synchronized (writing) {
            if (stopping) {
                return tasks.getOrDefault(accepted.id(), accepted);
            }
        }
        Task running = record(accepted.running());
        try {
            return record(running.finishedAs(TaskState.COMPLETED, work.apply(running)));
        } catch (RuntimeException exception) {
            if (!rethrow) {
                awaitAStopInProgress();
            }
            Task failed = record(running.finishedAs(TaskState.FAILED, String.format(
                    FAILURE, exception.getClass().getSimpleName(), exception.getMessage())));
            if (rethrow) {
                throw new TaskFailure(failed, exception);
            }
            return failed;
        }
    }

    /**
     * Gives a stop that is already under way the moment it needs to record
     * this task as interrupted, before its failure is recorded.
     *
     * <p>Codex signals the server's whole process group, so a command the
     * task was running can die of the same signal a moment before the
     * server's shutdown hook starts. Recorded at once, that death read as the
     * task's failure, and the hook then left the finished task alone: one run
     * in five ended {@code failed}, exit code 143, instead of
     * {@code interrupted}. The pause is short enough to fit inside the 0.3 s
     * Codex allows before SIGKILL, and only background tasks take it: a
     * call the client is waiting on answers its failure at once.
     */
    private static void awaitAStopInProgress() {
        try {
            Thread.sleep(STOP_GRACE_MILLIS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Keeps a task's latest state, in memory and in its folder.
     *
     * <p>Writing the file is best effort: a task whose record cannot be saved
     * still runs and can still be asked about in this session.
     *
     * @param task the task to keep
     * @return the same task, with its folder filled in; once the server is
     *     stopping, the task as it was interrupted instead
     */
    private Task record(Task task) {
        Task located = task.directory() != null ? task : new Task(task.id(), task.command(), task.provenance(),
                task.state(), task.result(), base.resolve(task.id()), task.created(), task.finished());
        pruneOnce();
        synchronized (writing) {
            if (stopping) {
                return tasks.getOrDefault(located.id(), located);
            }
            write(located);
        }
        return located;
    }

    /**
     * Keeps a task's state in memory and writes its record whole or not at
     * all: to a file of its own first, then moved over the old one, so a
     * process killed halfway leaves the previous record rather than an
     * empty one.
     *
     * @param task the task, with its folder filled in
     */
    private void write(Task task) {
        tasks.put(task.id(), task);
        Path partial = null;
        try {
            Files.createDirectories(task.directory());
            partial = Files.createTempFile(task.directory(), PARTIAL_PREFIX, PARTIAL_SUFFIX);
            json.writerWithDefaultPrettyPrinter().writeValue(partial.toFile(), task.asMap());
            replace(partial, task.directory().resolve(TASK_FILE));
        } catch (IOException | RuntimeException exception) {
            deleteQuietly(partial);
        }
    }

    /**
     * Moves a finished record into place, atomically where the file system
     * can.
     *
     * @param partial the record just written
     * @param target where it belongs
     * @throws IOException when it cannot be moved at all
     */
    private static void replace(Path partial, Path target) throws IOException {
        try {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Removes a file that is no longer wanted, if there is one.
     *
     * @param file the file, or {@code null}
     */
    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException exception) {
            return;
        }
    }

    /**
     * Removes the oldest task folders, once per registry.
     *
     * <p>Best effort, and deliberately timid: it only considers directories
     * directly under the base whose name is one of this registry's ids and
     * which hold a {@code task.json}, so nothing it did not write can be
     * removed. Anything it cannot read or delete is left alone.
     */
    private void pruneOnce() {
        if (!pruned.compareAndSet(false, true)) {
            return;
        }
        List<Path> folders;
        try (Stream<Path> entries = Files.list(base)) {
            folders = entries.filter(TaskRegistry::isTaskFolder)
                    .sorted(Comparator.comparing(TaskRegistry::modifiedAt).reversed())
                    .toList();
        } catch (IOException exception) {
            return;
        }
        for (Path folder : folders.stream().skip(RETAINED_FOLDERS).toList()) {
            delete(folder);
        }
    }

    /**
     * Whether a path is a folder this registry wrote.
     *
     * @param path the candidate
     * @return {@code true} when it is a directory named like an id and
     *     holding a task record
     */
    private static boolean isTaskFolder(Path path) {
        return Files.isDirectory(path)
                && path.getFileName().toString().matches(ID_PATTERN)
                && Files.isRegularFile(path.resolve(TASK_FILE));
    }

    /**
     * When a task folder was last written.
     *
     * @param folder the folder
     * @return its record's modification time, or the epoch when unreadable,
     *     which sorts it oldest and so removes it first
     */
    private static FileTime modifiedAt(Path folder) {
        try {
            return Files.getLastModifiedTime(folder.resolve(TASK_FILE));
        } catch (IOException exception) {
            return FileTime.fromMillis(0);
        }
    }

    /**
     * Removes one task folder and the files in it.
     *
     * @param folder the folder to remove
     */
    private static void delete(Path folder) {
        try (Stream<Path> contents = Files.list(folder)) {
            for (Path file : contents.toList()) {
                Files.deleteIfExists(file);
            }
        } catch (IOException exception) {
            return;
        }
        try {
            Files.deleteIfExists(folder);
        } catch (IOException exception) {
            return;
        }
    }

    /**
     * A short id that is not already taken.
     *
     * @return that id
     */
    private String newId() {
        String id = UUID.randomUUID().toString().substring(ID_START, ID_LENGTH);
        return tasks.containsKey(id) ? newId() : id;
    }
}
