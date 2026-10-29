package com.kaizten.sheriff.infrastructure.docker;

import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import com.kaizten.sheriff.infrastructure.process.ProcessStatus;
import com.kaizten.sheriff.infrastructure.process.TemporaryFolders;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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
 *
 * <p>Sheriff analyzes a folder of what it mounts at {@code /data}, so a run
 * mounts at {@code /data} a state folder of the component's own, outside the
 * project, and the component alone at {@code /data/<component>}: Sheriff's
 * arguments and the paths it reports are the ones a mount of the parent
 * gave, but it sees nothing beside the component, and the three state files
 * it writes at the root land in that folder rather than in the user's tree.
 * Mounting the parent let a component's neighbours change what Sheriff
 * reports for it: on 16 October, {@code JAVA_HEXAGONAL} reported
 * {@code FilenamePascalCase} in a component only when another project in the
 * same folder had a {@code domain/} directory. Through these tools that
 * difference did not show, as the base profile run first reports the same
 * files and {@link KnownFalsePositives} drops {@code package-info.java}, and
 * across three Java components, a TypeScript one and a Python one, under ten
 * profiles, nothing else differed; but nothing makes it so for every rule.
 * The parent's mount also left root-owned state files in the folder of
 * every project beside it, and made each of them wait for the others' runs.
 *
 * <p>Sheriff runs from the image's root, whatever {@code WORKDIR} the image
 * declares: it finds its TypeScript analyzer in {@code ./node_modules}, which
 * is under {@code /}. The image of 29 October ({@code sha256:e03d9daf})
 * declares {@code /data}, and there every TypeScript analysis reported 0
 * errors and exited 0, with Node's "Cannot find module" only on stdout.
 */
public final class SheriffContainer {

