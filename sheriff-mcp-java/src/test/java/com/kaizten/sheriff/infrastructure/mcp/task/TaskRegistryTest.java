package com.kaizten.sheriff.infrastructure.mcp.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The registry behind the task ids: what a client sees of a task while it
 * runs, after it finishes, and after it fails.
 */
final class TaskRegistryTest {

    @TempDir
    Path base;

    @Test
    @DisplayName("a background task answers at once with its id, and completes later")
    void aSubmittedTaskRunsInTheBackground() throws InterruptedException {
        CountDownLatch release = new CountDownLatch(1);
        try (TaskRegistry registry = new TaskRegistry(base)) {
            Task accepted = registry.submit("sheriff_autofix component=app", task -> {
                await(release);
                return "done";
            });

            assertEquals(TaskState.QUEUED, accepted.state());
            assertTrue(registry.find(accepted.id()).orElseThrow().state().compareTo(TaskState.COMPLETED) < 0);
            release.countDown();
            registry.close();
            Task finished = registry.find(accepted.id()).orElseThrow();
            assertEquals(TaskState.COMPLETED, finished.state());
            assertEquals("done", finished.result());
        }
    }

    @Test
    @DisplayName("a background task that throws is failed, with the reason as its result")
    void aFailingBackgroundTaskIsRecordedAsFailed() {
        try (TaskRegistry registry = new TaskRegistry(base)) {
            Task accepted = registry.submit("sheriff_autofix", task -> {
                throw new IllegalStateException("no backend");
            });
            registry.close();
            Task failed = registry.find(accepted.id()).orElseThrow();
            assertEquals(TaskState.FAILED, failed.state());
            assertTrue(failed.result().contains("no backend"), failed.result());
        }
    }

