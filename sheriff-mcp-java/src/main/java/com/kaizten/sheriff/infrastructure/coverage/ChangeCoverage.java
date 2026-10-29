package com.kaizten.sheriff.infrastructure.coverage;

import com.kaizten.sheriff.infrastructure.git.GitVersionControl;
import com.kaizten.sheriff.infrastructure.process.BuildOutput;
import com.kaizten.sheriff.infrastructure.process.Platform;
import com.kaizten.sheriff.infrastructure.process.ProcessOutcome;
import com.kaizten.sheriff.infrastructure.process.ProcessRunner;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Measures how well a component's tests test the code a change wrote: the
 * lines of it they run and the branches of its conditions they take, by
 * JaCoCo, and the changes to those lines they notice, by PIT's mutation
 * testing, with the mutators {@link CoverageThresholds} names.
 *
 * <p>Only the change, the lines of the main Java sources that the last commit
 * does not have, is held to the thresholds: a project with little coverage
 * from before would otherwise fail every change, and PIT on a whole project
 * takes hours where on the classes of a change it takes minutes.
 *
 * <p>Both tools run through the component's own Maven, with nothing added to
 * its {@code pom.xml}: JaCoCo's agent and report by their coordinates, and
 * PIT's goal by its own. PIT runs JUnit 5 only with its JUnit 5 plugin, which
 * a {@code pom.xml} would declare inside PIT's plugin; on the command line it
 * goes in as an additional classpath element, and PIT then adds the JUnit
 * platform launcher of the project's own version. With JUnit 4 alone that
 * plugin stops PIT ("Coverage generator Minion exited abnormally"), so it is
 * added only when a test imports JUnit 5. PIT runs the tests itself, without
 * surefire's configuration, and refuses a suite with a test that fails
 * there, so the system properties surefire gives the tests
 * ({@code systemPropertyVariables}, read from the effective {@code pom.xml},
 * inherited and resolved) go to PIT as its {@code argLine}. PIT splits that
 * line at every space, quotes or not, and its test process dies on a value
 * with one, so such a value is left out. All of it measured with Maven 3.10
 * on 19 October 2026.
 */
public final class ChangeCoverage {

