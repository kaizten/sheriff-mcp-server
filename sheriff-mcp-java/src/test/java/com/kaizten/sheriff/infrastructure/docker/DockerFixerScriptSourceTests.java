package com.kaizten.sheriff.infrastructure.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaizten.sheriff.infrastructure.process.FakeProcessRunner;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for reading a fixer script out of the image: the command it builds,
 * and the paths it refuses to build one for.
 */
class DockerFixerScriptSourceTests {

    private static final String IMAGE = "sheriff:test";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static DockerFixerScriptSource sourceOver(FakeProcessRunner runner) {
        return new DockerFixerScriptSource(runner, IMAGE, TIMEOUT);
    }

    @Test
    void shouldReturnTheScriptsText() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "print('hi')\n", ""));
        assertEquals("print('hi')\n", sourceOver(runner).read("java/tests/a/a.py"));
    }

    @Test
    void shouldReadFromUnderTheFixersRoot() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "x", ""));
        sourceOver(runner).read("java/tests/a/a.py");
        List<String> command = runner.commands().get(0);
        assertTrue(command.contains(IMAGE));
        assertEquals("cat '" + DockerFixerScriptSource.FIXERS_ROOT + "/java/tests/a/a.py'",
                command.get(command.size() - 1));
    }

    @Test
    void shouldMountNothing() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "x", ""));
        sourceOver(runner).read("java/a.py");
        assertFalse(runner.commands().get(0).contains("-v"));
    }

    @Test
    void shouldTreatALeadingSlashAsPartOfTheRelativePath() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "x", ""));
        sourceOver(runner).read("/java/a.py");
        List<String> command = runner.commands().get(0);
        assertEquals("cat '" + DockerFixerScriptSource.FIXERS_ROOT + "/java/a.py'",
                command.get(command.size() - 1));
    }

    @Test
    void shouldRefuseToClimbOutOfTheFixersDirectory() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "x", ""));
        DockerFixerScriptSource source = sourceOver(runner);
        for (String path : List.of("../etc/passwd", "java/../../etc/passwd", "", "   ", "/")) {
            assertThrows(IllegalArgumentException.class, () -> source.read(path));
        }
        assertThrows(IllegalArgumentException.class, () -> source.read(null));
        assertTrue(runner.commands().isEmpty());
    }

    @Test
    @DisplayName("a quote in the path would close the shell quoting and run what follows")
    void shouldRefuseAPathThatWouldEscapeTheShellQuoting() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "x", ""));
        DockerFixerScriptSource source = sourceOver(runner);
        for (String path : List.of("java/a'.py", "a.py'; echo pwned; echo '", "$(id).py", "`id`.py", "a b.py")) {
            assertThrows(IllegalArgumentException.class, () -> source.read(path));
        }
        assertTrue(runner.commands().isEmpty());
    }

    @Test
    @DisplayName("every fixer path in the committed catalog is still accepted")
    void shouldStillAcceptEveryRealFixerPath() throws Exception {
        Path catalog = Path.of("rules_catalog.json");
        assumeTrue(Files.exists(catalog));
        JsonNode rules = new ObjectMapper().readTree(Files.readString(catalog)).path("rules");
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "x", ""));
        DockerFixerScriptSource source = sourceOver(runner);
        int checked = 0;
        for (JsonNode rule : rules) {
            String fixer = rule.path("fixer").asText();
            if (fixer.isEmpty()) {
                continue;
            }
            source.read(fixer);
            checked++;
        }
        assertTrue(checked > 50);
    }

    @Test
    void shouldTreatADoubleDotInsideANameAsPartOfIt() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "x", ""));
        sourceOver(runner).read("java/a..b.py");
        List<String> command = runner.commands().get(0);
        assertTrue(command.get(command.size() - 1).contains("a..b.py"));
    }

    @Test
    void shouldReportDockersOwnError() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(1, "", "no such file"));
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> sourceOver(runner).read("java/missing.py"));
        assertTrue(failure.getMessage().contains("no such file"));
    }

    @Test
    void shouldReportThatDockerNeverRan() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.unavailable("docker is not installed"));
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> sourceOver(runner).read("java/a.py"));
        assertTrue(failure.getMessage().contains("docker is not installed"));
    }

    @Test
    void shouldNameTheScriptWhenNothingElseExplainsTheFailure() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(2, "", ""));
        IllegalStateException failure =
                assertThrows(IllegalStateException.class, () -> sourceOver(runner).read("java/a.py"));
        assertTrue(failure.getMessage().contains("java/a.py"));
    }
}
