package com.kaizten.sheriff.infrastructure.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kaizten.sheriff.domain.Rules;
import com.kaizten.sheriff.infrastructure.config.DeclaredProfile;
import com.kaizten.sheriff.infrastructure.process.Platform;
import com.kaizten.sheriff.infrastructure.shell.ShellTestRunner;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a project looks like to Sheriff, worked out from the project itself so
 * that the server needs no configuration to be dropped into one.
 *
 * <p>Sheriff analyzes a <em>subdirectory</em> of what it mounts, so a project
 * is always "a directory to mount, and the component inside it". A project
 * with its sources at the root ({@code src/} beside the build file) is one
 * module: its parent is mounted and the project is the component. Anything
 * else holds its components one level down, and every subdirectory with
 * sources of a language Sheriff knows is one. The test is {@code src/}, not a
 * build file, because a Maven aggregator has a {@code pom.xml} at its root
 * and its modules below it; that is the same rule the skill's hook applies.
 *
 * <p>The profile is the one the project declares, when it declares one
 * ({@link DeclaredProfile}), and otherwise follows the language most of the
 * component's sources are written in, and the verification command follows the build tool the
 * component uses, so a TypeScript project gets TYPESCRIPT and {@code npm test}
 * rather than a Java default that analyzes nothing and a {@code mvn} that is
 * not there.
 */
public final class ProjectLayout {

    private static final String SOURCES = "src";
    private static final String NO_COMPONENT = "";
    private static final String JAVA_PROFILE = "JAVA";
    private static final String TYPESCRIPT_PROFILE = "TYPESCRIPT";
    private static final String VUE_PROFILE = "VUEJS";
    private static final String PERL_PROFILE = "PERL_FORMAT";
    private static final String PYTHON_PROFILE = "PYTHON";
    private static final List<String> BASE_PROFILES =
            List.of(JAVA_PROFILE, TYPESCRIPT_PROFILE, VUE_PROFILE, PERL_PROFILE, PYTHON_PROFILE);
    private static final String JAVA_EXTENSION = ".java";
    private static final String TYPESCRIPT_EXTENSION = ".ts";
    private static final String TYPESCRIPT_JSX_EXTENSION = ".tsx";
    private static final String VUE_EXTENSION = ".vue";
    private static final String PERL_SCRIPT_EXTENSION = ".pl";
    private static final String PERL_MODULE_EXTENSION = ".pm";
    private static final String PYTHON_EXTENSION = ".py";
    private static final List<String> JAVA_FILES = List.of(JAVA_EXTENSION);
    private static final List<String> TYPESCRIPT_FILES = List.of(TYPESCRIPT_EXTENSION, TYPESCRIPT_JSX_EXTENSION);
    private static final List<String> VUE_FILES = List.of(VUE_EXTENSION);
    private static final List<String> PERL_FILES = List.of(PERL_SCRIPT_EXTENSION, PERL_MODULE_EXTENSION);
    private static final List<String> PYTHON_FILES = List.of(PYTHON_EXTENSION);
    private static final List<String> ANY_SOURCE_FILES = List.of(
            JAVA_EXTENSION, TYPESCRIPT_EXTENSION, TYPESCRIPT_JSX_EXTENSION, VUE_EXTENSION,
            PERL_SCRIPT_EXTENSION, PERL_MODULE_EXTENSION, PYTHON_EXTENSION);
    private static final String USER_HOME = "user.home";
    private static final String MAVEN_WRAPPER = "mvnw";
    private static final String MAVEN_BUILD = "pom.xml";
    private static final String GRADLE_WRAPPER = "gradlew";
    private static final String GRADLE_BUILD = "build.gradle";
    private static final String GRADLE_KOTLIN_BUILD = "build.gradle.kts";
    private static final String NPM_MANIFEST = "package.json";
    private static final List<String> BUILD_FILES = List.of(
            MAVEN_BUILD, GRADLE_BUILD, GRADLE_KOTLIN_BUILD, NPM_MANIFEST, DeclaredProfile.FILE_NAME);
    private static final String SCRIPTS_FIELD = "scripts";
    private static final String TEST_SCRIPT = "test";
    private static final String MAVEN_WRAPPER_TEST = "cd %s && ./mvnw%s test";
    private static final String MAVEN_TEST = "cd %s && mvn%s test";
    private static final String GRADLE_WRAPPER_TEST = "cd %s && ./gradlew test";
    private static final String GRADLE_TEST = "cd %s && gradle test";
    private static final String MAVEN_WRAPPER_TEST_WINDOWS = "cd %s && mvnw.cmd%s test";
    private static final String QUIET = " -q";
    private static final String NOT_QUIET = "";
    private static final String GRADLE_WRAPPER_TEST_WINDOWS = "cd %s && gradlew.bat test";
    private static final String WINDOWS_QUOTE = "\"";
    private static final String NPM_TEST = "cd %s && npm test";
    private static final String NO_BUILD_TOOL = ShellTestRunner.NO_TESTS_COMMAND;
    private static final String QUOTE = "'";
    private static final String ESCAPED_QUOTE = "'\\''";
    private static final int NONE = 0;
    private static final int ONLY_ONE = 1;