    private static final String JACOCO = "org.jacoco:jacoco-maven-plugin:0.8.15";
    private static final String PITEST = "org.pitest:pitest-maven:1.30.0";
    private static final String JUNIT5_PLUGIN = "org.pitest:pitest-junit5-plugin:1.2.3";
    private static final String JUNIT5_PLUGIN_JAR = "pitest-junit5-plugin-1.2.3.jar";
    private static final String DEPENDENCY_COPY = "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy";
    private static final String EFFECTIVE_POM = "org.apache.maven.plugins:maven-help-plugin:3.5.1:effective-pom";
    private static final String OUTPUT = "-Doutput=";
    private static final String EFFECTIVE_POM_FILE = "effective-pom.xml";
    private static final String TEST_ARGUMENTS = "-DargLine=";
    private static final String ARGUMENT_SEPARATOR = " ";
    private static final String SYSTEM_PROPERTY = "-D";
    private static final String ASSIGNMENT = "=";
    private static final String BATCH = "-B";
    private static final String QUIET = "-q";
    private static final String TEST = "test";
    private static final String PREPARE_AGENT = JACOCO + ":prepare-agent";
    private static final String REPORT = JACOCO + ":report";
    private static final String KEEP_GOING_ON_FAILURES = "-Dmaven.test.failure.ignore=true";
    private static final String MUTATION_COVERAGE = PITEST + ":mutationCoverage";
    private static final String TARGET_CLASSES = "-DtargetClasses=";
    private static final String EVERY_TEST = "-DtargetTests=*";
    private static final String XML_ONLY = "-DoutputFormats=XML";
    private static final String NO_TIMESTAMP = "-DtimestampedReports=false";
    private static final String NO_MUTATIONS_IS_FINE = "-DfailWhenNoMutations=false";
    private static final String ADDITIONAL_CLASSPATH = "-DadditionalClasspathElements=";
    private static final String MUTATORS = "-Dmutators=";
    private static final String ARTIFACT = "-Dartifact=" + JUNIT5_PLUGIN;
    private static final String OUTPUT_DIRECTORY = "-DoutputDirectory=";
    private static final String MAIN_SOURCES = "src/main/java";
    private static final String TEST_SOURCES = "src/test/java";
    private static final String COVERAGE_REPORT = "target/site/jacoco/jacoco.xml";
    private static final String MUTATION_REPORT = "target/pit-reports/mutations.xml";
    private static final String PLUGIN_FOLDER = "target/sheriff-pitest";
    private static final String JAVA = ".java";
    private static final String JUNIT5 = "org.junit.jupiter";
    private static final String SLASH = "/";
    private static final String DOT = ".";
    private static final String INNER_CLASSES = "$*";
    private static final String CLASS_SEPARATOR = ",";
    private static final String NO_BRANCH_STAMP = "";
    private static final int OUTPUT_SHOWN = 1500;
    private static final int START = 0;
    private static final Duration COPY_TIMEOUT = Duration.ofMinutes(5);
    private static final String NO_COVERAGE_REPORT = "JaCoCo wrote no report, so the tests did not run with "
            + "its agent. A surefire argLine set in the pom.xml has to keep JaCoCo's, as @{argLine}. Maven said:%n%s";
    private static final String NO_MUTATION_REPORT = "PIT wrote no report. Maven said:%n%s";
    private static final String NO_JUNIT5_PLUGIN = "PIT's JUnit 5 plugin (%s) could not be downloaded. Maven "
            + "said:%n%s";
    private static final String UNREADABLE = "a report could not be read: %s";
    private static final String NOT_MAVEN = "the component does not build with Maven";
    private final ProcessRunner processes;
    private final Path mount;
    private final BiFunction<String, List<String>, Optional<String>> maven;
    private final Function<String, CoverageThresholds> thresholds;
    private final Duration coverageTimeout;
    private final Duration mutationTimeout;

    /**
     * Wires the measure to a project.
     *
     * @param processes how to run git and Maven
     * @param mount the directory Sheriff mounts, where Maven runs from
     * @param maven the command that runs Maven on a component with some
     *     arguments, empty when it does not build with Maven
     * @param thresholds what each component's tests are held to
     * @param coverageTimeout how long the tests get under JaCoCo
     * @param mutationTimeout how long PIT gets
     */
    public ChangeCoverage(ProcessRunner processes, Path mount,
            BiFunction<String, List<String>, Optional<String>> maven,
            Function<String, CoverageThresholds> thresholds, Duration coverageTimeout, Duration mutationTimeout) {
        this.processes = processes;
        this.mount = mount;
        this.maven = maven;
        this.thresholds = thresholds;
        this.coverageTimeout = coverageTimeout;
        this.mutationTimeout = mutationTimeout;
    }

    /**
     * Whether a component has a change to measure, which is cheap to know:
     * git, and no build.
     *
     * @param component the component, a folder of the mount
     * @return {@code true} for a Maven component whose main Java sources
     *     hold lines the last commit does not have
     */
    public boolean applies(String component) {
        return maven.apply(component, List.of()).isPresent() && !changes(component).isEmpty();
    }

    /**
     * Measures how well a component's tests test what the change wrote:
     * JaCoCo first, which runs the tests and compiles them for PIT, then PIT
     * on the classes the change touched.
     *
     * @param component the component, a folder of the mount
     * @return the verdict; nothing to measure when it does not apply
     */
    public CoverageVerdict measure(String component) {
        Map<String, Set<Integer>> changes = changes(component);
        if (changes.isEmpty() || maven.apply(component, List.of()).isEmpty()) {
            return CoverageVerdict.nothingToMeasure();
        }
        Path root = mount.resolve(component);
        try {
            CoverageThresholds held = thresholds.apply(component);
            Map<String, Map<Integer, CoveredLine>> lines = coverage(component, root);
            List<Mutant> mutants = mutation(component, root, changes.keySet(), held.mutators());
            return CoverageVerdict.of(changes, lines, mutants, held);
        } catch (IllegalStateException exception) {
            return CoverageVerdict.unmeasured(exception.getMessage());
        } catch (IOException exception) {
            return CoverageVerdict.unmeasured(String.format(UNREADABLE, exception.getMessage()));
        }
    }

