package com.kaizten.sheriff.infrastructure.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The record of what a turn found already changed.
 */
class TurnStartTests {

    @TempDir
    Path directory;

    @Test
    @DisplayName("what a turn found is read back by the Stop hook, a separate process, and the next turn replaces it")
    void recordsPerSession() {
        TurnStart turns = new TurnStart(directory);
        turns.record("one", Map.of("app", "abc"));
        turns.record("two", Map.of());
        assertEquals(Optional.of(Map.of("app", "abc")), new TurnStart(directory).recorded("one"));
        assertEquals(Optional.of(Map.of()), turns.recorded("two"));
        turns.record("one", Map.of("web", "def"));
        assertEquals(Optional.of(Map.of("web", "def")), turns.recorded("one"));
    }

    @Test
    @DisplayName("no record, or no session to keep one by, means every changed component is checked")
    void nothingRecordedIsNothing() {
        TurnStart turns = new TurnStart(directory);
        turns.record("", Map.of("app", "abc"));
        assertEquals(Optional.empty(), turns.recorded(""));
        assertEquals(Optional.empty(), turns.recorded("never"));
    }
}
