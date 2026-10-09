package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The bounded walk behind project detection.
 */
final class SourceCounterTest {

    @TempDir
    Path workspace;

    private void file(String relative) throws IOException {
        Path file = workspace.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "");
    }

    @Test
    void countsEachLanguageByItsExtensions() throws IOException {
        file("src/A.java");
        file("src/B.java");
        file("src/c.ts");
        Map<String, Integer> counts = SourceCounter.count(workspace,
                Map.of("java", List.of(".java"), "ts", List.of(".ts")));
        assertEquals(2, counts.get("java"));
        assertEquals(1, counts.get("ts"));
    }

    @Test
    void neverLooksInsideDependenciesBuildOutputOrHiddenFolders() throws IOException {
        file("node_modules/x/a.ts");
        file("target/B.java");
        file("build/C.java");
        file(".git/D.java");
        assertFalse(SourceCounter.holdsAny(workspace, List.of(".java", ".ts")));
        file("src/E.java");
        assertTrue(SourceCounter.holdsAny(workspace, List.of(".java")));
    }

    @Test
    void aMissingDirectoryHoldsNothing() {
        assertFalse(SourceCounter.holdsAny(workspace.resolve("nope"), List.of(".java")));
    }
}
