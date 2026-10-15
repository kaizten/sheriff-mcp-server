package com.kaizten.sheriff.infrastructure.docker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.kaizten.sheriff.infrastructure.process.FakeProcessRunner;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
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
        return container(runner, REPOSITORY);
    }

    private static SheriffContainer container(FakeProcessRunner runner, Path repository) {
        return new SheriffContainer(runner, "kaizten/sheriff:latest", repository, Duration.ofSeconds(5),
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
        container(runner, repository).run(List.of("test", "--test", "JAVA", "--component", "app"));
        assertEquals(List.of("docker", "run", "--rm", "--name", "sheriff-agent-test",
                "--mount", SheriffContainer.bindMount(container(runner, repository).stateFolder("app")),
                "--mount", "type=bind,source=" + app + ",target=/data/app",
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
    @DisplayName("a component is mounted alone, inside a state folder of its own: nothing beside it is seen")
    void mountsTheComponentAloneInsideItsStateFolder(@TempDir Path repository) throws IOException {
        Path app = repository.resolve("app");
        Files.createDirectories(app.resolve("src"));
        Files.createDirectories(repository.resolve("neighbour/domain"));
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "", ""));
        SheriffContainer container = container(runner, repository);
        container.run(List.of("test", "--test", "JAVA", "--uri", "file:/data", "--component", "app"));
        Path state = container.stateFolder("app");
        assertEquals(List.of("docker", "run", "--rm", "--name", "sheriff-agent-test",
                "--mount", "type=bind,source=" + state + ",target=/data",
                "--mount", "type=bind,source=" + app + ",target=/data/app",
                "kaizten/sheriff:latest", "test", "--test", "JAVA", "--uri", "file:/data", "--component", "app"),
                runner.command());
        assertEquals(state, runner.directory(), "Sheriff's state lands in the folder it runs in");
        assertTrue(Files.isDirectory(state), "the state folder is made before Docker is asked to mount it");
        assertFalse(state.startsWith(repository), "the state folder is never inside the project");
        assertEquals(List.of("app", "neighbour"), Files.list(repository).map(path -> path.getFileName().toString())
                .sorted().toList(), "nothing is written into the mount");
    }

    @Test
    @DisplayName("each component has a state folder of its own, the same every time, under sheriff.state.home")
    void eachComponentHasItsOwnStateFolder(@TempDir Path repository) {
        SheriffContainer container = container(FakeProcessRunner.always(ProcessOutcome.completed(0, "", "")),
                repository);
        Path app = container.stateFolder("app");
        assertEquals(app, container.stateFolder("app"));
        assertNotEquals(app, container.stateFolder("api"));
        assertEquals(Path.of(System.getProperty("sheriff.state.home")), app.getParent());
        assertEquals(16, app.getFileName().toString().length());
        assertNotEquals(app, container(FakeProcessRunner.always(ProcessOutcome.completed(0, "", "")),
                repository.resolve("elsewhere")).stateFolder("app"), "two projects with a module of the same name");
    }

    @Test
    @DisplayName("a run locks its component, not the folder of every project beside it")
    void locksTheComponent() {
        SheriffContainer container = container(FakeProcessRunner.always(ProcessOutcome.completed(0, "", "")));
        assertEquals(REPOSITORY.resolve("app"), container.lockFor(Optional.of("app")));
        assertEquals(REPOSITORY, container.lockFor(Optional.empty()));
    }

    @Test
    @DisplayName("the component's mount quotes its source and target the way --mount needs")
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "':' and '\"' cannot be in a Windows file name")
    void quotesTheComponentsMount() {
        assertEquals("type=bind,source=/home/a/app,target=/data/app",
                SheriffContainer.componentMount(Path.of("/home/a/app"), "app"));
        assertEquals("type=bind,\"source=/home/a/x,y\",\"target=/data/x,y\"",
                SheriffContainer.componentMount(Path.of("/home/a/x,y"), "x,y"));
    }

    @Test
    @DisplayName("a state folder that cannot be made is a run that did not happen, said in words")
    @DisabledOnOs(value = OS.WINDOWS, disabledReason = "the read-only parent is set with POSIX permissions")
    void anUnwritableStateFolderIsReported(@TempDir Path repository) throws IOException {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "", ""));
        SheriffContainer container = container(runner, repository);
        Path state = container.stateFolder("app");
        Files.createDirectories(state.getParent());
        Files.writeString(state, "a file where the folder should be");
        try {
            ProcessOutcome outcome = container.run(List.of("test", "--component", "app"));
            assertFalse(outcome.ran());
            assertTrue(outcome.failure().contains("state folder"));
            assertEquals(List.of(), runner.commands());
        } finally {
            Files.delete(state);
        }
    }

    @Test
    @DisplayName("state folders unused for a week go, with the empty folder Docker mounted on; nothing else does")
    void prunesStateFoldersNoRunHasUsed(@TempDir Path home) throws IOException {
        Path old = Files.createDirectories(home.resolve("0123456789abcdef/app"));
        Path recent = Files.createDirectories(home.resolve("fedcba9876543210"));
        Path foreign = Files.createDirectories(home.resolve("not-a-state-folder"));
        FileTime longAgo = FileTime.from(Instant.now().minus(Duration.ofDays(8)));
        Files.setLastModifiedTime(old.getParent(), longAgo);
        Files.setLastModifiedTime(foreign, longAgo);
        SheriffContainer.pruneStateFolders(home);
        assertFalse(Files.exists(old.getParent()));
        assertTrue(Files.isDirectory(recent));
        assertTrue(Files.isDirectory(foreign));
        SheriffContainer.pruneStateFolders(home.resolve("missing"));
    }

    @Test
    void aRunThatFinishesLeavesNothingToRemove() {
        FakeProcessRunner runner = FakeProcessRunner.always(ProcessOutcome.completed(0, "", ""));
        container(runner).run(List.of("test"));
        assertEquals(1, runner.commands().size());
    }
}