    /**
     * The lines of each main Java source the last commit does not have.
     *
     * @param component the component
     * @return them per file, the file under {@code src/main/java}; empty when
     *     there are none, or git cannot say
     */
    private Map<String, Set<Integer>> changes(String component) {
        Path root = mount.resolve(component);
        Path sources = root.resolve(MAIN_SOURCES);
        Map<String, Set<Integer>> changed = new TreeMap<>();
        try {
            GitVersionControl git = new GitVersionControl(processes, mount, root, NO_BRANCH_STAMP);
            for (Path file : git.changedPaths()) {
                if (file.startsWith(sources) && file.toString().endsWith(JAVA) && Files.isRegularFile(file)) {
                    Set<Integer> lines = git.changedLines(file);
                    if (!lines.isEmpty()) {
                        changed.put(Platform.slashes(sources.relativize(file)), lines);
                    }
                }
            }
            return changed;
        } catch (IOException | IllegalStateException exception) {
            return Map.of();
        }
    }

    /**
     * Runs the tests under JaCoCo's agent and reads its report.
     *
     * @param component the component
     * @param root its folder
     * @return the lines of code of each file, run or not, with their branches
     * @throws IOException when the report cannot be read
     */
    private Map<String, Map<Integer, CoveredLine>> coverage(String component, Path root) throws IOException {
        Path report = root.resolve(COVERAGE_REPORT);
        Files.deleteIfExists(report);
        ProcessOutcome outcome = run(component, List.of(BATCH, QUIET, PREPARE_AGENT, TEST, REPORT,
                KEEP_GOING_ON_FAILURES), coverageTimeout);
        if (!Files.isRegularFile(report)) {
            throw new IllegalStateException(String.format(NO_COVERAGE_REPORT, said(outcome)));
        }
        return CoverageReports.lines(report);
    }

    /**
     * Runs PIT on the classes of the changed files and reads its report.
     *
     * @param component the component
     * @param root its folder
     * @param files the changed files, under {@code src/main/java}
     * @param mutators PIT's mutators, or groups of them, to make the mutants
     *     with
     * @return the mutants
     * @throws IOException when the report cannot be read
     */
    private List<Mutant> mutation(String component, Path root, Set<String> files, String mutators)
            throws IOException {
        List<String> arguments = new ArrayList<>(List.of(BATCH, QUIET, MUTATION_COVERAGE, TARGET_CLASSES
                + targets(files), EVERY_TEST, XML_ONLY, NO_TIMESTAMP, NO_MUTATIONS_IS_FINE, MUTATORS + mutators));
        if (usesJunit5(root)) {
            arguments.add(ADDITIONAL_CLASSPATH + junit5Plugin(component, root));
        }
        List<String> properties = surefireProperties(component, root);
        if (!properties.isEmpty()) {
            arguments.add(TEST_ARGUMENTS + String.join(ARGUMENT_SEPARATOR, properties));
        }
        Path report = root.resolve(MUTATION_REPORT);
        Files.deleteIfExists(report);
        ProcessOutcome outcome = run(component, arguments, mutationTimeout);
        if (Files.isRegularFile(report)) {
            return CoverageReports.mutants(report);
        }
        if (outcome.succeeded()) {
            return List.of();
        }
        throw new IllegalStateException(String.format(NO_MUTATION_REPORT, said(outcome)));
    }