    private static final String DOCKER = "docker";
    private static final String RUN = "run";
    private static final String REMOVE_WHEN_DONE = "--rm";
    private static final String NAME_FLAG = "--name";
    private static final String WORKDIR_FLAG = "--workdir";
    private static final String CONTAINER_MOUNT = "/data";
    private static final String MOUNT_FLAG = "--mount";
    private static final String BIND_MOUNT = "type=bind,%s,target=" + CONTAINER_MOUNT;
    private static final String SOURCE_FIELD = "source=";
    private static final String TARGET_FIELD = "target=";
    private static final String COMPONENT_MOUNT = "type=bind,%s,%s";
    private static final String TMPFS_MOUNT = "type=tmpfs,%s";
    private static final String DESTINATION_FIELD = "destination=";
    private static final String SLASH = "/";
    private static final String IMAGE_ROOT = SLASH;
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
    private static final String STATE_HOME_PROPERTY = "sheriff.state.home";
    private static final String CACHE_HOME_VARIABLE = "XDG_CACHE_HOME";
    private static final String USER_HOME_PROPERTY = "user.home";
    private static final String CACHE_FOLDER = ".cache";
    private static final String TOOL_FOLDER = "sheriff-mcp";
    private static final String STATE_FOLDER = "state";
    private static final String DIGEST = "SHA-256";
    private static final int STATE_NAME_LENGTH = 16;
    private static final int FIRST = 0;
    private static final String NO_STATE_FOLDER = "Could not create Sheriff's state folder %s: %s";
    private static final String STATE_NAME_REGEX = "[0-9a-f]{16}";
    private static final Pattern STATE_NAME = Pattern.compile(STATE_NAME_REGEX);
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
        return MountLock.holding(lockFor(componentOf(arguments)), () -> runLocked(arguments));
    }

    /**
     * What a run holds while it runs: the component it names, whose state
     * folder nothing else may touch meanwhile, or the whole mount for a run
     * that names none.
     *
     * @param component the component the run names, if any
     * @return the path the lock is taken on
     */
    Path lockFor(Optional<String> component) {
        return component.map(repositoryRoot::resolve).orElse(repositoryRoot);
    }

    /**
     * The folder Sheriff's state files go to for one component: under
     * {@code ${XDG_CACHE_HOME:-~/.cache}/sheriff-mcp/state/}, named after a
     * digest of the component's absolute path, or under the folder the
     * {@code sheriff.state.home} property names, which the tests set. A
     * folder in the home rather than the temporary directory, because
     * Docker installed as a snap sees a private {@code /tmp} of its own.
     *
     * @param component the component, as Sheriff names it
     * @return that folder, not created
     */
    public Path stateFolder(String component) {
        Path directory = repositoryRoot.resolve(component).toAbsolutePath().normalize();
        return stateHome().resolve(digest(directory.toString()));
    }

    /**
     * Removes the state folders no run has used for a week: each holds, after
     * its runs, only the empty folder Docker made to mount the component on.
     * Only folders named the way {@link #stateFolder} names them are touched.
     * Best effort, like every clean-up here.
     *
     * @param home the folder that holds every component's state folder
     */
    static void pruneStateFolders(Path home) {
        Instant oldest = Instant.now().minus(TemporaryFolders.MARKER_LIFETIME);
        List<Path> folders;
        try (Stream<Path> entries = Files.list(home)) {
            folders = entries.filter(folder -> STATE_NAME.matcher(folder.getFileName().toString()).matches()
                    && Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)).toList();
        } catch (IOException exception) {
            return;
        }
        for (Path folder : folders) {
            try {
                if (Files.getLastModifiedTime(folder).toInstant().isBefore(oldest)) {
                    removeFolder(folder);
                }
            } catch (IOException exception) {
                continue;
            }
        }
    }

    /**
     * Deletes a state folder and what is in it, the deepest first.
     *
     * @param folder the folder
     * @throws IOException when something in it cannot be deleted
     */
    private static void removeFolder(Path folder) throws IOException {
        List<Path> inside;
        try (Stream<Path> walk = Files.walk(folder)) {
            inside = walk.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : inside) {
            Files.deleteIfExists(path);
        }
    }

    /**
     * Where every component's state folder is.
     *
     * @return that folder
     */
    private static Path stateHome() {
        String configured = System.getProperty(STATE_HOME_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured);
        }
        String cache = System.getenv(CACHE_HOME_VARIABLE);
        Path base = cache == null || cache.isBlank()
                ? Path.of(System.getProperty(USER_HOME_PROPERTY), CACHE_FOLDER) : Path.of(cache);
        return base.resolve(TOOL_FOLDER).resolve(STATE_FOLDER);
    }

    /**
     * A short digest of a text, for a folder name.
     *
     * @param text what to digest
     * @return its first hexadecimal characters
     */
    private static String digest(String text) {
        try {
            byte[] hash = MessageDigest.getInstance(DIGEST).digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(FIRST, STATE_NAME_LENGTH);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * One run, with its lock already held, in the component's state folder,
     * created when missing.
     *
     * @param arguments what to pass to Sheriff
     * @return what the run produced
     */
    private ProcessOutcome runLocked(List<String> arguments) {
        Optional<Path> state = componentOf(arguments).map(this::stateFolder);
        Path directory = state.orElse(repositoryRoot);
        try {
            if (state.isPresent()) {
                pruneStateFolders(directory.getParent());
                Files.createDirectories(directory);
            }
        } catch (IOException exception) {
            return ProcessOutcome.unavailable(String.format(NO_STATE_FOLDER, directory, exception.getMessage()));
        }
        String name = names.get();
        ProcessOutcome outcome = processes.run(command(name, arguments), directory, Map.of(), timeout);
        if (outcome.status() == ProcessStatus.TIMED_OUT) {
            processes.run(List.of(DOCKER, REMOVE_CONTAINER, FORCE, name), repositoryRoot, Map.of(), REMOVAL_TIMEOUT);
        }
        return outcome;
    }

    /**
     * The full command line for one run: the state folder at {@code /data}
     * and the component inside it, or, for a run that names no component,
     * the whole mount there.
     *
     * @param name the container's name
     * @param arguments what to pass to Sheriff
     * @return the {@code docker run} invocation
     */
    List<String> command(String name, List<String> arguments) {
        List<String> command = new ArrayList<>(List.of(DOCKER, RUN, REMOVE_WHEN_DONE, NAME_FLAG, name, WORKDIR_FLAG, IMAGE_ROOT));
        Optional<String> named = componentOf(arguments);
        if (named.isEmpty()) {
            command.addAll(List.of(MOUNT_FLAG, bindMount(repositoryRoot)));
        }
        named.ifPresent(component -> {
            command.addAll(List.of(MOUNT_FLAG, bindMount(stateFolder(component)),
                    MOUNT_FLAG, componentMount(repositoryRoot.resolve(component), component)));
            buildOutput(repositoryRoot.resolve(component))
                    .forEach(output -> command.addAll(List.of(MOUNT_FLAG, hiddenMount(component, output))));
        });
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
    public static List<String> buildOutput(Path component) {
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
     * The {@code --mount} value that binds the component's own directory at
     * {@code /data/<component>}, inside the state folder bound at
     * {@code /data}.
     *
     * @param directory the component's directory
     * @param component its name, as Sheriff names it
     * @return that value
     */
    static String componentMount(Path directory, String component) {
        return String.format(COMPONENT_MOUNT, csvField(SOURCE_FIELD + directory),
                csvField(TARGET_FIELD + CONTAINER_MOUNT + SLASH + component));
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
