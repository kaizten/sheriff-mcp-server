package com.kaizten.sheriff.infrastructure.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * One Sheriff at a time per mount.
 */
class MountLockTests {

    @TempDir
    private Path mount;

    @Test
    @DisplayName("a sequence can hold the lock while each run inside it asks again")
    void isReentrant() {
        String result = MountLock.holding(mount, () -> MountLock.holding(mount, () -> "inner"));
        assertEquals("inner", result);
    }

    @Test
    @DisplayName("a second run on the same mount waits for the first to finish")
    void serializesRunsOnOneMount() throws InterruptedException {
        List<String> events = new CopyOnWriteArrayList<>();
        CountDownLatch firstInside = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread first = new Thread(() -> MountLock.holding(mount, () -> {
            events.add("first in");
            firstInside.countDown();
            await(release);
            events.add("first out");
            return null;
        }));
        first.start();
        assertTrue(firstInside.await(5, TimeUnit.SECONDS));
        Thread second = new Thread(() -> MountLock.holding(mount, () -> events.add("second in")));
        second.start();
        Thread.sleep(200);
        release.countDown();
        first.join(5000);
        second.join(5000);
        assertEquals(List.of("first in", "first out", "second in"), events);
    }

    @Test
    @DisplayName("the lock file lives outside the mount, where the scope check never sees it")
    void theLockFileIsNotInTheMount() throws Exception {
        MountLock.holding(mount, () -> null);
        assertTrue(!MountLock.lockFileFor(mount.toAbsolutePath().normalize()).startsWith(mount));
        assertTrue(MountLock.lockFileFor(mount.toAbsolutePath().normalize()).getParent().getFileName().toString()
                .startsWith("sheriff-locks-"), "one user's lock folder could not be written by another");
        try (var entries = Files.list(mount)) {
            assertEquals(0, entries.count());
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }
}
