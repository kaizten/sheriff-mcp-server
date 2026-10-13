package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class TestShellTest {

    private static List<String> commands(TestShell shell, String directory, String quiet) {
        return List.of(shell.mavenWrapper(), shell.maven(), shell.gradleWrapper(), shell.gradle(), shell.npm())
                .stream().map(format -> String.format(format, directory, quiet)).toList();
    }

    @Test
    @DisplayName("sh joins with &&, in the folder; ./mvnw and ./gradlew are the wrappers")
    void posixRunsEveryToolInItsFolder() {
        assertEquals(List.of("cd 'a' && ./mvnw -q test", "cd 'a' && mvn -q test", "cd 'a' && ./gradlew test",
                "cd 'a' && gradle test", "cd 'a' && npm test"), commands(TestShell.POSIX, "'a'", " -q"));
    }

    @Test
    @DisplayName("cmd joins with && and names a wrapper .\\mvnw.cmd, as it finds no bare one with "
            + "NoDefaultCurrentDirectoryInExePath set")
    void cmdNamesTheWrapperFromTheFolder() {
        assertEquals(List.of("cd \"a\" && .\\mvnw.cmd -q test", "cd \"a\" && mvn -q test",
                "cd \"a\" && .\\gradlew.bat test", "cd \"a\" && gradle test", "cd \"a\" && npm test"),
                commands(TestShell.CMD, "\"a\"", " -q"));
    }

    @Test
    @DisplayName("a model's shell on Windows: ./ before a wrapper, no &&, and a tool on the PATH given the folder "
            + "with no cd that could fail")
    void anyWindowsShellNeedsNoCdButForAWrapper() {
        List<String> commands = commands(TestShell.ANY_WINDOWS_SHELL, "'C:/a'", "");

        assertEquals(List.of("cd 'C:/a'; ./mvnw.cmd test", "mvn -f 'C:/a' test", "cd 'C:/a'; ./gradlew.bat test",
                "gradle -p 'C:/a' test", "npm --prefix 'C:/a' test"), commands);
        assertFalse(commands.stream().anyMatch(command -> command.contains("&&")), "Windows PowerShell has no &&");
    }
}