    private final Path mount;
    private final String ownComponent;
    private final boolean searchable;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * A layout with its two halves already known.
     *
     * @param mount the directory Sheriff mounts
     * @param ownComponent the component the project itself is, or the empty
     *     string when it holds several
     * @param searchable whether looking for components under the mount makes
     *     sense at all
     */
    private ProjectLayout(Path mount, String ownComponent, boolean searchable) {
        this.mount = mount;
        this.ownComponent = ownComponent;
        this.searchable = searchable;
    }

    /**
     * Works out the layout of the project a client started this server in.
     *
     * <p>A directory inside a module's {@code src/} is taken as that module
     * ({@link #enclosingModule}).
     *
     * @param project the project directory
     * @return its layout
     */
    public static ProjectLayout detect(Path project) {
        Path root = enclosingModule(project.toAbsolutePath().normalize());
        Path parent = root.getParent();
        if (parent != null && root.getFileName() != null && Files.isDirectory(root.resolve(SOURCES))) {
            return new ProjectLayout(parent, root.getFileName().toString(), true);
        }
        return new ProjectLayout(root, NO_COMPONENT, !isNotAProject(root));
    }

    /**
     * The module a directory belongs to when it lies inside that module's
     * sources, and the directory itself otherwise.
     *
     * <p>Claude Code names the project ({@code CLAUDE_PROJECT_DIR}); Codex
     * does not, and runs both the server and the hooks in whatever directory
     * its session was opened in. Opened in {@code src/main/java/demo}, that
     * directory was the project: the server found nothing to analyze and the
     * Stop hook let every error through. Opened in {@code src/test}, the tests
     * were analyzed as a component of their own.
     *
     * <p>The nearest {@code src} above the directory decides, and only when
     * the folder holding it has a build file: {@code ~/work/src/weather} is a
     * project kept in a folder called {@code src}, not a source folder of
     * {@code ~/work}, and a Java package that happens to be called
     * {@code src} is not a module either. A directory that is a module itself
     * is never climbed out of, nor is a module passed on the way up, such as
     * a package kept under another project's {@code src/}; and the search
     * stops below the home directory and the root.
     *
     * @param directory an absolute, normalized directory
     * @return the module whose {@code src/} holds it, or the directory
     */
    static Path enclosingModule(Path directory) {
        if (Files.isDirectory(directory.resolve(SOURCES))) {
            return directory;
        }
        for (Path current = directory; current.getParent() != null; current = current.getParent()) {
            Path module = current.getParent();
            if (isNotAProject(module) || isModule(module) && !SOURCES.equals(String.valueOf(current.getFileName()))) {
                return directory;
            }
            if (SOURCES.equals(String.valueOf(current.getFileName())) && hasBuildFile(module)) {
                return module;
            }
        }
        return directory;
    }

