package com.kaizten.sheriff.infrastructure.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Reading the tests Sheriff ran on a component from the history it writes at
 * the root of its mount, as the image of 30 October 2026 wrote it after
 * {@code JAVA} and {@code JAVA_HEXAGONAL} on {@code back-end}.
 */
class SheriffHistoryTests {

    private static final String REAL_HISTORY = """
            {
              "file:/data" : [ {
                "components" : [ "back-end" ],
                "executions" : [ {
                  "duration" : "PT0.020370093S",
                  "warnings" : 0,
                  "errors" : 6,
                  "timestamp" : "2026-10-31T21:31:01.834498859Z"
                } ],
                "test" : "JAVA"
              }, {
                "components" : [ "back-end" ],
                "executions" : [ ],
                "test" : "JAVA_HEXAGONAL"
              } ],
              "file:/elsewhere" : [ {
                "components" : [ "front-end", "back-end" ],
                "test" : "JAVA"
              }, {
                "components" : [ "front-end" ],
                "test" : "TYPESCRIPT"
              } ]
            }
            """;

    @TempDir
    Path mount;

    @Test
    @DisplayName("every test the history records on a component, once each, under any URI")
    void everyTestOnTheComponent() throws IOException {
        Files.writeString(mount.resolve("sheriff_history.json"), REAL_HISTORY);

        SheriffHistory history = new SheriffHistory(mount);

        assertEquals(List.of("JAVA", "JAVA_HEXAGONAL"), history.testsOn("back-end"));
        assertEquals(List.of("JAVA", "TYPESCRIPT"), history.testsOn("front-end"));
        assertEquals(List.of(), history.testsOn("never"));
    }

    @Test
    @DisplayName("no history, or one that cannot be read, records nothing")
    void noHistoryRecordsNothing() throws IOException {
        assertEquals(List.of(), new SheriffHistory(mount).testsOn("back-end"));
        Files.writeString(mount.resolve("sheriff_history.json"), "{ not JSON");
        assertEquals(List.of(), new SheriffHistory(mount).testsOn("back-end"));
        Files.writeString(mount.resolve("sheriff_history.json"), "");
        assertEquals(List.of(), new SheriffHistory(mount).testsOn("back-end"));
    }

    @Test
    @DisplayName("an entry with no test names none")
    void anEntryWithNoTestNamesNone() throws IOException {
        Files.writeString(mount.resolve("sheriff_history.json"),
                "{\"file:/data\":[{\"components\":[\"back-end\"]},{\"components\":[\"back-end\"],\"test\":\"JAVA\"}]}");

        assertEquals(List.of("JAVA"), new SheriffHistory(mount).testsOn("back-end"));
    }
}
