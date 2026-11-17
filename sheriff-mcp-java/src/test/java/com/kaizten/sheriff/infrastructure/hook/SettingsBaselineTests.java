package com.kaizten.sheriff.infrastructure.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What the Stop hook compares a project's settings with.
 */
class SettingsBaselineTests {

    @TempDir
    Path folder;

    @Test
    @DisplayName("a turn's record keeps its commit and the text of each settings file, a line break included")
    void aTurnIsRecordedPerSession() {
        SettingsBaseline baselines = new SettingsBaseline(folder);
        Path file = folder.resolve("app/.sheriff.properties");
        baselines.recordTurn("s", new SettingsSnapshot("abc", Map.of(file, "coverage=50\nbranches=60\n")));
        baselines.recordTurn("", new SettingsSnapshot("ignored", Map.of()));
        SettingsSnapshot recorded = baselines.turn("s").orElseThrow();
        assertEquals("abc", recorded.commit());
        assertEquals(Map.of(file, "coverage=50\nbranches=60\n"), recorded.contents());
        assertEquals(Optional.empty(), baselines.turn("other"));
        assertEquals(Optional.empty(), baselines.turn(""));
    }

    @Test
    @DisplayName("a held record stays the oldest until it is let go")
    void aHeldRecordKeepsTheOldest() {
        SettingsBaseline baselines = new SettingsBaseline(folder);
        Path repository = folder.resolve("repository");
        baselines.hold(repository, new SettingsSnapshot("first", Map.of()));
        baselines.hold(repository, new SettingsSnapshot("second", Map.of()));
        assertEquals("first", baselines.heldFor(repository).orElseThrow().commit());
        assertTrue(baselines.heldFor(folder.resolve("elsewhere")).isEmpty());
        baselines.release(repository);
        assertTrue(baselines.heldFor(repository).isEmpty());
    }

    @Test
    @DisplayName("with no record the last commit is compared with, whatever it is then")
    void noRecordIsTheLastCommit() {
        assertEquals("HEAD", SettingsSnapshot.lastCommit().revision());
        assertEquals("abc", new SettingsSnapshot("abc", Map.of()).revision());
    }
}
