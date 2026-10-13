package com.kaizten.sheriff.infrastructure.docker;

import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import com.kaizten.sheriff.infrastructure.process.ProcessStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * One run of the Sheriff image against a mounted repository, as a container
 * with a name of its own, removed by force when it outlives its deadline.
 *
 * <p>The name is the whole point. A timeout kills the process this agent
 * started, which is the {@code docker} client, and not the container the
 * client asked the daemon for. That container went on analyzing, finished
 * after the agent had already cleaned up and moved on, and dropped Sheriff's
 * three state files, owned by root, at the root of the repository: the next
 * run then found a dirty tree it had not made. Knowing the container's name
 * is what makes it possible to stop it.
 */
public final class SheriffContainer {

    private static final String DOCKER = "docker";
    private static final String RUN = "run";
    private static final String REMOVE_WHEN_DONE = "--rm";
    private static final String NAME_FLAG = "--name";
    private static final String CONTAINER_MOUNT = "/data";
    private static final String MOUNT_FLAG = "--mount";
    private static final String BIND_MOUNT = "type=bind,%s,target=" + CONTAINER_MOUNT;
    private static final String SOURCE_FIELD = "source=";
    private static final String TMPFS_MOUNT = "type=tmpfs,%s";
    private static final String DESTINATION_FIELD = "destination=";
    private static final String SLASH = "/";
    private static final String COMPONENT_FLAG = "--component";
    private static final int NEXT = 1;
    private static final String MAVEN_BUILD = "pom.xml";
    private static final String MAVEN_OUTPUT = "target";
    private static final String GRADLE_BUILD = "build.gradle";
    private static final String GRADLE_KOTLIN_BUILD = "build.gradle.kts";
    private static final List<String> GRADLE_BUILDS = List.of(GRADLE_BUILD, GRADLE_KOTLIN_BUILD);
    private static final String GRADLE_OUTPUT = "build";
    private static final String GRADLE_CACHE = ".gradle";
    private static final List<String> GRADLE_OUTPUTS = List.of(GRADLE_OUTPUT, GRADLE_CACHE);
    private static final String QUOTE = "\"";
    private static final String ESCAPED_QUOTE = "\"\"";
    private static final int NOT_FOUND = -1;
    private static final String CSV_SPECIAL = ",\"";
    private static final String REMOVE_CONTAINER = "rm";
    private static final String FORCE = "-f";
    private static final String NAME_PREFIX = "sheriff-agent-";
    private static final Duration REMOVAL_TIMEOUT = Duration.ofSeconds(30);

    private final ProcessRunner processes;
    private final String image;
    private final Path repositoryRoot;
    private final Duration timeout;
    private final Supplier<String> names;

    /**
     * Wires runs of one image against one repository.
     *
     * @param processes how to run Docker
     * @param image the Sheriff image to run
     * @param repositoryRoot the directory to mount at {@code /data}
     * @param timeout how long one run is given
     */
    public SheriffContainer(ProcessRunner processes, String image, Path repositoryRoot, Duration timeout) {
        this(processes, image, repositoryRoot, timeout, SheriffContainer::uniqueName);
    }

    /**
     * Wires runs with a chosen way of naming containers, so a test can assert
     * the exact command line.
     *
     * @param processes how to run Docker
     * @param image the Sheriff image to run
     * @param repositoryRoot the directory to mount at {@code /data}
     * @param timeout how long one run is given
     * @param names where each run's container name comes from
     */
    SheriffContainer(
            ProcessRunner processes, String image, Path repositoryRoot, Duration timeout, Supplier<String> names) {
        this.processes = processes;
        this.image = image;
        this.repositoryRoot = repositoryRoot;
        this.timeout = timeout;
        this.names = names;
    }

    /**
     * Runs Sheriff once with the given arguments, and removes the container
     * if it was still running when the deadline passed.
     *
     * @param arguments what to pass to Sheriff: a subcommand and its flags
     * @return what the run produced
     */
    public ProcessOutcome run(List<String> arguments) {
        return MountLock.holding(repositoryRoot, () -> runLocked(arguments));
    }

    /**
     * One run, with the mount's lock already held.
     *
     * @param arguments what to pass to Sheriff
     * @return what the run produced
     */
    private ProcessOutcome runLocked(List<String> arguments) {
        String name = names.get();
        ProcessOutcome outcome = processes.run(command(name, arguments), repositoryRoot, Map.of(), timeout);
        if (outcome.status() == ProcessStatus.TIMED_OUT) {
            processes.run(List.of(DOCKER, REMOVE_CONTAINER, FORCE, name), repositoryRoot, Map.of(), REMOVAL_TIMEOUT);
        }
        return outcome;
    }

