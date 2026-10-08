package com.kaizten.sheriff.infrastructure.docker;

import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import com.kaizten.sheriff.infrastructure.process.ProcessStatus;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
                MOUNT_FLAG, bindMount(repositoryRoot),
                image));
        command.addAll(arguments);
        return command;
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
        String source = directory.toString();
        boolean special = source.chars().anyMatch(character -> CSV_SPECIAL.indexOf(character) != NOT_FOUND);
        String field = SOURCE_FIELD + source;
        String quoted = special ? QUOTE + field.replace(QUOTE, ESCAPED_QUOTE) + QUOTE : field;
        return String.format(BIND_MOUNT, quoted);
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
