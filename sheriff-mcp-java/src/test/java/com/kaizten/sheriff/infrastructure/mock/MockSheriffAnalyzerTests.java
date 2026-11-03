package com.kaizten.sheriff.infrastructure.mock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.domain.valueobject.AnalysisResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The analyzer the loop runs in mock mode: one finding per line that carries
 * the marker, and a failure when there is nothing to analyze.
 */
class MockSheriffAnalyzerTests {

    @TempDir
    Path repository;

    @Test
    @DisplayName("each line with the marker is a finding, named by the file and its line")
    void eachMarkedLineIsAFinding() throws IOException {
        Path app = Files.createDirectories(repository.resolve("app/src"));
        Files.writeString(app.resolve("A.java"), "class A {\n    // MARK\n}\n// MARK\n");
        Files.writeString(app.resolve("B.java"), "class B {}\n");

        AnalysisResult result = new MockSheriffAnalyzer(repository, repository.resolve("app"), "MARK").analyze();

        assertEquals(2, result.total());
        assertEquals("Mock marker found at line 2.", result.errors().get(0).description());
        assertEquals("app/src/A.java".replace('/', java.io.File.separatorChar), result.errors().get(0).file());
    }

    @Test
    @DisplayName("a target that is not there is a failure, not a clean run")
    void aMissingTargetFails() {
        AnalysisResult result = new MockSheriffAnalyzer(repository, repository.resolve("gone"), "MARK").analyze();

        assertTrue(result.error());
    }
}