    /**
     * Whether a directory is a module of its own: a build file, and sources
     * under {@code src/}.
     *
     * @param directory the directory
     * @return {@code true} for a module
     */
    private static boolean isModule(Path directory) {
        return Files.isDirectory(directory.resolve(SOURCES)) && hasBuildFile(directory);
    }

    /**
     * Whether a directory holds a file only a project's root has.
     *
     * @param directory the directory
     * @return {@code true} with a Maven, Gradle or npm build, or a Sheriff
     *     declaration
     */
    private static boolean hasBuildFile(Path directory) {
        return BUILD_FILES.stream().anyMatch(name -> Files.isRegularFile(directory.resolve(name)));
    }

    /**
     * Whether a directory is somewhere a client starts servers when it does
     * not start them in a project: the home directory, or the root.
     *
     * <p>Searched for components, either answers with every folder that
     * happens to hold a source file, after walking the whole of it.
     *
     * @param directory the directory the server was started in
     * @return {@code true} for those two
     */
    private static boolean isNotAProject(Path directory) {
        Path home = Path.of(System.getProperty(USER_HOME)).toAbsolutePath().normalize();
        return directory.getParent() == null || directory.equals(home);
    }

    /**
     * A layout whose mount was configured by hand, so nothing is inferred
     * about which component the project is.
     *
     * @param mount the directory to mount
     * @return that layout
     */
    public static ProjectLayout mountedAt(Path mount) {
        return new ProjectLayout(mount.toAbsolutePath().normalize(), NO_COMPONENT, true);
    }

    /**
     * The directory Sheriff mounts at {@code /data}.
     *
     * @return that directory
     */
    public Path mount() {
        return mount;
    }

    /**
     * The component the project itself is, when it is a single module.
     *
     * @return its name, or the empty string when the project holds several
     */
    public String ownComponent() {
        return ownComponent;
    }

