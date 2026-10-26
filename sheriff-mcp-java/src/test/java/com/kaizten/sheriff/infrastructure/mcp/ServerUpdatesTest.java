package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Looking for a newer version of these tools, and what each setting does
 * with one.
 */
class ServerUpdatesTest {

    private static final byte[] NEW_JAR = "the jar of 0.1.9".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path folder;

    private Path cache;
    private Path jar;
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-26T12:00:00Z"));

    /**
     * Releases as a test sets them up, recording what is asked of them.
     */
    private static final class Releases implements ReleaseSource {

        private final Optional<String> latest;
        private final Map<String, byte[]> files;
        private final List<String> asked = new ArrayList<>();
        private final boolean unreachable;

        Releases(Optional<String> latest, Map<String, byte[]> files, boolean unreachable) {
            this.latest = latest;
            this.files = files;
            this.unreachable = unreachable;
        }

        @Override
        public Optional<String> latest() throws IOException {
            asked.add("latest");
            if (unreachable) {
                throw new IOException("no network");
            }
            return latest;
        }

        @Override
        public byte[] download(String version, String file) throws IOException {
            asked.add(version + "/" + file);
            byte[] bytes = files.get(file);
            if (bytes == null) {
                throw new IOException("404 for " + file);
            }
            return bytes;
        }
    }

