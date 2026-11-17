package com.kaizten.sheriff.infrastructure.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The settings the user gave a project's file their way.
 */
class ChosenSettingsTests {

    @TempDir
    Path folder;

    @Test
    @DisplayName("a line holds the user's choice while it keeps the value recorded, in that file only")
    void recordsAValuePerFile() throws IOException {
        ChosenSettings chosen = new ChosenSettings(folder.resolve("records"));
        Path file = Files.writeString(folder.resolve(".sheriff.properties"), "SHERIFF_COVERAGE=80\n");
        Path other = Files.writeString(Files.createDirectories(folder.resolve("other")).resolve(".sheriff.properties"),
                "SHERIFF_COVERAGE=80\n");
        chosen.record(file, "SHERIFF_COVERAGE", "80");
        chosen.record(file, "SHERIFF_PROTOTYPE", "");
        assertTrue(chosen.chose(file, "SHERIFF_COVERAGE", "80"));
        assertTrue(chosen.chose(file, "SHERIFF_PROTOTYPE", ""));
        assertFalse(chosen.chose(file, "SHERIFF_COVERAGE", "10"));
        assertFalse(chosen.chose(file, "SHERIFF_BRANCHES", "80"));
        assertFalse(chosen.chose(other, "SHERIFF_COVERAGE", "80"));
    }

    @Test
    @DisplayName("a change the user's way is recorded as theirs; one through Settings.change alone, as --config, is not")
    void onlyAChosenChangeIsRecorded() throws IOException {
        Path project = Files.createDirectories(folder.resolve("project"));
        Settings.change(Map.of(), project, "SHERIFF_STOP_MAX_BLOCKS", "3", true);
        assertFalse(new ChosenSettings().chose(Settings.projectFile(project), "SHERIFF_STOP_MAX_BLOCKS", "3"));
        Settings.changeAsChosen(Map.of(), project, "SHERIFF_STOP_MAX_BLOCKS", " 2 ", true);
        assertTrue(new ChosenSettings().chose(Settings.projectFile(project), "SHERIFF_STOP_MAX_BLOCKS", "2"));
    }
}
