package com.kaizten.sheriff.infrastructure.hook;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.valueobject.SheriffFinding;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The record of errors that stayed through the work done for them.
 */
class StuckErrorsTests {

    @TempDir
    Path folder;

    private final Path component = Path.of("/work/app");

    @Test
    @DisplayName("an error is known by its file, rule and message, not by its line")
    void anErrorIsKnownWithoutItsLine() {
        SheriffFinding first = new SheriffFinding("/data/app/A.java", "No JavaDoc", "h", "R", "ERROR", Map.of("line", 3));
        SheriffFinding moved = new SheriffFinding("/data/app/A.java", "No JavaDoc", "h", "R", "ERROR", Map.of("line", 9));
        assertEquals("/data/app/A.java|R|No JavaDoc", StuckErrors.key(first));
        assertEquals(Set.of(StuckErrors.key(first)), StuckErrors.keys(List.of(first, moved)));
    }

    @Test
    @DisplayName("each answer an error stays through is an attempt: two leave it for the end, three tell the user")
    void attemptsAreCounted() {
        StuckErrors stuck = new StuckErrors(folder);
        Set<String> both = Set.of("a", "b");
        stuck.attempted(component, Set.of("a"), both);
        assertEquals(Set.of(), stuck.deferred(component));
        stuck.attempted(component, Set.of("a"), both);
        assertEquals(Set.of("a"), stuck.deferred(component), "b was never listed, so never attempted");
        stuck.attempted(component, Set.of("a", "b"), both);
        assertEquals(Set.of("a"), stuck.exhausted(component));
        assertEquals(Set.of("a"), stuck.deferred(component));
    }

    @Test
    @DisplayName("an error that goes away leaves the record, and comes back as new")
    void aFixedErrorIsForgotten() {
        StuckErrors stuck = new StuckErrors(folder);
        stuck.stalled(component, Set.of("a", "b"));
        assertEquals(Set.of("a", "b"), stuck.deferred(component));
        stuck.attempted(component, Set.of(), Set.of("b"));
        assertEquals(Set.of("b"), stuck.deferred(component));
        stuck.attempted(component, Set.of(), Set.of("a", "b"));
        assertEquals(Set.of("b"), stuck.deferred(component));
    }

    @Test
    @DisplayName("a stop with nothing changed leaves every error for the end at once, never lowering a count")
    void aStallDefersAtOnce() {
        StuckErrors stuck = new StuckErrors(folder);
        stuck.attempted(component, Set.of("a"), Set.of("a"));
        stuck.attempted(component, Set.of("a"), Set.of("a"));
        stuck.attempted(component, Set.of("a"), Set.of("a"));
        stuck.stalled(component, Set.of("a", "b"));
        assertEquals(Set.of("a", "b"), stuck.deferred(component));
        assertEquals(Set.of("a"), stuck.exhausted(component));
    }

    @Test
    @DisplayName("each component has its own record, and one that cannot be read or written records nothing")
    void recordsAreBestEffort() throws IOException {
        StuckErrors stuck = new StuckErrors(folder);
        stuck.stalled(component, Set.of("a"));
        assertEquals(Set.of(), stuck.deferred(Path.of("/work/other")));
        Path notAFolder = Files.writeString(folder.resolve("file"), "x");
        StuckErrors unwritable = new StuckErrors(notAFolder);
        unwritable.stalled(component, Set.of("a"));
        assertEquals(Set.of(), unwritable.deferred(component));
        try (Stream<Path> files = Files.list(folder)) {
            for (Path record : files.filter(path -> !path.equals(notAFolder)).toList()) {
                Files.writeString(record, "not json");
            }
        }
        assertEquals(Set.of(), stuck.deferred(component));
    }

    @Test
    @DisplayName("an error listed that is no longer there is not counted, as there is nothing left of it")
    void aListedErrorThatIsGoneIsNotCounted() {
        StuckErrors stuck = new StuckErrors(folder);
        stuck.attempted(component, Set.of("gone"), Set.of("a"));
        stuck.attempted(component, Set.of("gone"), Set.of("a", "gone"));
        assertEquals(Set.of(), stuck.deferred(component));
    }

    @Test
    @DisplayName("an error Sheriff's fixers removed that is back had its repair undone: left for the end at once")
    void anUndoneRepairIsLeftForTheEnd() {
        StuckErrors stuck = new StuckErrors(folder);
        stuck.repaired(component, Set.of("pom"));
        stuck.repaired(component, Set.of());
        assertEquals(Set.of("pom"), stuck.repairedBefore(component));
        assertEquals(Set.of(), stuck.undone(component, Set.of("other")), "an error never repaired is not undone");
        stuck.attempted(component, Set.of(), Set.of("other"));
        assertEquals(Set.of("pom"), stuck.repairedBefore(component), "what was repaired outlives the attempts");

        assertEquals(Set.of("pom"), stuck.undone(component, Set.of("pom", "other")));

        assertTrue(stuck.deferred(component).contains("pom"));
        assertEquals(Set.of(), stuck.exhausted(component));
        stuck.attempted(component, Set.of("pom"), Set.of("pom"));
        assertEquals(Set.of("pom"), stuck.exhausted(component), "one more answer through it, and the user is told");
    }

    @Test
    @DisplayName("an error Sheriff repaired counts as left for the end wherever it is asked, as the gate asks")
    void aRepairedErrorIsDeferredForTheHooks() {
        StuckErrors stuck = new StuckErrors(folder);
        stuck.repaired(component, Set.of("pom"));
        assertEquals(Set.of("pom"), stuck.deferred(component));
    }

    @Test
    @DisplayName("a record an earlier version wrote, attempts alone, is still read")
    void anEarlierRecordIsRead() throws IOException {
        StuckErrors stuck = new StuckErrors(folder);
        stuck.stalled(component, Set.of("a"));
        try (Stream<Path> files = Files.list(folder)) {
            for (Path record : files.filter(Files::isRegularFile).toList()) {
                Files.writeString(record, "{\"a\": 3, \"b\": 1}");
            }
        }
        assertEquals(Set.of("a"), stuck.exhausted(component));
        assertEquals(Set.of(), stuck.repairedBefore(component));
    }

    @Test
    @DisplayName("nothing repaired and nothing undone write no record")
    void nothingToRecordWritesNothing() throws IOException {
        StuckErrors stuck = new StuckErrors(folder);
        stuck.repaired(component, Set.of());
        assertEquals(Set.of(), stuck.undone(component, Set.of("a")));
        try (Stream<Path> files = Files.list(folder)) {
            assertEquals(List.of(), files.toList());
        }
    }

    @Test
    @DisplayName("only numbers are attempts: a count written as text, or an empty record, counts nothing")
    void onlyNumbersAreAttempts() throws IOException {
        StuckErrors stuck = new StuckErrors(folder);
        stuck.stalled(component, Set.of("a"));
        try (Stream<Path> files = Files.list(folder)) {
            Path record = files.filter(Files::isRegularFile).findFirst().orElseThrow();
            Files.writeString(record, "{\"attempts\": {\"a\": \"5\", \"b\": 3}, \"repaired\": []}");
            assertEquals(Set.of("b"), stuck.exhausted(component));
            Files.writeString(record, "");
            assertEquals(Set.of(), stuck.deferred(component));
        }
    }
}