    @BeforeEach
    void folders() throws IOException {
        cache = folder.resolve("cache");
        Path install = Files.createDirectories(folder.resolve("share/sheriff-agent"));
        jar = install.resolve("sheriff-mcp.jar");
        Files.writeString(jar, "the jar of 0.1.8");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private Releases publishing(String version, String checksum) {
        return new Releases(Optional.of(version), Map.of("sheriff-mcp.jar", NEW_JAR, "sheriff-mcp.jar.sha256",
                checksum.getBytes(StandardCharsets.UTF_8)), false);
    }

    private ServerUpdates updates(String mode, Releases releases) {
        return new ServerUpdates(mode, "0.1.8", releases, cache, Optional.of(jar), now::get);
    }

    @Test
    @DisplayName("notify, the default, says a newer version is published and how to update, and changes nothing")
    void notifySays() throws Exception {
        Releases releases = publishing("0.1.9", sha256(NEW_JAR) + "  sheriff-mcp.jar");
        ServerUpdates updates = updates("notify", releases);

        assertEquals(Optional.empty(), updates.notice());
        updates.check();

        String notice = updates.notice().orElseThrow();
        assertTrue(notice.startsWith("Sheriff tools 0.1.9 is published; this is 0.1.8. Tell the user."), notice);
        assertTrue(notice.contains("curl -fsSL https://github.com/kaizten/sheriff-mcp-server/releases/latest/download/"
                + "install.sh | bash"), notice);
        assertTrue(notice.contains("git pull && scripts/install.sh"), notice);
        assertEquals("the jar of 0.1.8", Files.readString(jar));
        assertEquals(List.of("latest"), releases.asked);
        assertTrue(updates("anything else", releases).notice().isEmpty());
        ServerUpdates unknownMode = updates("sometimes", publishing("0.1.9", sha256(NEW_JAR)));
        unknownMode.check();
        assertTrue(unknownMode.notice().orElseThrow().startsWith("Sheriff tools 0.1.9 is published"),
                "a mode that is none of the three notifies");
    }

    @Test
    @DisplayName("auto downloads the new jar, checks its checksum and puts it in place, and says so")
    void autoUpdates() throws Exception {
        Releases releases = publishing("0.1.9", sha256(NEW_JAR).toUpperCase() + "  sheriff-mcp.jar\n");
        ServerUpdates updates = updates("auto", releases);

        updates.check();

        assertArrayEquals(NEW_JAR, Files.readAllBytes(jar));
        assertFalse(Files.exists(jar.resolveSibling(".sheriff-mcp.jar.download")), "nothing is left beside it");
        assertTrue(updates.notice().orElseThrow().startsWith("Sheriff tools updated themselves from 0.1.8 to 0.1.9"
                + " (SHERIFF_UPDATES=auto)"), updates.notice().orElseThrow());
        assertEquals(List.of("latest", "0.1.9/sheriff-mcp.jar", "0.1.9/sheriff-mcp.jar.sha256"), releases.asked);
    }

    @Test
    @DisplayName("auto refuses a jar its checksum does not match, or with no checksum, and says how to update")
    void autoRefusesABadJar() throws Exception {
        ServerUpdates mismatched = updates("auto", publishing("0.1.9", sha256("other".getBytes()) + "  x"));
        mismatched.check();
        assertEquals("the jar of 0.1.8", Files.readString(jar));
        assertTrue(mismatched.notice().orElseThrow().contains("updating to it by itself failed (the jar downloaded does "
                + "not match its release's checksum)"), mismatched.notice().orElseThrow());
        assertTrue(mismatched.notice().orElseThrow().contains("git pull && scripts/install.sh"));

        ServerUpdates blank = updates("auto", publishing("0.1.9", "   "));
        blank.check();
        assertTrue(blank.notice().orElseThrow().contains("(the release's checksum names no hash)"));

        ServerUpdates missing = updates("auto", new Releases(Optional.of("0.1.9"), Map.of(), false));
        missing.check();
        assertTrue(missing.notice().orElseThrow().contains("(404 for sheriff-mcp.jar)"));
        assertEquals("the jar of 0.1.8", Files.readString(jar));
    }

    @Test
    @DisplayName("auto only tells a build's own jar, which git pull and the installer update")
    void autoLeavesABuildsJar() throws Exception {
        Path built = Files.createDirectories(folder.resolve("sheriff-mcp-java/target")).resolve("sheriff-mcp.jar");
        Files.writeString(built, "built");
        Releases releases = publishing("0.1.9", sha256(NEW_JAR));
        ServerUpdates updates = new ServerUpdates("auto", "0.1.8", releases, cache, Optional.of(built), now::get);

        updates.check();

        assertEquals("built", Files.readString(built));
        assertTrue(updates.notice().orElseThrow().startsWith("Sheriff tools 0.1.9 is published"));
        ServerUpdates classes = new ServerUpdates("auto", "0.1.8", publishing("0.1.9", sha256(NEW_JAR)), cache,
                Optional.of(folder.resolve("classes")), now::get);
        classes.check();
        assertTrue(classes.notice().orElseThrow().startsWith("Sheriff tools 0.1.9 is published"),
                "code that is no jar is only told about");
        ServerUpdates noJar = new ServerUpdates("auto", "0.1.8", publishing("0.1.9", sha256(NEW_JAR)), cache,
                Optional.empty(), now::get);
        noJar.check();
        assertTrue(noJar.notice().orElseThrow().startsWith("Sheriff tools 0.1.9 is published"));
    }

    @Test
    @DisplayName("never does not look, and neither does a build that does not know its own version")
    void neverLooks() {
        Releases releases = publishing("0.1.9", "x");
        ServerUpdates never = updates("never", releases);
        never.check();
        ServerUpdates unknown = new ServerUpdates("notify", "unknown", releases, cache, Optional.of(jar), now::get);
        unknown.check();
        assertEquals(Optional.empty(), never.notice());
        assertEquals(Optional.empty(), unknown.notice());
        assertEquals(List.of(), releases.asked);
    }

    @Test
    @DisplayName("the same version, an older one, no answer or no network say nothing, and only an answer is "
            + "remembered")
    void nothingNewerSaysNothing() {
        List<Releases> cases = List.of(publishing("0.1.8", "x"), publishing("0.1.7", "x"),
                new Releases(Optional.empty(), Map.of(), false), new Releases(Optional.empty(), Map.of(), true));
        List<Boolean> remembered = new ArrayList<>();
        for (int index = 0; index < cases.size(); index++) {
            Path own = folder.resolve("cache-" + index);
            ServerUpdates updates = new ServerUpdates("auto", "0.1.8", cases.get(index), own, Optional.of(jar),
                    now::get);
            updates.check();
            assertEquals(Optional.empty(), updates.notice(), cases.get(index).latest.toString());
            remembered.add(Files.exists(own.resolve("latest-release.properties")));
        }
        assertEquals(List.of(true, true, false, false), remembered);
        assertEquals("the jar of 0.1.8", contents(jar));
    }

    private static String contents(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    @Test
    @DisplayName("what was found is remembered for a day: a second start does not ask, the next day does")
    void theNewestIsRememberedForADay() throws Exception {
        Releases first = publishing("0.1.9", "x");
        updates("notify", first).check();
        Releases second = publishing("0.2.0", "x");
        ServerUpdates sameDay = updates("notify", second);
        sameDay.check();
        assertEquals(List.of(), second.asked, "within a day the cache answers");
        assertTrue(sameDay.notice().orElseThrow().startsWith("Sheriff tools 0.1.9 is published"));

        now.set(now.get().plus(ServerUpdates.CHECK_EVERY));
        ServerUpdates nextDay = updates("notify", second);
        nextDay.check();
        assertEquals(List.of("latest"), second.asked);
        assertTrue(nextDay.notice().orElseThrow().startsWith("Sheriff tools 0.2.0 is published"));

        Files.writeString(cache.resolve("latest-release.properties"), "checked=soon\n");
        Releases third = publishing("0.2.1", "x");
        ServerUpdates broken = updates("notify", third);
        broken.check();
        assertEquals(List.of("latest"), third.asked, "a cache that cannot be read is asked past");
    }

    @Test
    @DisplayName("a cache that cannot be written still lets the newest be told")
    void anUnwritableCacheStillTells() throws Exception {
        Path notAFolder = Files.writeString(folder.resolve("file"), "x");
        ServerUpdates updates = new ServerUpdates("notify", "0.1.8", publishing("0.1.9", "x"),
                notAFolder.resolve("cache"), Optional.of(jar), now::get);
        updates.check();
        assertTrue(updates.notice().orElseThrow().startsWith("Sheriff tools 0.1.9 is published"));
    }

    @Test
    @DisplayName("the newest release is asked once and then read from the cache, and nothing when it cannot be told")
    void theNewestReleaseDirectly() {
        Releases releases = publishing("0.1.9", "x");
        ServerUpdates updates = updates("notify", releases);
        assertEquals(Optional.of("0.1.9"), updates.latestRelease());
        assertEquals(Optional.of("0.1.9"), updates.latestRelease());
        assertEquals(List.of("latest"), releases.asked);
        ServerUpdates offline = new ServerUpdates("notify", "0.1.8", new Releases(Optional.empty(), Map.of(), true),
                folder.resolve("other-cache"), Optional.of(jar), now::get);
        assertEquals(Optional.empty(), offline.latestRelease());
    }

    @Test
    @DisplayName("a jar is replaced only by one that matches its release's checksum")
    void replacingDirectly() throws Exception {
        updates("auto", publishing("0.1.9", sha256(NEW_JAR))).replace(jar, "0.1.9");
        assertArrayEquals(NEW_JAR, Files.readAllBytes(jar));
        Files.writeString(jar, "the jar of 0.1.8");
        ServerUpdates wrong = updates("auto", publishing("0.1.9", "0".repeat(64)));
        java.io.IOException refused = org.junit.jupiter.api.Assertions.assertThrows(java.io.IOException.class,
                () -> wrong.replace(jar, "0.1.9"));
        assertEquals("the jar downloaded does not match its release's checksum", refused.getMessage());
        assertEquals("the jar of 0.1.8", Files.readString(jar));
    }

    @Test
    @DisplayName("versions are compared number by number, and one that is not numbers alone is newer than nothing")
    void versionsAreComparedByNumber() {
        assertTrue(ServerUpdates.newer("0.1.10", "0.1.9"));
        assertTrue(ServerUpdates.newer("1.0.0", "0.9.9"));
        assertTrue(ServerUpdates.newer("0.1.8.1", "0.1.8"));
        assertFalse(ServerUpdates.newer("0.1.8", "0.1.8.0"));
        assertFalse(ServerUpdates.newer("0.1.9", "0.1.10"));
        assertFalse(ServerUpdates.newer("0.1.8", "0.1.8"));
        assertFalse(ServerUpdates.newer("0.1.9-rc.1", "0.1.8"));
        assertTrue(ServerUpdates.newer("0.1.9", "0.1.8-dev"), "a part of the running one that is no number counts 0");
        assertFalse(ServerUpdates.newer("0.2.x", "0.1.8"), "a candidate with a part that is no number is never newer");
    }

    @Test
    @DisplayName("a mode that is none of the three is notify; the three are kept")
    void theModes() {
        Releases releases = publishing("0.1.9", "x");
        assertEquals("notify", updates("sometimes", releases).mode());
        assertEquals("notify", updates("", releases).mode());
        assertEquals("auto", updates("auto", releases).mode());
        assertEquals("never", updates("never", releases).mode());
        assertEquals("notify", updates("notify", releases).mode());
    }

    @Test
    @DisplayName("what was found is written down when it can be, and says when it cannot")
    void rememberingSaysWhetherItWrote() throws Exception {
        ServerUpdates updates = updates("notify", publishing("0.1.9", "x"));
        assertTrue(updates.remember(cache.resolve("latest-release.properties"), "0.1.9"));
        Path notAFolder = Files.writeString(folder.resolve("plain"), "x");
        assertFalse(updates.remember(notAFolder.resolve("latest-release.properties"), "0.1.9"));
    }

    @Test
    @DisplayName("a hash this Java does not have is an error, not a match")
    void anUnknownHash() throws Exception {
        assertEquals(sha256(NEW_JAR), ServerUpdates.hash(NEW_JAR, "SHA-256"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> ServerUpdates.hash(NEW_JAR, "NO-SUCH-HASH"));
    }

    @Test
    @DisplayName("a jar is installed when it is outside a build's target folder")
    void installedJars() {
        assertTrue(ServerUpdates.installed(Path.of("/home/a/.local/share/sheriff-agent/sheriff-mcp.jar")));
        assertFalse(ServerUpdates.installed(Path.of("/repo/sheriff-mcp-java/target/sheriff-mcp.jar")));
        assertFalse(ServerUpdates.installed(Path.of("/repo/sheriff-mcp-java/target/classes")));
        assertFalse(ServerUpdates.installed(Path.of("/sheriff-mcp.jar")), "a jar at the root has no folder of its own");
    }

    @Test
    @DisplayName("the server's looks in the user's cache, with the setting it is given, and in the background")
    void theServersUpdates() throws Exception {
        McpConfig config = new McpConfig(Map.of("XDG_CACHE_HOME", folder.toString(), "SHERIFF_UPDATES", "never"),
                folder);
        Releases releases = publishing("9.9.9", "x");
        ServerUpdates updates = ServerUpdates.forServer(config, jar, releases).inBackground();
        Thread.sleep(300);
        assertEquals(Optional.empty(), updates.notice());
        assertEquals(List.of(), releases.asked, "never is read from the settings");
        ServerUpdates told = ServerUpdates.forServer(new McpConfig(Map.of("XDG_CACHE_HOME", folder.toString()),
                folder), jar, publishing("9.9.9", "x"));
        told.check();
        assertTrue(told.notice().orElseThrow().startsWith("Sheriff tools 9.9.9 is published"),
                "with no setting it notifies");
        assertTrue(Files.exists(folder.resolve("sheriff-mcp/latest-release.properties")));
    }
}
