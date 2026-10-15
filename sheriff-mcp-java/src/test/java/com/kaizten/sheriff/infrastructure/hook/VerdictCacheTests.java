package com.kaizten.sheriff.infrastructure.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import com.kaizten.sheriff.domain.valueobject.TrackedFile;
import com.kaizten.sheriff.infrastructure.config.Composition;
import com.kaizten.sheriff.infrastructure.config.Configuration;
import com.kaizten.sheriff.infrastructure.docker.MountLock;
import com.kaizten.sheriff.infrastructure.mcp.SheriffRunner;
import com.kaizten.sheriff.infrastructure.process.FakeProcessRunner;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the record of verdicts the MCP's tools write and the hooks read.
 *
 * <p>What matters is when a verdict must <em>not</em> be served: for other
 * sources, another profile, another project with a module of the same name,
 * after its lifetime, or from an analysis that did not see the files as they
 * are. A stale "clean" lets a turn end with errors; a missed one only costs a
 * run of Sheriff.
 */
class VerdictCacheTests {

    private static final String PROFILE = "JAVA";

    @TempDir
    private Path mount;

    @TempDir
    private Path folder;

    private Path app;
    private VerdictCache cache;

    @BeforeEach
    void layOutAComponent() throws IOException {
        app = mount.resolve("app");
        Files.createDirectories(app.resolve("src"));
        Files.writeString(app.resolve("src/A.java"), "class A {}");
        cache = new VerdictCache(folder, Duration.ofMinutes(15));
    }

    private static AnalysisResult errorsIn(String... files) {
        return AnalysisResult.of(List.of(files).stream()
                .map(file -> new SheriffFinding(file, "Braces are not used", "Add braces.", "JavaBraces", "ERROR",
                        Map.of()))
                .toList());
    }

    private static String sha256(String content) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("a verdict is served for the same sources, profile and component, and for nothing else")
    void servesOnlyTheSameTerms() {
        String fingerprint = VerdictCache.fingerprintOf(app);
        cache.remember(app, PROFILE, false, fingerprint, errorsIn("app/src/A.java"));
        Optional<GateVerdict> served = cache.recalled(app, PROFILE, false, fingerprint);
        assertTrue(served.isPresent());
        assertEquals(1, served.get().errors());
        assertTrue(served.get().hasErrorsIn("app/src/A.java"));
        assertTrue(served.get().sample().contains("Braces are not used"));
        assertFalse(cache.recalled(app, PROFILE, false, "another").isPresent(), "other sources");
        assertFalse(cache.recalled(app, "JAVA,JAVA_HEXAGONAL", false, fingerprint).isPresent(), "another profile");
        assertFalse(cache.recalled(mount.resolve("elsewhere/app"), PROFILE, false, fingerprint).isPresent(),
                "another project's module of the same name");
        assertFalse(cache.recalled(app, PROFILE, false, "").isPresent(), "sources that cannot be told");
    }

    @Test
    @DisplayName("a hook's message shows the first five errors and says how many more there are")
    void samplesTheFirstErrors() {
        assertEquals("", VerdictCache.sampleOf(errorsIn()));
        assertEquals("  - app/src/A.java: Braces are not used", VerdictCache.sampleOf(errorsIn("app/src/A.java")));
        String seven = VerdictCache.sampleOf(errorsIn("a", "b", "c", "d", "e", "f", "g"));
        assertEquals(6, seven.lines().count());
        assertTrue(seven.contains("  - e: Braces are not used"));
        assertFalse(seven.contains("  - f:"));
        assertTrue(seven.endsWith("... and 2 more"));
    }

    @Test
    @DisplayName("past its lifetime a verdict is measured again")
    void expires() {
        String fingerprint = VerdictCache.fingerprintOf(app);
        VerdictCache expired = new VerdictCache(folder, Duration.ofSeconds(-1));
        expired.remember(app, PROFILE, false, fingerprint, errorsIn());
        assertFalse(expired.recalled(app, PROFILE, false, fingerprint).isPresent());
        assertTrue(cache.recalled(app, PROFILE, false, fingerprint).isPresent(), "the same entry, younger than 15 min");
    }

    @Test
    @DisplayName("nothing is recorded for a failed analysis or sources that cannot be told, and a broken entry reads as none")
    void recordsOnlyWhatCanBeTrusted() throws IOException {
        String fingerprint = VerdictCache.fingerprintOf(app);
        cache.remember(app, PROFILE, false, fingerprint, AnalysisResult.failure("no docker"));
        assertFalse(cache.recalled(app, PROFILE, false, fingerprint).isPresent());
        cache.remember(app, PROFILE, false, "", errorsIn());
        assertFalse(cache.recalled(app, PROFILE, false, "").isPresent());
        Files.createDirectories(folder);
        Files.writeString(cache.entryFor(app, PROFILE, false), "{ half an ent");
        assertFalse(cache.recalled(app, PROFILE, false, fingerprint).isPresent());
    }

    @Test
    @DisplayName("a reader that stops at the first error takes a full verdict first, a full reader never a partial one")
    void aFullVerdictServesBothWays() {
        String fingerprint = VerdictCache.fingerprintOf(app);
        cache.remember(app, PROFILE, true, fingerprint, errorsIn("app/src/A.java"));
        assertFalse(cache.recalled(app, PROFILE, false, fingerprint).isPresent());
        assertEquals(1, cache.recalled(app, PROFILE, true, fingerprint).get().errors());
        cache.remember(app, PROFILE, false, fingerprint, errorsIn("app/src/A.java", "app/src/B.java"));
        assertEquals(2, cache.recalled(app, PROFILE, true, fingerprint).get().errors());
    }

