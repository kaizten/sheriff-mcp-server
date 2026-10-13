package com.kaizten.sheriff.infrastructure.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.kaizten.sheriff.infrastructure.process.FakeProcessRunner;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

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
    @DisplayName("what a build writes is covered with an empty folder, so Sheriff never analyzes generated code")
    void coversTheBuildOutput(@TempDir Path repository) throws IOException {
        Path app = repository.resolve("app");
        Files.createDirectories(app.resolve("build/generated"));
        Files.createDirectories(app.resolve(".gradle"));
        Files.createDirectories(app.resolve("target"));
        Files.writeString(app.resolve("pom.xml"), "<project/>");
        Files.writeString(app.resolve("build.gradle"), "");
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "", ""));
        new SheriffContainer(runner, "kaizten/sheriff:latest", repository, Duration.ofSeconds(5),
                () -> "sheriff-agent-test").run(List.of("test", "--test", "JAVA", "--component", "app"));
        assertEquals(List.of("docker", "run", "--rm", "--name", "sheriff-agent-test",
                "--mount", SheriffContainer.bindMount(repository),
                "--mount", "type=tmpfs,destination=/data/app/target",
                "--mount", "type=tmpfs,destination=/data/app/build",
                "--mount", "type=tmpfs,destination=/data/app/.gradle",
                "kaizten/sheriff:latest", "test", "--test", "JAVA", "--component", "app"), runner.command());
    }

    @Test
    @DisplayName("a folder is covered only when it is there and the component's build tool writes it")
    void coversOnlyTheBuildToolsOwnOutput(@TempDir Path component) throws IOException {
        Files.createDirectories(component.resolve("build"));
        Files.createDirectories(component.resolve("target"));
        assertEquals(List.of(), SheriffContainer.buildOutput(component), "no build file: a build/ may be source");
        Files.writeString(component.resolve("build.gradle.kts"), "");
        assertEquals(List.of("build"), SheriffContainer.buildOutput(component));
        Files.writeString(component.resolve("pom.xml"), "<project/>");
        assertEquals(List.of("target", "build"), SheriffContainer.buildOutput(component));
    }

    @Test
    @DisplayName("the component comes from --component, and a run with none covers nothing")
    void readsTheComponentFromTheArguments() {
        assertEquals(Optional.of("api"), SheriffContainer.componentOf(List.of("test", "--component", "api")));
        assertEquals(Optional.empty(), SheriffContainer.componentOf(List.of("tree", "--export-profile", "JSON")));
        assertEquals(Optional.empty(), SheriffContainer.componentOf(List.of("test", "--component")));
        assertEquals("type=tmpfs,\"destination=/data/x,y/target\"", SheriffContainer.hiddenMount("x,y", "target"));
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