    /**
     * The full command line for one run.
     *
     * @param name the container's name
     * @param arguments what to pass to Sheriff
     * @return the {@code docker run} invocation
     */
    List<String> command(String name, List<String> arguments) {
        List<String> command = new ArrayList<>(List.of(
                DOCKER, RUN, REMOVE_WHEN_DONE,
                NAME_FLAG, name,
                MOUNT_FLAG, bindMount(repositoryRoot)));
        componentOf(arguments).ifPresent(component -> buildOutput(repositoryRoot.resolve(component))
                .forEach(output -> command.addAll(List.of(MOUNT_FLAG, hiddenMount(component, output)))));
        command.add(image);
        command.addAll(arguments);
        return command;
    }

    /**
     * The component a run names, from its {@code --component} argument.
     *
     * @param arguments what is passed to Sheriff
     * @return the component, or empty when the run names none
     */
    static Optional<String> componentOf(List<String> arguments) {
        int flag = arguments.indexOf(COMPONENT_FLAG);
        if (flag == NOT_FOUND || flag + NEXT >= arguments.size()) {
            return Optional.empty();
        }
        return Optional.of(arguments.get(flag + NEXT));
    }

    /**
     * The folders of a component its build writes, which hold no code of the
     * project's: {@code target} beside a {@code pom.xml}, {@code build} and
     * {@code .gradle} beside a {@code build.gradle}. Sheriff analyzes every
     * file of a component and has no way to leave a folder out, so it read
     * the code Gradle generates there too: on PetClinic, 10,082 of 10,311
     * errors were in {@code build/generated}, and through WSL's Docker from
     * {@code C:\} the analysis took 7 min 27 s instead of 6 s (13 October).
     *
     * @param component the component's directory
     * @return those of them that are there
     */
    static List<String> buildOutput(Path component) {
        List<String> outputs = new ArrayList<>();
        if (Files.isRegularFile(component.resolve(MAVEN_BUILD))) {
            outputs.add(MAVEN_OUTPUT);
        }
        if (GRADLE_BUILDS.stream().anyMatch(build -> Files.isRegularFile(component.resolve(build)))) {
            outputs.addAll(GRADLE_OUTPUTS);
        }
        return outputs.stream().filter(output -> Files.isDirectory(component.resolve(output))).toList();
    }

    /**
     * The {@code --mount} value that covers a folder of the component with an
     * empty one in the container, so Sheriff neither reads nor repairs what
     * is in it, and nothing on the disk changes.
     *
     * @param component the component, as Sheriff names it
     * @param output the folder to cover
     * @return that value
     */
    static String hiddenMount(String component, String output) {
        return String.format(TMPFS_MOUNT, csvField(DESTINATION_FIELD + CONTAINER_MOUNT + SLASH + component
                + SLASH + output));
    }

    /**
     * The {@code --mount} value that binds a directory at {@code /data}.
     *
     * <p>{@code --mount} rather than {@code -v}: {@code -v} splits its value
     * on {@code :}, so a directory named {@code projects:2026}, legal on Linux
     * and macOS, failed every analysis with "invalid mode: /data". {@code
     * --mount} is comma-separated instead, and quotes a value that holds a
     * comma or a quote the way CSV does: the whole field, {@code "source=..."},
     * since Docker refuses a quote that does not open one.
     *
     * @param directory the directory to mount
     * @return that value
     */
    static String bindMount(Path directory) {
        return String.format(BIND_MOUNT, csvField(SOURCE_FIELD + directory));
    }

    /**
     * One field of a {@code --mount} value, quoted whole when it holds a comma
     * or a quote.
     *
     * @param field the field, its name included
     * @return it, quoted when it must be
     */
    private static String csvField(String field) {
        boolean special = field.chars().anyMatch(character -> CSV_SPECIAL.indexOf(character) != NOT_FOUND);
        return special ? QUOTE + field.replace(QUOTE, ESCAPED_QUOTE) + QUOTE : field;
    }

    /**
     * A container name no other run will have.
     *
     * @return that name
     */
    private static String uniqueName() {
        return NAME_PREFIX + UUID.randomUUID();
    }
}