    @Test
    @DisplayName("the fingerprint changes with any source, and not with what Sheriff never reads")
    void fingerprintsTheSources() throws IOException, InterruptedException {
        String before = VerdictCache.fingerprintOf(app);
        Files.createDirectories(app.resolve("node_modules/lib"));
        Files.writeString(app.resolve("node_modules/lib/x.ts"), "export const x = 1;");
        Files.writeString(app.resolve("notes.md"), "hello");
        assertEquals(before, VerdictCache.fingerprintOf(app));
        Thread.sleep(10);
        Files.writeString(app.resolve("src/A.java"), "class A { int b; }");
        assertFalse(before.equals(VerdictCache.fingerprintOf(app)));
        assertEquals("", VerdictCache.fingerprintOf(mount.resolve("nope")));
    }

    @Test
    @DisplayName("an analysis is recorded only when it saw every source as it is now")
    void recordsAnAnalysisOfTheFilesAsTheyAre() throws Exception {
        AnalysisResult seen = AnalysisResult.of(List.of(),
                List.of(new TrackedFile("app/src/A.java", sha256("class A {}"))));
        cache.rememberAnalyzed(mount, "app", PROFILE, seen);
        assertTrue(cache.recalled(app, PROFILE, false, VerdictCache.fingerprintOf(app)).isPresent());
    }

    @Test
    @DisplayName("an analysis of other contents, or that missed a source, or tracked nothing, is not recorded")
    void doesNotRecordAnAnalysisOfOtherFiles() throws Exception {
        cache.rememberAnalyzed(mount, "app", PROFILE, AnalysisResult.of(List.of(),
                List.of(new TrackedFile("app/src/A.java", sha256("class A { int edited; }")))));
        assertFalse(cache.recalled(app, PROFILE, false, VerdictCache.fingerprintOf(app)).isPresent());
        Files.writeString(app.resolve("src/B.java"), "class B {}");
        cache.rememberAnalyzed(mount, "app", PROFILE, AnalysisResult.of(List.of(),
                List.of(new TrackedFile("app/src/A.java", sha256("class A {}")))));
        assertFalse(cache.recalled(app, PROFILE, false, VerdictCache.fingerprintOf(app)).isPresent());
        cache.rememberAnalyzed(mount, "app", PROFILE, AnalysisResult.of(List.of()));
        assertFalse(cache.recalled(app, PROFILE, false, VerdictCache.fingerprintOf(app)).isPresent());
    }

    @Test
    @DisplayName("after a clean sheriff_test, the Stop hook's check runs no Sheriff of its own")
    void theHooksReadWhatTheMcpRecorded() throws Exception {
        String tracked = "{\"/data/app/src/A.java\": {\"hash\": \"" + sha256("class A {}") + "\"}}";
        String errors = "{\"file:/data\": {\"app\": {\"JAVA\": []}}}";
        AtomicInteger runs = new AtomicInteger();
        ProcessRunner sheriff = (command, directory, environment, timeout) -> {
            runs.incrementAndGet();
            try {
                Files.writeString(directory.resolve("sheriff_errors.json"), errors);
                Files.writeString(directory.resolve("sheriff_tracked_files.json"), tracked);
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            return ProcessOutcome.completed(0, "", "");
        };
        Path agent = Files.createDirectories(mount.resolve("sheriff-mcp-java"));
        Configuration configuration = new Configuration(Map.of("TARGET_REPO", mount.toString()), agent);
        ComponentGate gate = new ComponentGate(configuration, new Composition(configuration, sheriff),
                new VerdictCache());
        assertEquals(0, new SheriffRunner(sheriff, "kaizten/sheriff:latest", mount, Duration.ofSeconds(5))
                .test("app", PROFILE).total());
        assertEquals(1, runs.get());
        assertTrue(gate.verdictFor("app").clean());
        assertEquals(1, runs.get(), "the hook ran Sheriff again on files the MCP had just analyzed");
    }

    @Test
    @DisplayName("a gate that waited for the lock takes the verdict the run before it recorded")
    void looksAgainOnceTheLockIsHeld() throws Exception {
        Path agent = Files.createDirectories(mount.resolve("sheriff-mcp-java"));
        Configuration configuration = new Configuration(Map.of("TARGET_REPO", mount.toString()), agent);
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "", ""));
        ComponentGate gate = new ComponentGate(configuration, new Composition(configuration, runner), cache);
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch recorded = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = threads.submit(() -> MountLock.holding(app, () -> {
                held.countDown();
                await(recorded);
                cache.remember(app, PROFILE, false, VerdictCache.fingerprintOf(app), errorsIn("app/src/A.java"));
                return null;
            }));
            held.await(5, TimeUnit.SECONDS);
            Future<GateVerdict> waiting = threads.submit(() -> gate.verdictFor("app"));
            Thread.sleep(100);
            recorded.countDown();
            holder.get(5, TimeUnit.SECONDS);
            assertEquals(1, waiting.get(5, TimeUnit.SECONDS).errors());
            assertEquals(List.of(), runner.commands(), "the waiting gate ran Sheriff for an answer already recorded");
        } finally {
            threads.shutdownNow();
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
