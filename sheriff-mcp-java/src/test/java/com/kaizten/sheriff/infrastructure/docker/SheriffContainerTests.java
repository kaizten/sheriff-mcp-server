package com.kaizten.sheriff.infrastructure.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.kaizten.sheriff.infrastructure.process.FakeProcessRunner;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Tests for how one Sheriff run is launched, and what happens to its
 * container when the run outlives its deadline.
 */
class SheriffContainerTests {

    private static final Path REPOSITORY = Path.of("/repo");

    private static SheriffContainer container(FakeProcessRunner runner) {
        return new SheriffContainer(runner, "kaizten/sheriff:latest", REPOSITORY, Duration.ofSeconds(5),
                () -> "sheriff-agent-test");
    }

    @Test
    @DisplayName("a directory whose name holds ':' or ',' is still mounted, which -v could not do with ':'")
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "':' and '\"' cannot be in a Windows file name")
    void mountsAnyDirectoryName() {
        assertEquals("type=bind,source=/home/a/projects:2026,target=/data",
                SheriffContainer.bindMount(Path.of("/home/a/projects:2026")));
        assertEquals("type=bind,\"source=/home/a/x,y\",target=/data",
                SheriffContainer.bindMount(Path.of("/home/a/x,y")));
        assertEquals("type=bind,\"source=/home/a/say \"\"hi\"\"\",target=/data",
                SheriffContainer.bindMount(Path.of("/home/a/say \"hi\"")));
    }

    @Test
    void namesTheContainerAndMountsTheRepository() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "", ""));
        container(runner).run(List.of("test", "--test", "JAVA"));
        assertEquals(List.of("docker", "run", "--rm", "--name", "sheriff-agent-test", "--mount", "type=bind,source=" + REPOSITORY + ",target=/data",
                "kaizten/sheriff:latest", "test", "--test", "JAVA"), runner.command());
    }

    @Test
    @DisplayName("a run that times out has its container removed, or it finishes later and litters the repository")
    void aTimedOutRunHasItsContainerRemoved() {
        FakeProcessRunner runner = new FakeProcessRunner(List.of(
                ProcessOutcome.timedOut("did not respond within 5s."), ProcessOutcome.completed(0, "", "")));
        ProcessOutcome outcome = container(runner).run(List.of("test"));
        assertEquals("did not respond within 5s.", outcome.failure());
        assertEquals(List.of("docker", "rm", "-f", "sheriff-agent-test"), runner.commands().get(1));
    }

    @Test
    void aRunThatFinishesLeavesNothingToRemove() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "", ""));
        container(runner).run(List.of("test"));
        assertEquals(1, runner.commands().size());
    }
}
