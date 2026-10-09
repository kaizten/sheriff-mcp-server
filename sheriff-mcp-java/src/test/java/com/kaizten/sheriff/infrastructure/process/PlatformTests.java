package com.kaizten.sheriff.infrastructure.process;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What differs on Windows, checked on any machine by naming the platform.
 */
class PlatformTests {

    @TempDir
    Path bin;

    @Test
    @DisplayName("a command line runs in cmd.exe on Windows and in /bin/sh elsewhere")
    void theShellFollowsThePlatform() {
        assertEquals(List.of("cmd.exe", "/c", "mvn -q test"), Platform.shell("mvn -q test", true));
        assertEquals(List.of("/bin/sh", "-c", "mvn -q test"), Platform.shell("mvn -q test", false));
    }

    @Test
    @DisplayName("paths compared with Sheriff's and git's are written with forward slashes")
    void pathsUseForwardSlashes() {
        assertEquals("app/src/A.java", Platform.slashes(Path.of("app", "src", "A.java")));
    }

    @Test
    @DisplayName("a Windows path reaches a Claude Code rule as /drive/rest, the form it matches")
    void aRulePathOnWindows() {
        assertEquals("/d/work/app", Platform.posixForm("D:\\work\\app"));
        assertEquals("/c/Users/a b/p", Platform.posixForm("C:\\Users\\a b\\p"));
        assertEquals("/work/app", Platform.posixForm("/work/app"));
    }

    @Test
    @DisplayName("on Windows, claude installed by npm is found as claude.cmd; elsewhere nothing changes")
    void anNpmShimIsFoundOnWindows() throws IOException {
        Path shim = Files.createFile(bin.resolve("claude.cmd"));
        Map<String, String> environment = Map.of("PATH", "C:\\nowhere;" + bin, "PATHEXT", ".COM;.EXE;.CMD");
        assertEquals(List.of(shim.toString(), "-p"), Platform.resolved(List.of("claude", "-p"), true, environment));
        assertEquals(List.of("claude", "-p"), Platform.resolved(List.of("claude", "-p"), false, environment));
        assertEquals(List.of("codex"), Platform.resolved(List.of("codex"), true, environment));
        assertEquals(List.of("java.exe"), Platform.resolved(List.of("java.exe"), true, environment));
    }
}
