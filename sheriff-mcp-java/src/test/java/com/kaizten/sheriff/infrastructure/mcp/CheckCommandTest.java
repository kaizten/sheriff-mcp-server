package com.kaizten.sheriff.infrastructure.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The jar as a CI gate: which exit code each outcome gets, without Docker.
 */
final class CheckCommandTest {

    private static final String ONE_ERROR = "[{\"file\":\"/data/api/A.java\",\"description\":\"No JavaDoc\","
            + "\"howToSolve\":\"Add one.\",\"referenceCode\":\"R\",\"type\":\"ERROR\"}]";

    @TempDir
    Path workspace;

    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();

    private int check(ProcessOutcome outcome, String... arguments) throws IOException {
        Files.createDirectories(workspace.resolve("api/src"));
        Files.writeString(workspace.resolve("api/src/A.java"), "class A {}");
        McpConfig config = new McpConfig(Map.of(), workspace);
        ProcessRunner processes = (command, directory, environment, timeout) -> {
            int test = command.indexOf("test");
            int profile = command.indexOf("--test");
            int component = command.indexOf("--component");
            if (outcome.succeeded() && test >= 0 && profile >= 0 && component >= 0) {
                try {
                    Files.writeString(directory.resolve("sheriff_errors.json"),
                            "{\"file:/data\":{\"" + command.get(component + 1) + "\":{\""
                                    + command.get(profile + 1) + "\":"
                                    + (outcome.standardOutput().indexOf('[') < 0 ? "[]"
                                            : outcome.standardOutput().substring(outcome.standardOutput().indexOf('[')))
                                    + "}}}");
                } catch (IOException exception) {
                    throw new java.io.UncheckedIOException(exception);
                }
            }
            return outcome;
        };
        SheriffRunner runner = new SheriffRunner(processes, config.image(), config.repository(), Duration.ofSeconds(5));
        PrintStream output = new PrintStream(printed, true, StandardCharsets.UTF_8);
        return new CheckCommand(config, runner, output).run(List.of(arguments));
    }

    private String printed() {
        return printed.toString(StandardCharsets.UTF_8);
    }

    @Test
    void aCleanProjectPasses() throws IOException {
        assertEquals(CheckCommand.PASSED, check(ProcessOutcome.completed(0, "", ""), "--check"));
        assertTrue(printed().contains("api: 0 errors under JAVA"));
    }

    @Test
    void errorsFailTheCheckAndAreListed() throws IOException {
        assertEquals(CheckCommand.ERRORS_FOUND, check(ProcessOutcome.completed(0, ONE_ERROR, ""), "--check"));
        assertTrue(printed().contains("No JavaDoc"));
    }

    @Test
    @DisplayName("zero errors on a component with nothing of the profile's language is not a pass")
    void nothingAnalyzedIsNotAPass() throws IOException {
        assertEquals(CheckCommand.NOT_ANALYZED,
                check(ProcessOutcome.completed(0, "", ""), "--check", "--profile", "TYPESCRIPT"));
        assertTrue(printed().contains("nothing was analyzed"));
    }

    @Test
    void sheriffNotRunningIsNotAPass() throws IOException {
        assertEquals(CheckCommand.NOT_ANALYZED, check(ProcessOutcome.unavailable("no docker"), "--check"));
    }

    @Test
    void aProjectWithNothingToAnalyzeSaysSo() {
        McpConfig config = new McpConfig(Map.of(), workspace);
        SheriffRunner runner = new SheriffRunner((command, directory, environment, timeout) -> null,
                config.image(), config.repository(), Duration.ofSeconds(5));
        int exit = new CheckCommand(config, runner, new PrintStream(printed, true, StandardCharsets.UTF_8))
                .run(List.of("--check"));
        assertEquals(CheckCommand.NOT_ANALYZED, exit);
        assertTrue(printed().contains("Nothing to check"));
    }
}
