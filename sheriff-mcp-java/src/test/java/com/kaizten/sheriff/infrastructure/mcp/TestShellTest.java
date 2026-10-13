package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

final class TestShellTest {

    @Test
    @DisplayName("sh and cmd join with &&; a model's shell on Windows with ;, which Windows PowerShell reads too")
    void eachShellJoinsTheCommandItsWay() {
        assertEquals("cd 'a' && x", String.format(TestShell.POSIX.inDirectory(), "'a'", "x"));
        assertEquals("cd \"a\" && x", String.format(TestShell.CMD.inDirectory(), "\"a\"", "x"));
        assertEquals("cd \"a\"; x", String.format(TestShell.ANY_WINDOWS_SHELL.inDirectory(), "\"a\"", "x"));
        assertFalse(TestShell.ANY_WINDOWS_SHELL.inDirectory().contains("&&"));
    }

    @Test
    @DisplayName("Maven's wrapper is ./mvnw, .\\mvnw.cmd for cmd, and ./mvnw.cmd for Git Bash and PowerShell")
    void eachShellNamesMavensWrapper() {
        assertEquals("./mvnw -q test", String.format(TestShell.POSIX.mavenWrapper(), " -q"));
        assertEquals(".\\mvnw.cmd -q test", String.format(TestShell.CMD.mavenWrapper(), " -q"));
        assertEquals("./mvnw.cmd test", String.format(TestShell.ANY_WINDOWS_SHELL.mavenWrapper(), ""));
    }

    @Test
    @DisplayName("Gradle's wrapper is ./gradlew, .\\gradlew.bat for cmd, and ./gradlew.bat for Git Bash and PowerShell")
    void eachShellNamesGradlesWrapper() {
        assertEquals("./gradlew test", TestShell.POSIX.gradleWrapper());
        assertEquals(".\\gradlew.bat test", TestShell.CMD.gradleWrapper());
        assertEquals("./gradlew.bat test", TestShell.ANY_WINDOWS_SHELL.gradleWrapper());
    }
}