    /**
     * The system properties surefire gives the component's tests, as Maven
     * resolves them, ready for a JVM's command line.
     *
     * @param component the component
     * @param root its folder
     * @return each one as {@code -Dname=value}; none when Maven could not
     *     write the effective {@code pom.xml}, and then PIT says which test
     *     failed without them
     * @throws IOException when the effective {@code pom.xml} cannot be read
     */
    private List<String> surefireProperties(String component, Path root) throws IOException {
        Path folder = root.resolve(PLUGIN_FOLDER).toAbsolutePath().normalize();
        Path effective = folder.resolve(EFFECTIVE_POM_FILE);
        Files.createDirectories(folder);
        Files.deleteIfExists(effective);
        run(component, List.of(BATCH, QUIET, EFFECTIVE_POM, OUTPUT + effective), COPY_TIMEOUT);
        if (!Files.isRegularFile(effective)) {
            return List.of();
        }
        return CoverageReports.surefireProperties(effective).entrySet().stream()
                .filter(property -> property.getValue().chars().noneMatch(Character::isWhitespace))
                .map(property -> SYSTEM_PROPERTY + property.getKey() + ASSIGNMENT + property.getValue())
                .toList();
    }

    /**
     * The classes PIT is to mutate: each changed file's, and the classes
     * inside it.
     *
     * @param files the changed files, under {@code src/main/java}
     * @return them as PIT's globs, {@code demo.Calc,demo.Calc$*}
     */
    private static String targets(Set<String> files) {
        return files.stream()
                .map(file -> file.substring(START, file.length() - JAVA.length()).replace(SLASH, DOT))
                .flatMap(name -> Stream.of(name, name + INNER_CLASSES))
                .collect(Collectors.joining(CLASS_SEPARATOR));
    }

    /**
     * Whether a test of the component imports JUnit 5. A folder that cannot
     * be read is reported while the walk goes on, as an unchecked exception.
     *
     * @param root the component's folder
     * @return {@code true} when one does
     */
    private static boolean usesJunit5(Path root) {
        Path tests = root.resolve(TEST_SOURCES);
        if (!Files.isDirectory(tests)) {
            return false;
        }
        try (Stream<Path> files = Files.walk(tests)) {
            return files.filter(file -> file.toString().endsWith(JAVA)).anyMatch(ChangeCoverage::importsJunit5);
        } catch (IOException | UncheckedIOException exception) {
            return false;
        }
    }

    /**
     * Whether a test source names JUnit 5.
     *
     * @param file the source
     * @return {@code true} when it does
     */
    private static boolean importsJunit5(Path file) {
        try {
            return Files.readString(file).contains(JUNIT5);
        } catch (IOException exception) {
            return false;
        }
    }

    /**
     * PIT's JUnit 5 plugin, copied from Maven's repository beside the build
     * output the first time.
     *
     * @param component the component
     * @param root its folder
     * @return the plugin's jar, by its absolute path
     */
    private Path junit5Plugin(String component, Path root) {
        Path folder = root.resolve(PLUGIN_FOLDER).toAbsolutePath().normalize();
        Path jar = folder.resolve(JUNIT5_PLUGIN_JAR);
        if (Files.isRegularFile(jar)) {
            return jar;
        }
        ProcessOutcome outcome = run(component, List.of(BATCH, QUIET, DEPENDENCY_COPY, ARTIFACT,
                OUTPUT_DIRECTORY + folder), COPY_TIMEOUT);
        if (!Files.isRegularFile(jar)) {
            throw new IllegalStateException(String.format(NO_JUNIT5_PLUGIN, JUNIT5_PLUGIN, said(outcome)));
        }
        return jar;
    }

    /**
     * Runs Maven on the component, from the mount.
     *
     * @param component the component
     * @param arguments Maven's options and goals
     * @param timeout how long it is given
     * @return what it did
     */
    private ProcessOutcome run(String component, List<String> arguments, Duration timeout) {
        String command = maven.apply(component, arguments).orElseThrow(() -> new IllegalStateException(NOT_MAVEN));
        return processes.run(Platform.shell(command), mount, Map.of(), timeout);
    }

    /**
     * What a run said about why it failed.
     *
     * @param outcome what the run did
     * @return the lines that say why and its last lines, or why it did not
     *     run
     */
    private static String said(ProcessOutcome outcome) {
        if (!outcome.ran()) {
            return outcome.failure();
        }
        return BuildOutput.excerpt(outcome.standardOutput() + outcome.standardError(), OUTPUT_SHOWN);
    }
}