    /**
     * Every component there is to analyze.
     *
     * @return the project itself when it is one module; otherwise every
     *     directory one level down that holds sources of a language Sheriff
     *     knows, sorted
     */
    public List<String> components() {
        if (!ownComponent.isEmpty()) {
            return List.of(ownComponent);
        }
        if (!searchable) {
            return List.of();
        }
        List<String> components = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(mount)) {
            for (Path entry : entries) {
                if (isCandidate(entry) && SourceCounter.holdsAny(entry, ANY_SOURCE_FILES)) {
                    components.add(entry.getFileName().toString());
                }
            }
        } catch (IOException exception) {
            return List.of();
        }
        components.sort(String::compareTo);
        return components;
    }

    /**
     * The component a call means when it names none.
     *
     * @return the project itself, or the only component it holds, or the
     *     empty string when there is no single answer
     */
    public String defaultComponent() {
        if (!ownComponent.isEmpty()) {
            return ownComponent;
        }
        List<String> components = components();
        return components.size() == ONLY_ONE ? components.get(NONE) : NO_COMPONENT;
    }

    /**
     * The profile the project declares for a component: in the component's
     * own directory, then, when the mount is the project and not only its
     * parent, at the project's root. A single-module project's mount holds
     * its siblings too, so a declaration there is not this project's.
     *
     * @param component the component, relative to the mount, or empty for
     *     the project as a whole
     * @return the declared profile, or empty when nothing declares one
     */
    public Optional<String> declaredProfile(String component) {
        List<Path> directories = new ArrayList<>();
        if (!component.isEmpty()) {
            directories.add(mount.resolve(component));
        }
        if (ownComponent.isEmpty() && searchable) {
            directories.add(mount);
        }
        return DeclaredProfile.in(directories);
    }

    /**
     * The profile for the language most of a component's sources are in.
     *
     * @param component the component, relative to the mount
     * @return the base profile of that language, or nothing when the
     *     component holds no sources Sheriff knows
     */
    public Optional<String> profileFor(String component) {
        Map<String, Integer> counts = sourceCounts(mount.resolve(component));
        String best = NO_COMPONENT;
        int bestCount = NONE;
        for (String profile : BASE_PROFILES) {
            int count = counts.getOrDefault(profile, NONE);
            if (count > bestCount) {
                best = profile;
                bestCount = count;
            }
        }
        return best.isEmpty() ? Optional.empty() : Optional.of(best);
    }

    /**
     * Whether a component holds anything the profile's language would look
     * at.
     *
     * <p>Zero errors has two causes and Sheriff reports them the same way:
     * the code passed, or there was no code of that language to look at.
     *
     * @param component the component, relative to the mount
     * @param profile the profile in use
     * @return {@code false} only when the component exists and holds none;
     *     a language nobody mapped, or a component that is not there, is not
     *     this check's to judge
     */
    public boolean holdsSourcesFor(String component, String profile) {
        List<String> suffixes = Rules.sourceSuffixesFor(Rules.languageForProfile(profile));
        Path root = mount.resolve(component);
        if (suffixes.isEmpty() || !Files.isDirectory(root)) {
            return true;
        }
        return SourceCounter.holdsAny(root, suffixes);
    }

    /**
     * The command that runs a component's own tests, for the autofix loop's
     * last gate.
     *
     * @param component the component, relative to the mount
     * @return a shell command run from the mount: the wrapper or tool the
     *     component builds with, or one that says nothing could be verified
     */
    public String verificationCommand(String component) {
        return verificationCommand(component, Platform.windows());
    }

    /**
     * The command a model runs for a component's tests, from wherever its
     * shell is: the component by its absolute path, and Maven not quiet.
     *
     * <p>The command the server runs is relative to the mount, which for a
     * single-module project is its parent: a model whose shell was in the
     * project ran {@code cd 'app'} and failed. And {@code -q} hides
     * {@code Tests run:} and {@code BUILD SUCCESS}, so a passing run printed
     * nothing a model could read as passing.
     *
     * @param component the component, relative to the mount
     * @return the command, or the one that says nothing could be verified
     */
    public String testCommandForModel(String component) {
        boolean windows = Platform.windows();
        String absolute = mount.resolve(component).toAbsolutePath().normalize().toString();
        return command(component, windows, windows ? WINDOWS_QUOTE + absolute + WINDOWS_QUOTE : quoted(absolute),
                NOT_QUIET);
    }

    /**
     * Whether the component has tests this server knows how to run: a Maven,
     * Gradle or npm build with a test script. Without one, the verification
     * command only says so, and must not be reported as tests that passed.
     *
     * @param component the component, relative to the mount
     * @return {@code true} when a build tool with tests was found
     */
    public boolean hasTests(String component) {
        return !NO_BUILD_TOOL.equals(verificationCommand(component, false));
    }

    /**
     * The command that runs a component's tests, written for a given
     * platform's shell: {@code cmd.exe} on Windows takes double quotes and
     * runs the wrappers as {@code mvnw.cmd} and {@code gradlew.bat}.
     *
     * @param component the component, relative to the mount
     * @param windows whether the command runs on Windows
     * @return the command, or empty when no build tool was recognized
     */
    String verificationCommand(String component, boolean windows) {
        String directory = windows ? WINDOWS_QUOTE + component + WINDOWS_QUOTE : quoted(component);
        return command(component, windows, directory, QUIET);
    }

    /**
     * The command that runs a component's tests, from a directory written
     * one way or another.
     *
     * @param component the component, relative to the mount
     * @param windows whether the command runs on Windows
     * @param directory the component's directory, quoted for the shell
     * @param quiet Maven's quiet option, or nothing
     * @return the command, or the one that says nothing could be verified
     */
    private String command(String component, boolean windows, String directory, String quiet) {
        Path root = mount.resolve(component);
        if (Files.isRegularFile(root.resolve(MAVEN_WRAPPER))) {
            return String.format(windows ? MAVEN_WRAPPER_TEST_WINDOWS : MAVEN_WRAPPER_TEST, directory, quiet);
        }
        if (Files.isRegularFile(root.resolve(MAVEN_BUILD))) {
            return String.format(MAVEN_TEST, directory, quiet);
        }
        if (Files.isRegularFile(root.resolve(GRADLE_WRAPPER))) {
            return String.format(windows ? GRADLE_WRAPPER_TEST_WINDOWS : GRADLE_WRAPPER_TEST, directory);
        }
        if (Files.isRegularFile(root.resolve(GRADLE_BUILD)) || Files.isRegularFile(root.resolve(GRADLE_KOTLIN_BUILD))) {
            return String.format(GRADLE_TEST, directory);
        }
        if (hasNpmTestScript(root.resolve(NPM_MANIFEST))) {
            return String.format(NPM_TEST, directory);
        }
        return NO_BUILD_TOOL;
    }

    /**
     * Whether a {@code package.json} declares a test script. Without one,
     * {@code npm test} fails, and the loop would read that as a broken build.
     *
     * @param manifest the manifest's path
     * @return {@code true} when it exists and has {@code scripts.test}
     */
    private boolean hasNpmTestScript(Path manifest) {
        if (!Files.isRegularFile(manifest)) {
            return false;
        }
        try {
            JsonNode root = json.readTree(Files.readString(manifest));
            return root != null && root.path(SCRIPTS_FIELD).hasNonNull(TEST_SCRIPT);
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * A path quoted for {@code sh}.
     *
     * @param path the path to quote
     * @return it, in single quotes
     */
    private static String quoted(String path) {
        return QUOTE + path.replace(QUOTE, ESCAPED_QUOTE) + QUOTE;
    }

    /**
     * How many of a directory's files speak for each base profile.
     *
     * <p>Counted by the extension that identifies a language, not by every
     * extension its profile reads: Sheriff's Vue profile also reads
     * {@code .ts} and {@code .js}, so counting those for it would call any
     * TypeScript project with a config file in JavaScript a Vue one. A
     * {@code .vue} file is what makes a project Vue, and then its TypeScript
     * counts towards Vue too.
     *
     * @param directory the directory to search
     * @return the count per profile
     */
    private static Map<String, Integer> sourceCounts(Path directory) {
        Map<String, List<String>> groups = new LinkedHashMap<>();
        groups.put(JAVA_PROFILE, JAVA_FILES);
        groups.put(TYPESCRIPT_PROFILE, TYPESCRIPT_FILES);
        groups.put(VUE_PROFILE, VUE_FILES);
        groups.put(PERL_PROFILE, PERL_FILES);
        groups.put(PYTHON_PROFILE, PYTHON_FILES);
        Map<String, Integer> found = SourceCounter.count(directory, groups);
        int vue = found.get(VUE_PROFILE);
        int typescript = found.get(TYPESCRIPT_PROFILE);
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put(JAVA_PROFILE, found.get(JAVA_PROFILE));
        counts.put(TYPESCRIPT_PROFILE, vue > NONE ? NONE : typescript);
        counts.put(VUE_PROFILE, vue > NONE ? vue + typescript : NONE);
        counts.put(PERL_PROFILE, found.get(PERL_PROFILE));
        counts.put(PYTHON_PROFILE, found.get(PYTHON_PROFILE));
        return counts;
    }

    /**
     * Whether a folder one level under the mount could be a component.
     *
     * @param entry the folder
     * @return {@code true} for a real folder that is not hidden, build output
     *     or dependencies
     */
    private static boolean isCandidate(Path entry) {
        return Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS) && SourceCounter.isSearched(entry);
    }
}