    @Test
    @DisplayName("a server stopped mid-task, as Codex stops one when its session ends, leaves it interrupted, not running")
    void aServerStoppedMidTaskInterruptsItsTasks() throws IOException, InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (TaskRegistry registry = new TaskRegistry(base)) {
            Task running = registry.submit("sheriff_autofix component=app", task -> {
                started.countDown();
                await(release);
                return "done";
            });
            Task queued = registry.submit("sheriff_autofix component=web", task -> "done");
            assertTrue(started.await(10, TimeUnit.SECONDS));
            registry.interruptUnfinished();
            for (Task accepted : List.of(running, queued)) {
                Task interrupted = registry.find(accepted.id()).orElseThrow();
                assertEquals(TaskState.INTERRUPTED, interrupted.state());
                assertTrue(interrupted.state().finished());
                assertTrue(interrupted.result().contains("stopped before this task finished"), interrupted.result());
                assertTrue(Files.readString(interrupted.directory().resolve("task.json")).contains("interrupted"));
            }
            release.countDown();
        }
    }

    @Test
    @DisplayName("Codex kills the loop's git too: the failure that follows must not overwrite 'interrupted'")
    void nothingOverwritesAnInterruptedTask() throws IOException, InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (TaskRegistry registry = new TaskRegistry(base)) {
            Task accepted = registry.submit("sheriff_autofix component=app", task -> {
                started.countDown();
                await(release);
                throw new IllegalStateException("git checkout failed: exit code 143");
            });
            assertTrue(started.await(10, TimeUnit.SECONDS));
            registry.interruptUnfinished();
            release.countDown();
            registry.close();
            Task task = registry.find(accepted.id()).orElseThrow();
            assertEquals(TaskState.INTERRUPTED, task.state());
            assertTrue(Files.readString(task.directory().resolve("task.json")).contains("interrupted"));
        }
    }

    @Test
    @DisplayName("the signal that stops the server can kill the loop's git first: 1 run in 5 ended failed, not interrupted")
    void aFailureAsTheServerStopsIsAnInterruption() throws IOException, InterruptedException {
        CountDownLatch failing = new CountDownLatch(1);
        try (TaskRegistry registry = new TaskRegistry(base)) {
            Task accepted = registry.submit("sheriff_autofix component=app", task -> {
                failing.countDown();
                throw new IllegalStateException("git checkout failed: exit code 143");
            });
            assertTrue(failing.await(10, TimeUnit.SECONDS));
            registry.interruptUnfinished();
            registry.close();
            Task task = registry.find(accepted.id()).orElseThrow();
            assertEquals(TaskState.INTERRUPTED, task.state());
            assertTrue(Files.readString(task.directory().resolve("task.json")).contains("interrupted"));
        }
    }

    @Test
    @DisplayName("a task still queued when the server stops never starts: it would branch and run Sheriff unrecorded")
    void aQueuedTaskDoesNotStartOnceTheServerStops() throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean secondRan = new AtomicBoolean();
        try (TaskRegistry registry = new TaskRegistry(base)) {
            registry.submit("sheriff_autofix component=app", task -> {
                started.countDown();
                await(release);
                return "done";
            });
            Task queued = registry.submit("sheriff_autofix component=web", task -> {
                secondRan.set(true);
                return "done";
            });
            assertTrue(started.await(10, TimeUnit.SECONDS));
            registry.interruptUnfinished();
            release.countDown();
            registry.close();
            assertFalse(secondRan.get());
            assertEquals(TaskState.INTERRUPTED, registry.find(queued.id()).orElseThrow().state());
        }
    }

    @Test
    @DisplayName("a record is moved into place whole, and nothing half-written is left beside it")
    void recordsAreWrittenWhole() throws IOException {
        try (TaskRegistry registry = new TaskRegistry(base)) {
            Task done = registry.run("sheriff_test", task -> "0 errors");
            try (Stream<Path> files = Files.list(done.directory())) {
                assertEquals(List.of("task.json"), files.map(file -> file.getFileName().toString()).toList());
            }
        }
    }

    @Test
    @DisplayName("a finished task is left as it ended")
    void aFinishedTaskIsNotInterrupted() {
        try (TaskRegistry registry = new TaskRegistry(base)) {
            Task done = registry.run("sheriff_test", task -> "0 errors");
            registry.interruptUnfinished();
            assertEquals(TaskState.COMPLETED, registry.find(done.id()).orElseThrow().state());
        }
    }

    @Test
    @DisplayName("a task run in place is recorded, and a failure still reaches the caller")
    void aTaskRunInPlaceRethrows() {
        try (TaskRegistry registry = new TaskRegistry(base)) {
            TaskFailure failure = assertThrows(TaskFailure.class,
                    () -> registry.run("sheriff_test", task -> {
                        throw new IllegalArgumentException("nothing analyzed");
                    }));
            assertEquals(TaskState.FAILED, registry.all().get(0).state());
            assertTrue(failure.getCause() instanceof IllegalArgumentException, failure.toString());
            assertEquals(registry.all().get(0).id(), failure.task().id());
        }
    }

    @Test
    @DisplayName("each task has its own folder with a task.json recording id, command and state")
    void everyTaskIsWrittenToItsFolder() throws IOException {
        try (TaskRegistry registry = new TaskRegistry(base)) {
            Task task = registry.run("sheriff_test component=app profile=JAVA", running -> "0 errors");

            assertEquals(base.resolve(task.id()), task.directory());
            String record = Files.readString(task.directory().resolve("task.json"));
            assertTrue(record.contains("\"command\" : \"sheriff_test component=app profile=JAVA\""), record);
            assertTrue(record.contains("\"state\" : \"completed\""), record);
        }
    }

    @Test
    void theListingIsNewestFirst() {
        try (TaskRegistry registry = new TaskRegistry(base)) {
            Task first = registry.run("first", task -> "");
            Task second = registry.run("second", task -> "");
            assertEquals(List.of(second.id(), first.id()), registry.all().stream().map(Task::id).toList());
        }
    }

    /**
     * Writes a folder that looks exactly like one a previous session left.
     */
    private Path leftoverFolder(String id, long modifiedAt) throws IOException {
        Path folder = base.resolve(id);
        Files.createDirectories(folder);
        Files.writeString(folder.resolve("task.json"), "{}");
        Files.writeString(folder.resolve("sheriff_errors.json"), "{}");
        Files.setLastModifiedTime(folder.resolve("task.json"), FileTime.fromMillis(modifiedAt));
        return folder;
    }

    @Test
    @DisplayName("folders left by earlier sessions are pruned to the retained count, oldest first")
    void prunesWhatEarlierSessionsLeft() throws IOException {
        int leftover = TaskRegistry.RETAINED_FOLDERS + 5;
        List<Path> folders = new ArrayList<>();
        for (int index = 0; index < leftover; index++) {
            folders.add(leftoverFolder(String.format("%08x", index), index + 1L));
        }
        assertEquals(leftover, countFolders());

        try (TaskRegistry registry = new TaskRegistry(base)) {
            registry.run("sheriff_test", task -> "");
        }

        assertEquals(TaskRegistry.RETAINED_FOLDERS + 1, countFolders(), "the retained ones, plus the new task");
        for (int oldest = 0; oldest < 5; oldest++) {
            assertTrue(Files.notExists(folders.get(oldest)), "the oldest should have gone: " + folders.get(oldest));
        }
        assertTrue(Files.isDirectory(folders.get(leftover - 1)), "the newest leftover should have stayed");
    }

    @Test
    @DisplayName("pruning takes the whole folder, not just the record")
    void removesTheSheriffJsonToo() throws IOException {
        Path oldest = leftoverFolder("00000000", 1L);
        for (int index = 1; index <= TaskRegistry.RETAINED_FOLDERS; index++) {
            leftoverFolder(String.format("%08x", index), index + 1L);
        }

        try (TaskRegistry registry = new TaskRegistry(base)) {
            registry.run("sheriff_test", task -> "");
        }

        assertTrue(Files.notExists(oldest.resolve("sheriff_errors.json")));
        assertTrue(Files.notExists(oldest));
    }

    @Test
    @DisplayName("pruning only ever removes folders this registry wrote")
    void leavesAnythingElseAlone() throws IOException {
        Path stranger = base.resolve("no-soy-una-tarea");
        Files.createDirectories(stranger);
        Files.writeString(stranger.resolve("importante.txt"), "no me borres");
        Path idShapedButEmpty = base.resolve("deadbeef");
        Files.createDirectories(idShapedButEmpty);
        for (int index = 0; index <= TaskRegistry.RETAINED_FOLDERS; index++) {
            leftoverFolder(String.format("%08x", index), index + 1L);
        }

        try (TaskRegistry registry = new TaskRegistry(base)) {
            registry.run("sheriff_test", task -> "");
        }

        assertTrue(Files.exists(stranger.resolve("importante.txt")), "a folder it did not write was removed");
        assertTrue(Files.isDirectory(idShapedButEmpty), "an id-shaped folder with no task.json was removed");
    }

    @Test
    @DisplayName("nothing is removed while there is room, and never what this session just wrote")
    void keepsEverythingBelowTheLimit() throws IOException {
        leftoverFolder("00000000", 1L);

        try (TaskRegistry registry = new TaskRegistry(base)) {
            Task first = registry.run("sheriff_test", task -> "");
            Task second = registry.run("sheriff_test", task -> "");
            assertEquals(3, countFolders());
            assertTrue(Files.isRegularFile(first.directory().resolve("task.json")));
            assertTrue(Files.isRegularFile(second.directory().resolve("task.json")));
        }
    }

    private long countFolders() throws IOException {
        try (Stream<Path> entries = Files.list(base)) {
            return entries.filter(Files::isDirectory).count();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
