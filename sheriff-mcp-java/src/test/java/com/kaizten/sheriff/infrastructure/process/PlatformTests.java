package com.kaizten.sheriff.infrastructure.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    @DisplayName("the sheriff.docker property stands for SHERIFF_DOCKER when the variable is not set, never over it")
    void theDockerPropertyStandsForTheVariable() {
        assertEquals("wsl", Platform.withDockerProperty(Map.of(), "wsl").get("SHERIFF_DOCKER"));
        assertEquals("wsl:Ubuntu",
                Platform.withDockerProperty(Map.of("SHERIFF_DOCKER", "wsl:Ubuntu"), "wsl").get("SHERIFF_DOCKER"));
        assertEquals(Map.of(), Platform.withDockerProperty(Map.of(), null));
        assertEquals(Map.of(), Platform.withDockerProperty(Map.of(), " "));
    }

    @Test
    @DisplayName("this process's environment takes SHERIFF_DOCKER from -Dsheriff.docker only when it has none")
    void theProcessEnvironmentReadsTheProperty() {
        String before = System.getProperty("sheriff.docker");
        System.setProperty("sheriff.docker", "wsl:FromTheProperty");
        try {
            String variable = System.getenv("SHERIFF_DOCKER");
            String expected = variable == null || variable.isBlank() ? "wsl:FromTheProperty" : variable;
            assertEquals(expected, Platform.environment().get("SHERIFF_DOCKER"));
            assertEquals(System.getenv("PATH"), Platform.environment().get("PATH"), "the rest is the process's own");
        } finally {
            if (before == null) {
                System.clearProperty("sheriff.docker");
            } else {
                System.setProperty("sheriff.docker", before);
            }
        }
    }

    @Test
    @DisplayName("a path for a model's shell on Windows is single quoted, which keeps a $ or a backtick in Git Bash "
            + "and both PowerShells, and double quoted only when it holds a single quote")
    void aPathForAnyWindowsShellIsSingleQuoted() {
        assertEquals("'C:/Users/a/pay$home'", Platform.quotedForAnyWindowsShell("C:\\Users\\a\\pay$home"));
        assertEquals("'C:/tick`x'", Platform.quotedForAnyWindowsShell("C:/tick`x"));
        assertEquals("\"C:/o'brien\"", Platform.quotedForAnyWindowsShell("C:\\o'brien"));
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

    @Test
    @DisplayName("SHERIFF_DOCKER=wsl runs docker through wsl.exe -e, with no shell to expand a $, and a bind "
            + "mount's source as WSL sees it")
    void dockerRunsThroughWslWhenAsked() {
        List<String> run = List.of("docker", "run", "--mount", "type=bind,source=C:\\Users\\a b\\proj,target=/data", "img");
        assertEquals(
                List.of("wsl.exe", "-e", "docker", "run", "--mount",
                        "type=bind,source=/mnt/c/Users/a b/proj,target=/data", "img"),
                Platform.dockerCommand(run, true, Map.of("SHERIFF_DOCKER", "wsl")));
        assertEquals(
                List.of("wsl.exe", "-e", "docker", "run", "--mount", "type=bind,\"source=/mnt/d/a,b/c\",target=/data"),
                Platform.dockerCommand(List.of("docker", "run", "--mount", "type=bind,\"source=D:\\a,b\\c\",target=/data"),
                        true, Map.of("SHERIFF_DOCKER", "wsl")));
        assertEquals(
                List.of("wsl.exe", "-e", "docker", "run",
                        "--mount", "type=bind,source=/mnt/c/Users/a/.cache/sheriff-mcp/state/0a1b,target=/data",
                        "--mount", "type=bind,source=/mnt/c/work/app,target=/data/app", "img"),
                Platform.dockerCommand(List.of("docker", "run",
                        "--mount", "type=bind,source=C:\\Users\\a\\.cache\\sheriff-mcp\\state\\0a1b,target=/data",
                        "--mount", "type=bind,source=C:\\work\\app,target=/data/app", "img"),
                        true, Map.of("SHERIFF_DOCKER", "wsl")),
                "the state folder and the component are both mounts, and both are translated");
        assertEquals(
                List.of("wsl.exe", "-d", "Ubuntu", "-e", "docker", "pull", "img"),
                Platform.dockerCommand(List.of("docker", "pull", "img"), true, Map.of("SHERIFF_DOCKER", "wsl:Ubuntu")));
    }

    @Test
    @DisplayName("on Windows an argument with a double quote is quoted for the program's command line, backslashes "
            + "before a quote doubled, so wsl.exe and docker read it whole")
    void anArgumentWithAQuoteIsQuotedOnWindows() {
        assertEquals("\"echo \\\"a b\\\"\"", Platform.quotedArgument("echo \"a b\""));
        assertEquals("\"x\\\\\\\"y\"", Platform.quotedArgument("x\\\"y"), "a backslash before a quote is doubled");
        assertEquals("\"\\\"q\\\" end\\\\\"", Platform.quotedArgument("\"q\" end\\"), "and before the closing one");
        assertEquals("C:\\a b\\c", Platform.quotedArgument("C:\\a b\\c"), "with no quote Java quotes it itself");
        List<String> mount = List.of("wsl.exe", "-e", "docker", "run", "--mount",
                "type=bind,\"source=/mnt/c/par,ent/app\",target=/data/app", "img");
        assertEquals(List.of("wsl.exe", "-e", "docker", "run", "--mount",
                "\"type=bind,\\\"source=/mnt/c/par,ent/app\\\",target=/data/app\"", "img"),
                Platform.quotedForCommandLine(mount, true));
    }

    @Test
    @DisplayName("cmd.exe, a .cmd or a .bat, and every program off Windows, get their arguments as they are")
    void cmdAndOtherPlatformsAreLeftAlone() {
        List<String> tests = List.of("cmd.exe", "/c", "cd \"app\" && .\\mvnw.cmd -q test");
        assertEquals(tests, Platform.quotedForCommandLine(tests, true));
        List<String> full = List.of("C:\\Windows\\System32\\CMD.EXE", "/c", "echo \"x\"");
        assertEquals(full, Platform.quotedForCommandLine(full, true));
        List<String> shim = List.of("C:\\Users\\a\\.local\\bin\\docker.cmd", "run", "\"x\"");
        assertEquals(shim, Platform.quotedForCommandLine(shim, true));
        List<String> sh = List.of("sh", "-c", "echo \"x\"");
        assertEquals(sh, Platform.quotedForCommandLine(sh, false));
    }

    @Test
    @DisplayName("Docker goes through WSL only on Windows with SHERIFF_DOCKER=wsl, never on Linux")
    void dockerThroughWslOnlyOnWindows() {
        assertTrue(Platform.dockerThroughWsl(true, Map.of("SHERIFF_DOCKER", "wsl")));
        assertTrue(Platform.dockerThroughWsl(true, Map.of("SHERIFF_DOCKER", "wsl:Ubuntu")));
        assertFalse(Platform.dockerThroughWsl(true, Map.of()));
        assertFalse(Platform.dockerThroughWsl(true, Map.of("SHERIFF_DOCKER", "docker")));
        assertFalse(Platform.dockerThroughWsl(false, Map.of("SHERIFF_DOCKER", "wsl")));
    }

    @Test
    @DisplayName("the setting an installer passes on is the variable's own, and only where it applies")
    void theDockerSettingToPassOn() {
        assertEquals(Optional.of("wsl:Ubuntu"), Platform.dockerSetting(true, Map.of("SHERIFF_DOCKER", " wsl:Ubuntu ")));
        assertEquals(Optional.of("wsl"), Platform.dockerSetting(true, Map.of("SHERIFF_DOCKER", "wsl")));
        assertEquals(Optional.empty(), Platform.dockerSetting(false, Map.of("SHERIFF_DOCKER", "wsl")));
        assertEquals(Optional.empty(), Platform.dockerSetting(true, Map.of()));
    }

    @Test
    @DisplayName("without the variable, off Windows, or for another program, the command is left alone")
    void dockerIsLeftAloneOtherwise() {
        List<String> run = List.of("docker", "pull", "img");
        assertEquals(run, Platform.dockerCommand(run, true, Map.of()));
        assertEquals(run, Platform.dockerCommand(run, false, Map.of("SHERIFF_DOCKER", "wsl")));
        assertEquals(List.of("git", "status"),
                Platform.dockerCommand(List.of("git", "status"), true, Map.of("SHERIFF_DOCKER", "wsl")));
    }
}
