package com.kaizten.sheriff.infrastructure.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the file tools the API backend hands the model.
 *
 * <p>This is the capability the CLI backend structurally cannot have — a write
 * refused before it happens rather than detected afterwards — so the refusals
 * are what these tests are mostly about.
 */
class FileToolsTests {

    @TempDir
    private Path repository;

    @BeforeEach
    void writeAFixture() throws IOException {
        Files.createDirectories(repository.resolve("app"));
        Files.writeString(repository.resolve("app/A.java"), "class A {}");
        Files.writeString(repository.resolve("app/B.java"), "class B {}");
    }

    private FileTools scoped() {
        return new FileTools(repository, Set.of("app/A.java"));
    }

    @Test
    void readsAFile() {
        assertEquals("class A {}", scoped().readFile("app/A.java"));
    }

    @Test
    @DisplayName("reading is not restricted: understanding a caller is how a fix avoids breaking it")
    void readsOutsideTheWriteScopeToo() {
        assertEquals("class B {}", scoped().readFile("app/B.java"));
    }

    @Test
    void aMissingFileIsAnAnswerNotAnException() {
        assertTrue(scoped().readFile("app/Nope.java").startsWith("Could not read"));
    }

    @Test
    @DisplayName("a write with no content is a call that never finished, not a request to empty the file")
    void refusesAWriteThatCarriesNoContent() throws IOException {
        String answer = scoped().writeFile("app/A.java", null);
        assertTrue(answer.startsWith("Refused"));
        assertEquals("class A {}", Files.readString(repository.resolve("app/A.java")));
    }

    @Test
    void writesAFileInScope() throws IOException {
        FileTools tools = scoped();
        assertTrue(tools.writeFile("app/A.java", "class A { void a() {} }").startsWith("Wrote"));
        assertEquals("class A { void a() {} }", Files.readString(repository.resolve("app/A.java")));
        assertEquals(List.of("app/A.java"), tools.written());
    }

    @Test
    @DisplayName("an out-of-scope write is refused before it happens, and the model is told why")
    void refusesAWriteOutsideThePassScope() throws IOException {
        FileTools tools = scoped();
        String answer = tools.writeFile("app/B.java", "ruined");
        assertTrue(answer.startsWith("Refused"));
        assertTrue(answer.contains("app/A.java"), "the refusal should say what is allowed");
        assertEquals("class B {}", Files.readString(repository.resolve("app/B.java")));
        assertEquals(List.of(), tools.written());
    }

    @Test
    @DisplayName("the repair pass has no scope, so every file in the repository is fair game")
    void anUnrestrictedPassMayWriteAnywhereInTheRepository() {
        FileTools unrestricted = new FileTools(repository, null);
        assertTrue(unrestricted.writeFile("app/B.java", "repaired").startsWith("Wrote"));
    }

    @Test
    @DisplayName("a path that escapes the repository is refused even when the pass is unrestricted")
    void refusesPathsThatLeaveTheRepository() {
        FileTools unrestricted = new FileTools(repository, null);
        assertTrue(unrestricted.writeFile("../escaped.java", "no").startsWith("Refused"));
        assertTrue(unrestricted.writeFile("/etc/passwd", "no").startsWith("Refused"));
        assertTrue(unrestricted.readFile("../../secrets").startsWith("Refused"));
        assertFalse(Files.exists(repository.getParent().resolve("escaped.java")));
    }

    @Test
    void normalizesWithoutBeingFooledByADetour() {
        assertTrue(scoped().readFile("app/../app/A.java").contains("class A"));
        assertEquals(null, scoped().resolve("app/../../outside.java"));
    }

    @Test
    void knowsWhatItMayWrite() {
        assertTrue(scoped().isAllowed("app/A.java"));
        assertFalse(scoped().isAllowed("app/B.java"));
        assertTrue(new FileTools(repository, null).isAllowed("anything.java"));
    }

    @Test
    @DisplayName("kept to the component, the model reads nothing beside it: for one module, that is every other project")
    void theWorkspaceKeepsTheModelOutOfTheProjectsBesideIt() throws IOException {
        Files.createDirectories(repository.resolve("other"));
        Files.writeString(repository.resolve("other/.env"), "TOKEN=secret");
        FileTools tools = new FileTools(repository, repository.resolve("app"), null);

        assertTrue(tools.readFile("app/A.java").contains("class A"));
        String refused = tools.readFile("other/.env");
        assertTrue(refused.startsWith("Refused") && refused.contains("'app'"), refused);
        assertTrue(tools.writeFile("other/new.java", "no").startsWith("Refused"));
        assertFalse(Files.exists(repository.resolve("other/new.java")));
        assertTrue(tools.writeFile("app/B.java", "repaired").startsWith("Wrote"));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "creating a symbolic link needs privileges on Windows")
    @DisplayName("a link inside the component that leads out of it is refused, as .. is")
    void aLinkThatLeadsOutIsRefused() throws IOException {
        Path outside = Files.createDirectories(repository.resolve("other"));
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(repository.resolve("app/link"), outside);
        Files.createSymbolicLink(repository.resolve("app/dangling.java"), outside.resolve("created.java"));
        FileTools tools = new FileTools(repository, repository.resolve("app"), null);

        assertTrue(tools.readFile("app/link/secret.txt").startsWith("Refused"));
        assertTrue(tools.writeFile("app/link/new.java", "no").startsWith("Refused"));
        assertTrue(tools.writeFile("app/dangling.java", "no").startsWith("Refused"));
        assertFalse(Files.exists(outside.resolve("new.java")));
        assertFalse(Files.exists(outside.resolve("created.java")));
    }

    @Test
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "creating a symbolic link needs privileges on Windows")
    @DisplayName("a link that stays inside the component is followed")
    void aLinkThatStaysInsideIsFollowed() throws IOException {
        Files.createSymbolicLink(repository.resolve("app/alias.java"), repository.resolve("app/A.java"));
        FileTools tools = new FileTools(repository, repository.resolve("app"), null);

        assertTrue(tools.readFile("app/alias.java").contains("class A"));
    }
}
